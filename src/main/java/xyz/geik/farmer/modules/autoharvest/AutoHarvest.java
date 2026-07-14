package xyz.geik.farmer.modules.autoharvest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.modules.FarmerModule;
import xyz.geik.farmer.modules.autoharvest.configuration.ConfigFile;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.ConfigurationMaintenance;
import xyz.geik.farmer.modules.autoharvest.configuration.LoggingSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.OptimizationSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.StackedCropSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.TelemetrySettings;
import xyz.geik.farmer.modules.autoharvest.configuration.TrackingSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.UpdateSettings;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestEvent;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestGuiCreateEvent;
import xyz.geik.farmer.modules.autoharvest.handlers.CropHarvesting;
import xyz.geik.farmer.modules.autoharvest.logging.BoundedErrorLogger;
import xyz.geik.farmer.modules.autoharvest.logging.ModuleDiagnostics;
import xyz.geik.farmer.modules.autoharvest.optimization.OptimizedHarvestQueue;
import xyz.geik.farmer.modules.autoharvest.platform.PaperPlatform;
import xyz.geik.farmer.modules.autoharvest.tracking.CropTrackingService;
import xyz.geik.farmer.modules.autoharvest.update.UpdateChecker;
import xyz.geik.glib.GLib;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AutoHarvest module main class.
 *
 * @author poyraz
 * @author siberanka
 */
public class AutoHarvest extends FarmerModule {

    private static final Set<String> BUNDLED_LANGUAGES = Set.of("en", "tr", "de");

    private static volatile AutoHarvest instance;

    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final OptimizedHarvestQueue optimizedHarvestQueue = new OptimizedHarvestQueue();

    private AutoHarvestEvent autoHarvestEvent;
    private AutoHarvestGuiCreateEvent autoHarvestGuiCreateEvent;
    private CropTrackingService cropTrackingService;
    private UpdateChecker updateChecker;

    private volatile boolean requirePiston;
    private volatile boolean checkAllDirections;
    private volatile boolean withoutFarmer;
    private volatile boolean checkStock = true;
    private volatile boolean active;

    private volatile String customPerm = "farmer.autoharvest";
    private volatile Set<XMaterial> crops = Collections.emptySet();
    private volatile Set<XMaterial> stackedCrops = Collections.emptySet();
    private volatile Map<Material, XMaterial> cropMaterials = Collections.emptyMap();
    private volatile int maxStackedSegmentsPerHarvest = 32;
    private volatile StackedCropSettings stackedCropSettings = StackedCropSettings.disabled();
    private volatile OptimizationSettings optimizationSettings = OptimizationSettings.DEFAULT;
    private volatile OptimizationSettings activeOptimizationSettings = OptimizationSettings.baseline();
    private volatile TrackingSettings configuredTrackingSettings = TrackingSettings.baseline();
    private volatile TrackingSettings activeTrackingSettings = TrackingSettings.baseline();
    private volatile BackpressureSettings configuredBackpressure = BackpressureSettings.DEFAULT;
    private volatile BackpressureSettings activeBackpressure = BackpressureSettings.BASELINE;
    private volatile LoggingSettings loggingSettings = LoggingSettings.DEFAULT;
    private volatile TelemetrySettings activeTelemetry = TelemetrySettings.DISABLED;
    private volatile UpdateSettings updateSettings = UpdateSettings.DEFAULT;
    private volatile ModuleDiagnostics diagnostics = ModuleDiagnostics.NOOP;

    private BoundedErrorLogger boundedErrorLogger;

    private ConfigFile configFile;

    @Override
    public void onEnable() {
        instance = this;
        lifecycleGeneration.incrementAndGet();
        active = false;

        if (!PaperPlatform.isSupported()) {
            setEnabled(false);
            ChatUtils.sendMessage(Bukkit.getConsoleSender(),
                    "&3[" + GLib.getInstance().getName() + "] &c" + getName()
                            + " requires Paper, Folia or Leaf. Bukkit/Spigot is unsupported.");
            return;
        }

        startDiagnostics();

        if (!loadConfigurationFiles()) {
            setEnabled(false);
            return;
        }

        if (configFile.isStatus()) {
            if (!activateRuntime()) {
                setEnabled(false);
                return;
            }
            ChatUtils.sendMessage(Bukkit.getConsoleSender(),
                    "&3[" + GLib.getInstance().getName() + "] &a" + getName() + " enabled.");
            ChatUtils.sendMessage(Bukkit.getConsoleSender(), "&3[" + GLib.getInstance().getName()
                    + "] &7Platform: " + PaperPlatform.getPlatformName());
        }
        else {
            setHasGui(false);
            ChatUtils.sendMessage(Bukkit.getConsoleSender(),
                    "&3[" + GLib.getInstance().getName() + "] &c" + getName() + " is not loaded.");
        }
        startUpdateChecker();
    }

    @Override
    public void onReload() {
        if (!isEnabled()) {
            return;
        }

        lifecycleGeneration.incrementAndGet();
        stopUpdateChecker();
        unregisterListeners();
        optimizedHarvestQueue.configure(OptimizationSettings.stopped(),
                BackpressureSettings.BASELINE, TelemetrySettings.DISABLED, diagnostics);

        if (!loadConfigurationFiles()) {
            return;
        }

        if (configFile.isStatus()) {
            if (!activateRuntime()) {
                setEnabled(false);
                return;
            }
        }
        else {
            unregisterListeners();
        }
        startUpdateChecker();
    }

    @Override
    public void onDisable() {
        active = false;
        lifecycleGeneration.incrementAndGet();
        stopUpdateChecker();
        optimizedHarvestQueue.configure(OptimizationSettings.stopped(),
                BackpressureSettings.BASELINE, TelemetrySettings.DISABLED, diagnostics);
        unregisterListeners();
        crops = Collections.emptySet();
        stackedCrops = Collections.emptySet();
        cropMaterials = Collections.emptyMap();
        maxStackedSegmentsPerHarvest = 32;
        stackedCropSettings = StackedCropSettings.disabled();
        optimizationSettings = OptimizationSettings.DEFAULT;
        activeOptimizationSettings = OptimizationSettings.baseline();
        configuredTrackingSettings = TrackingSettings.baseline();
        activeTrackingSettings = TrackingSettings.baseline();
        configuredBackpressure = BackpressureSettings.DEFAULT;
        activeBackpressure = BackpressureSettings.BASELINE;
        loggingSettings = LoggingSettings.DEFAULT;
        activeTelemetry = TelemetrySettings.DISABLED;
        updateSettings = UpdateSettings.DEFAULT;
        stopDiagnostics();
        if (instance == this) {
            instance = null;
        }
    }

    private boolean activateRuntime() {
        try {
            applyConfiguration();
            setHasGui(true);
            active = true;
            registerListeners();
            cropTrackingService.start(activeTrackingSettings, activeBackpressure, activeTelemetry);
            optimizedHarvestQueue.setChunkDrainListener(cropTrackingService::onHarvestQueueDrained);
            return true;
        }
        catch (RuntimeException | LinkageError exception) {
            active = false;
            optimizedHarvestQueue.configure(OptimizationSettings.stopped(),
                    BackpressureSettings.BASELINE, TelemetrySettings.DISABLED, diagnostics);
            try {
                unregisterListeners();
            }
            catch (RuntimeException | LinkageError cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            logCritical("AutoHarvest could not activate its runtime; the module was disabled.", exception);
            return false;
        }
    }

    private void registerListeners() {
        if (autoHarvestEvent == null) {
            autoHarvestEvent = new AutoHarvestEvent();
            Bukkit.getPluginManager().registerEvents(autoHarvestEvent, Main.getInstance());
        }
        if (cropTrackingService == null) {
            cropTrackingService = new CropTrackingService(this, autoHarvestEvent);
            Bukkit.getPluginManager().registerEvents(cropTrackingService, Main.getInstance());
        }
        if (autoHarvestGuiCreateEvent == null) {
            autoHarvestGuiCreateEvent = new AutoHarvestGuiCreateEvent();
            Bukkit.getPluginManager().registerEvents(autoHarvestGuiCreateEvent, Main.getInstance());
        }
    }

    private void unregisterListeners() {
        active = false;
        setHasGui(false);
        if (cropTrackingService != null) {
            optimizedHarvestQueue.setChunkDrainListener(null);
            cropTrackingService.stop();
            HandlerList.unregisterAll(cropTrackingService);
            cropTrackingService = null;
        }
        if (autoHarvestEvent != null) {
            HandlerList.unregisterAll(autoHarvestEvent);
            autoHarvestEvent = null;
        }
        if (autoHarvestGuiCreateEvent != null) {
            autoHarvestGuiCreateEvent.clear();
            HandlerList.unregisterAll(autoHarvestGuiCreateEvent);
            autoHarvestGuiCreateEvent = null;
        }
    }

    private boolean loadConfigurationFiles() {
        try {
            File moduleDirectory = getModuleDirectory();
            String language = resolveLanguage();
            repairLanguageFiles(moduleDirectory);
            setLang(language, getClass());

            try (InputStream bundledConfig = openBundledResource("autoharvest/config.yml")) {
                ConfigurationMaintenance.ConfigSnapshot snapshot = ConfigurationMaintenance.reconcileConfig(
                        new File(moduleDirectory, "config.yml"),
                        bundledConfig,
                        Main.getInstance().getLogger()
                );
                configFile = snapshot.config();
                stackedCropSettings = snapshot.stackedCrops();
                optimizationSettings = snapshot.optimization();
                configuredTrackingSettings = snapshot.tracking();
                configuredBackpressure = snapshot.backpressure();
                loggingSettings = snapshot.logging();
                if (boundedErrorLogger != null) {
                    boundedErrorLogger.configure(loggingSettings);
                }
                updateSettings = snapshot.update();
            }
            return true;
        }
        catch (IOException | RuntimeException exception) {
            logCritical("AutoHarvest could not safely load its configuration; the module was not enabled.", exception);
            return false;
        }
    }

    private void repairLanguageFiles(File moduleDirectory) throws IOException {
        File languageDirectory = new File(moduleDirectory, "lang");
        for (String language : BUNDLED_LANGUAGES) {
            File target = new File(languageDirectory, language + ".yml");
            try (InputStream defaults = openBundledResource("autoharvest/lang/" + language + ".yml")) {
                ConfigurationMaintenance.reconcileLanguageFile(target, defaults, Main.getInstance().getLogger());
            }
        }
    }

    private String resolveLanguage() {
        String requested = Main.getConfigFile().getSettings().getLang();
        String normalized = requested == null ? "en" : requested.trim().toLowerCase(Locale.ROOT);
        if (BUNDLED_LANGUAGES.contains(normalized)) {
            return normalized;
        }
        Main.getInstance().getLogger().warning("AutoHarvest has no bundled '" + normalized
                + "' language; falling back to English.");
        return "en";
    }

    private InputStream openBundledResource(String path) {
        InputStream resource = getClass().getClassLoader().getResourceAsStream(path);
        if (resource == null) {
            throw new IllegalStateException("Missing bundled AutoHarvest resource: " + path);
        }
        return resource;
    }

    private File getModuleDirectory() {
        return new File(Main.getInstance().getDataFolder(),
                "modules/" + getName().toLowerCase(Locale.ROOT));
    }

    private void startUpdateChecker() {
        stopUpdateChecker();
        UpdateChecker checker = null;
        try {
            checker = new UpdateChecker(this, updateSettings);
            checker.start();
            updateChecker = checker;
        }
        catch (RuntimeException | LinkageError exception) {
            if (checker != null) {
                try {
                    checker.stop();
                }
                catch (RuntimeException | LinkageError cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            logError("AutoHarvest update checks could not start; harvesting remains enabled.", exception);
        }
    }

    private void stopUpdateChecker() {
        UpdateChecker checker = updateChecker;
        updateChecker = null;
        if (checker != null) {
            try {
                checker.stop();
            }
            catch (RuntimeException | LinkageError exception) {
                logError("AutoHarvest update checks could not stop cleanly.", exception);
            }
        }
    }

    private void applyConfiguration() {
        Set<XMaterial> configuredCrops = parseCrops(configFile.getItems());
        stackedCrops = stackedCropSettings.enabled()
                ? parseStackedCrops(stackedCropSettings.items()) : Collections.emptySet();
        EnumSet<XMaterial> allCrops = EnumSet.noneOf(XMaterial.class);
        allCrops.addAll(configuredCrops);
        allCrops.addAll(stackedCrops);
        crops = allCrops.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(allCrops);
        cropMaterials = CropHarvesting.configuredBlockMaterials(crops);
        maxStackedSegmentsPerHarvest = stackedCropSettings.maxSegmentsPerHarvest();
        requirePiston = configFile.isRequirePiston();
        checkAllDirections = configFile.isCheckAllDirections();
        withoutFarmer = configFile.isWithoutFarmer();
        checkStock = configFile.isCheckStock();
        customPerm = configFile.getCustomPerm();
        setDefaultState(configFile.isDefaultStatus());
        boolean configuredOptimization = optimizationSettings.enabled();
        activeOptimizationSettings = configuredOptimization
                ? optimizationSettings : OptimizationSettings.baseline();
        activeTrackingSettings = configuredOptimization ? configuredTrackingSettings : TrackingSettings.baseline();
        activeBackpressure = configuredOptimization ? configuredBackpressure : BackpressureSettings.BASELINE;
        activeTelemetry = loggingSettings.telemetry();
        optimizedHarvestQueue.configure(activeOptimizationSettings, activeBackpressure, activeTelemetry, diagnostics);
    }

    private Set<XMaterial> parseCrops(List<String> configuredCrops) {
        EnumSet<XMaterial> parsed = EnumSet.noneOf(XMaterial.class);
        for (String crop : configuredCrops) {
            XMaterial.matchXMaterial(crop)
                    .map(CropHarvesting::normalize)
                    .filter(CropHarvesting::isSupportedCrop)
                    .ifPresent(parsed::add);
        }
        return parsed.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(parsed);
    }

    private Set<XMaterial> parseStackedCrops(List<String> configuredCrops) {
        EnumSet<XMaterial> parsed = EnumSet.noneOf(XMaterial.class);
        for (String crop : configuredCrops) {
            XMaterial.matchXMaterial(crop)
                    .map(CropHarvesting::normalize)
                    .filter(CropHarvesting::isStackCrop)
                    .ifPresent(parsed::add);
        }
        return parsed.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(parsed);
    }

    /**
     * Schedules one validated crop action through the globally and per-scope
     * bounded queue. Overflow is reconciled later and never becomes a direct
     * scheduler flood.
     */
    public void scheduleHarvest(
            @NotNull String scopeKey,
            @NotNull Location location,
            @NotNull Runnable action
    ) {
        OptimizedHarvestQueue.SubmitResult result = optimizedHarvestQueue.submit(
                Main.getInstance(), location, scopeKey, action);
        if (result == OptimizedHarvestQueue.SubmitResult.ENQUEUED
                || result == OptimizedHarvestQueue.SubmitResult.COALESCED) {
            return;
        }
        CropTrackingService tracker = cropTrackingService;
        if (result == OptimizedHarvestQueue.SubmitResult.DEFERRED && tracker != null && active) {
            tracker.requestReconciliation(location);
        }
    }

    public boolean hasPendingHarvests(@NotNull UUID worldId, int chunkX, int chunkZ) {
        return optimizedHarvestQueue.hasPendingJobs(worldId, chunkX, chunkZ);
    }

    public static boolean checkCrop(XMaterial material) {
        AutoHarvest module = instance;
        return module != null && module.active && module.crops.contains(material);
    }

    public XMaterial resolveCrop(@NotNull Material material) {
        return cropMaterials.get(material);
    }

    public void scanAround(@NotNull Player player) {
        CropTrackingService tracker = cropTrackingService;
        if (tracker != null && active) {
            tracker.scanAround(player, activeTrackingSettings.purchaseRadiusChunks());
        }
    }

    public static AutoHarvest getInstance() {
        return instance;
    }

    public boolean isRequirePiston() {
        return requirePiston;
    }

    public boolean isCheckAllDirections() {
        return checkAllDirections;
    }

    public boolean isWithoutFarmer() {
        return withoutFarmer;
    }

    public boolean isCheckStock() {
        return checkStock;
    }

    public String getCustomPerm() {
        return customPerm;
    }

    public Set<XMaterial> getCrops() {
        return crops;
    }

    public boolean isStackedCropEnabled(@NotNull XMaterial material) {
        return stackedCrops.contains(material);
    }

    public int getMaxStackedSegmentsPerHarvest() {
        return maxStackedSegmentsPerHarvest;
    }

    public Map<Material, XMaterial> getCropMaterials() {
        return cropMaterials;
    }

    public ConfigFile getConfigFile() {
        return configFile;
    }

    public OptimizationSettings getOptimizationSettings() {
        return optimizationSettings;
    }

    public OptimizationSettings getActiveOptimizationSettings() {
        return activeOptimizationSettings;
    }

    public TrackingSettings getActiveTrackingSettings() {
        return activeTrackingSettings;
    }

    public long getLifecycleGeneration() {
        return lifecycleGeneration.get();
    }

    public boolean isActiveGeneration(long generation) {
        return active && lifecycleGeneration.get() == generation;
    }

    public void logDebug(@NotNull String message) {
        diagnostics.debug(message);
    }

    public void logError(@NotNull String message, Throwable exception) {
        diagnostics.error(message, exception);
    }

    private void logCritical(String message, Throwable exception) {
        logError(message, exception);
        Main.getInstance().getLogger().severe(message + " Details were written to modules/autoharvest/error.log.");
    }

    private void startDiagnostics() {
        stopDiagnostics();
        boundedErrorLogger = new BoundedErrorLogger(
                getModuleDirectory().toPath(), Main.getInstance().getLogger(), LoggingSettings.DEFAULT);
        diagnostics = boundedErrorLogger;
    }

    private void stopDiagnostics() {
        diagnostics = ModuleDiagnostics.NOOP;
        BoundedErrorLogger logger = boundedErrorLogger;
        boundedErrorLogger = null;
        if (logger != null) {
            logger.close();
        }
    }
}
