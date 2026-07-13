package xyz.geik.farmer.modules.autoharvest.update;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.modules.autoharvest.AutoHarvest;
import xyz.geik.farmer.modules.autoharvest.configuration.UpdateSettings;
import xyz.geik.glib.chat.ChatUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Checks the fixed project repository without blocking a server thread and
 * notifies only the console and authorized administrators.
 */
public final class UpdateChecker implements Listener {

    static final String ADMIN_PERMISSION = "farmer.admin";
    private static final URI RELEASE_API = URI.create(
            "https://api.github.com/repos/siberanka/TwiFarmer-AutoHarvest/releases/latest");
    private static final long TICKS_PER_HOUR = 72_000L;
    private static final long FAILURE_LOG_INTERVAL_NANOS = 3_600_000_000_000L;
    private static final int MAX_NOTIFIED_PLAYERS = 4_096;

    private final AutoHarvest module;
    private final Plugin plugin;
    private final UpdateSettings settings;
    private final String currentVersion;
    private final HttpClient client;
    private final AtomicBoolean requestRunning = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicReference<CompletableFuture<?>> request = new AtomicReference<>();
    private final ConcurrentMap<UUID, String> notifiedPlayers = new ConcurrentHashMap<>();

    private volatile ScheduledTask periodicTask;
    private volatile GitHubReleaseParser.ReleaseInfo availableUpdate;
    private volatile String consoleNotifiedTag;
    private volatile String responseEtag;
    private volatile boolean running;

    public UpdateChecker(@NotNull AutoHarvest module, @NotNull UpdateSettings settings) {
        this.module = module;
        this.plugin = Main.getInstance();
        this.settings = settings;
        this.currentVersion = resolveCurrentVersion(module.getClass());
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void start() {
        stop();
        if (!settings.enabled()) {
            return;
        }
        if (currentVersion == null || ReleaseVersion.parse(currentVersion).isEmpty()) {
            plugin.getLogger().warning("Farmer AutoHarvest update check is disabled: current version is unavailable.");
            return;
        }

        running = true;
        long serviceGeneration = generation.incrementAndGet();
        long moduleGeneration = module.getLifecycleGeneration();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        checkNow(serviceGeneration, moduleGeneration);
        try {
            long intervalTicks = Math.multiplyExact(settings.checkIntervalHours(), TICKS_PER_HOUR);
            periodicTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    ignored -> checkNow(serviceGeneration, moduleGeneration), intervalTicks, intervalTicks);
        }
        catch (RuntimeException exception) {
            logFailure("could not schedule periodic update checks", exception);
        }
    }

    public void stop() {
        synchronized (notifiedPlayers) {
            running = false;
            availableUpdate = null;
            notifiedPlayers.clear();
        }
        generation.incrementAndGet();
        HandlerList.unregisterAll(this);
        ScheduledTask task = periodicTask;
        periodicTask = null;
        if (task != null) {
            task.cancel();
        }
        CompletableFuture<?> activeRequest = request.getAndSet(null);
        if (activeRequest != null) {
            activeRequest.cancel(true);
        }
        requestRunning.set(false);
        consoleNotifiedTag = null;
        responseEtag = null;
    }

    private void checkNow(long serviceGeneration, long moduleGeneration) {
        if (!isCurrent(serviceGeneration, moduleGeneration) || !requestRunning.compareAndSet(false, true)) {
            return;
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(RELEASE_API)
                .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Farmer-AutoHarvest/" + currentVersion)
                .GET();
        String etag = responseEtag;
        if (etag != null) {
            requestBuilder.header("If-None-Match", etag);
        }
        HttpRequest httpRequest = requestBuilder.build();

        CompletableFuture<Void> future = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> handleResponse(response, serviceGeneration, moduleGeneration))
                .exceptionally(exception -> {
                    if (isCurrent(serviceGeneration, moduleGeneration)) {
                        logFailure("could not query GitHub releases", unwrap(exception));
                    }
                    return null;
                })
                .whenComplete((ignored, exception) -> {
                    if (generation.get() == serviceGeneration) {
                        requestRunning.set(false);
                    }
                });
        request.set(future);
    }

    private void handleResponse(
            HttpResponse<InputStream> response,
            long serviceGeneration,
            long moduleGeneration
    ) {
        if (!isCurrent(serviceGeneration, moduleGeneration)) {
            closeResponse(response);
            return;
        }
        if (response.statusCode() == 304) {
            try (InputStream stream = response.body()) {
                return;
            }
            catch (IOException exception) {
                logFailure("could not close the GitHub release response", exception);
                return;
            }
        }
        String body;
        try (InputStream stream = response.body()) {
            if (response.statusCode() != 200) {
                logFailure("GitHub release API returned HTTP " + response.statusCode(), null);
                return;
            }
            byte[] bytes = stream.readNBytes(GitHubReleaseParser.MAX_RESPONSE_LENGTH + 1);
            if (bytes.length > GitHubReleaseParser.MAX_RESPONSE_LENGTH) {
                logFailure("GitHub release API response exceeded the size limit", null);
                return;
            }
            body = new String(bytes, StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            logFailure("could not read the GitHub release response", exception);
            return;
        }
        response.headers().firstValue("ETag")
                .filter(value -> value.length() <= 256 && value.matches("[\\x21-\\x7E]+"))
                .ifPresent(value -> responseEtag = value);
        GitHubReleaseParser.ReleaseInfo latest = GitHubReleaseParser.parse(body).orElse(null);
        if (latest == null) {
            logFailure("GitHub release API returned an invalid response", null);
            return;
        }
        if (!ReleaseVersion.isNewer(currentVersion, latest.tag())) {
            availableUpdate = null;
            return;
        }
        GitHubReleaseParser.ReleaseInfo previous = availableUpdate;
        availableUpdate = latest;
        if (previous == null || !previous.tag().equals(latest.tag())) {
            notifiedPlayers.clear();
            notifyConsoleAndOnlineAdmins(latest, serviceGeneration, moduleGeneration);
        }
    }

    private void closeResponse(HttpResponse<InputStream> response) {
        try (InputStream ignored = response.body()) {
            // Closing stale response bodies releases the HTTP connection promptly.
        }
        catch (IOException exception) {
            logFailure("could not close a stale GitHub release response", exception);
        }
    }

    private void notifyConsoleAndOnlineAdmins(
            GitHubReleaseParser.ReleaseInfo update,
            long serviceGeneration,
            long moduleGeneration
    ) {
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                synchronized (notifiedPlayers) {
                    if (!isCurrent(serviceGeneration, moduleGeneration) || availableUpdate != update) {
                        return;
                    }
                    if (!update.tag().equals(consoleNotifiedTag)) {
                        consoleNotifiedTag = update.tag();
                        ChatUtils.sendMessage(Bukkit.getConsoleSender(), notificationMessage(update));
                    }
                }
                for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                    player.getScheduler().run(plugin, task -> notifyPlayer(player, update), null);
                }
            });
        }
        catch (RuntimeException exception) {
            logFailure("could not schedule update notifications", exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        GitHubReleaseParser.ReleaseInfo update = availableUpdate;
        if (running && update != null) {
            notifyPlayer(event.getPlayer(), update);
        }
    }

    private void notifyPlayer(Player player, GitHubReleaseParser.ReleaseInfo update) {
        if (!canNotify(player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        synchronized (notifiedPlayers) {
            if (!running || availableUpdate != update) {
                return;
            }
            if (update.tag().equals(notifiedPlayers.get(playerId))) {
                return;
            }
            if (notifiedPlayers.size() < MAX_NOTIFIED_PLAYERS) {
                notifiedPlayers.put(playerId, update.tag());
            }
            ChatUtils.sendMessage(player, notificationMessage(update));
        }
    }

    private String notificationMessage(GitHubReleaseParser.ReleaseInfo update) {
        String template = module.getLang().getString("update.available");
        if (template == null || template.isBlank()) {
            template = "&e[{module}] Update available: &f{current} &7-> &a{latest}&7. Download: &b{url}";
        }
        String moduleName = module.getName();
        if (moduleName == null || moduleName.isBlank()) {
            moduleName = "AutoHarvest";
        }
        return formatMessage(template, moduleName, currentVersion, update.tag(), update.downloadUrl());
    }

    static boolean canNotify(Player player) {
        return player.isOp() || player.hasPermission(ADMIN_PERMISSION);
    }

    static String formatMessage(String template, String moduleName, String current, String latest, String url) {
        return template
                .replace("{module}", moduleName)
                .replace("{current}", current)
                .replace("{latest}", latest)
                .replace("{url}", url);
    }

    static String resolveCurrentVersion(Class<?> type) {
        Package modulePackage = type.getPackage();
        return modulePackage == null ? null : modulePackage.getImplementationVersion();
    }

    private boolean isCurrent(long serviceGeneration, long moduleGeneration) {
        return running && generation.get() == serviceGeneration
                && module.getLifecycleGeneration() == moduleGeneration;
    }

    private void logFailure(String message, Throwable exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if (now < next || !nextFailureLogNanos.compareAndSet(next, now + FAILURE_LOG_INTERVAL_NANOS)) {
            return;
        }
        if (exception == null) {
            plugin.getLogger().warning("Farmer AutoHarvest " + message + '.');
        }
        else {
            plugin.getLogger().log(Level.WARNING, "Farmer AutoHarvest " + message + '.', exception);
        }
    }

    private Throwable unwrap(Throwable exception) {
        return exception.getCause() == null ? exception : exception.getCause();
    }
}
