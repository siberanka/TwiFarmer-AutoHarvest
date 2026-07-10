package xyz.geik.farmer.modules.autoharvest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.modules.FarmerModule;
import xyz.geik.farmer.modules.autoharvest.configuration.ConfigFile;
import xyz.geik.farmer.modules.autoharvest.configuration.ConfigurationMaintenance;
import xyz.geik.farmer.modules.autoharvest.configuration.OptimizationSettings;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestEvent;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestGuiCreateEvent;
import xyz.geik.farmer.modules.autoharvest.handlers.CropHarvesting;
import xyz.geik.farmer.modules.autoharvest.optimization.OptimizedHarvestQueue;
import xyz.geik.farmer.modules.autoharvest.platform.PaperPlatform;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

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

    private volatile boolean requirePiston;
    private volatile boolean checkAllDirections;
    private volatile boolean withoutFarmer;
    private volatile boolean checkStock = true;
    private volatile boolean active;

    private volatile String customPerm = "farmer.autoharvest";
    private volatile Set<XMaterial> crops = Collections.emptySet();
    private volatile OptimizationSettings optimizationSettings = OptimizationSettings.disabled();

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

        if (!loadConfigurationFiles()) {
            setEnabled(false);
            return;
        }

        if (configFile.isStatus()) {
            activateRuntime();
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
    }

    @Override
    public void onReload() {
        if (!isEnabled()) {
            return;
        }

        lifecycleGeneration.incrementAndGet();
        unregisterListeners();
        optimizedHarvestQueue.configure(OptimizationSettings.disabled());

        if (!loadConfigurationFiles()) {
            return;
        }

        if (configFile.isStatus()) {
            activateRuntime();
        }
        else {
            unregisterListeners();
        }
    }

    @Override
    public void onDisable() {
        active = false;
        lifecycleGeneration.incrementAndGet();
        optimizedHarvestQueue.configure(OptimizationSettings.disabled());
        unregisterListeners();
        crops = Collections.emptySet();
        optimizationSettings = OptimizationSettings.disabled();
        if (instance == this) {
            instance = null;
        }
    }

    private void activateRuntime() {
        applyConfiguration();
        registerListeners();
        setHasGui(true);
        active = true;
    }

    private void registerListeners() {
        if (autoHarvestEvent == null) {
            autoHarvestEvent = new AutoHarvestEvent();
            Bukkit.getPluginManager().registerEvents(autoHarvestEvent, Main.getInstance());
        }
        if (autoHarvestGuiCreateEvent == null) {
            autoHarvestGuiCreateEvent = new AutoHarvestGuiCreateEvent();
            Bukkit.getPluginManager().registerEvents(autoHarvestGuiCreateEvent, Main.getInstance());
        }
    }

    private void unregisterListeners() {
        active = false;
        setHasGui(false);
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
                optimizationSettings = snapshot.optimization();
            }
            return true;
        }
        catch (IOException | RuntimeException exception) {
            Main.getInstance().getLogger().log(Level.SEVERE,
                    "AutoHarvest could not safely load its configuration; the module was not enabled.", exception);
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

    private void applyConfiguration() {
        crops = parseCrops(configFile.getItems());
        requirePiston = configFile.isRequirePiston();
        checkAllDirections = configFile.isCheckAllDirections();
        withoutFarmer = configFile.isWithoutFarmer();
        checkStock = configFile.isCheckStock();
        customPerm = configFile.getCustomPerm();
        setDefaultState(configFile.isDefaultStatus());
        optimizedHarvestQueue.configure(optimizationSettings);
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

    /**
     * Schedules one validated crop action. When optimize-module is off this
     * preserves the normal one-tick region delay.
     */
    public void scheduleHarvest(@NotNull Location location, @NotNull Runnable action) {
        OptimizedHarvestQueue.SubmitResult result = optimizedHarvestQueue.submit(
                Main.getInstance(), location, action, Main.getInstance().getLogger());
        if (result == OptimizedHarvestQueue.SubmitResult.ENQUEUED
                || result == OptimizedHarvestQueue.SubmitResult.COALESCED) {
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(Main.getInstance(), location.clone(), ignored -> action.run(), 1L);
    }

    public static boolean checkCrop(XMaterial material) {
        AutoHarvest module = instance;
        return module != null && module.active && module.crops.contains(material);
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

    public ConfigFile getConfigFile() {
        return configFile;
    }

    public OptimizationSettings getOptimizationSettings() {
        return optimizationSettings;
    }

    public long getLifecycleGeneration() {
        return lifecycleGeneration.get();
    }

    public boolean isActiveGeneration(long generation) {
        return active && lifecycleGeneration.get() == generation;
    }
}
