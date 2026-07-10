package xyz.geik.farmer.modules.autoharvest;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.modules.FarmerModule;
import xyz.geik.farmer.modules.autoharvest.configuration.ConfigFile;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestEvent;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestGuiCreateEvent;
import xyz.geik.farmer.modules.autoharvest.platform.PaperPlatform;
import xyz.geik.glib.GLib;
import xyz.geik.glib.chat.ChatUtils;
import xyz.geik.glib.shades.okaeri.configs.ConfigManager;
import xyz.geik.glib.shades.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AutoHarvest module main class
 *
 * @author poyraz
 * @author siberanka
 */
public class AutoHarvest extends FarmerModule {

    /**
     * Constructor of class
     */
    public AutoHarvest() {}

    private static volatile AutoHarvest instance;

    private AutoHarvestEvent autoHarvestEvent;

    private AutoHarvestGuiCreateEvent autoHarvestGuiCreateEvent;

    private volatile boolean requirePiston;
    private volatile boolean checkAllDirections;
    private volatile boolean withoutFarmer;
    private volatile boolean checkStock = true;

    private volatile String customPerm = "farmer.autoharvest";

    private volatile Set<XMaterial> crops = Collections.emptySet();

    private ConfigFile configFile;

    private final AtomicLong lifecycleGeneration = new AtomicLong();

    private volatile boolean active;

    /**
     * onEnable method of module
     */
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

        this.setLang(Main.getConfigFile().getSettings().getLang(), this.getClass());
        setupFile();
        if (configFile.isStatus()) {
            this.setHasGui(true);
            autoHarvestEvent = new AutoHarvestEvent();
            autoHarvestGuiCreateEvent = new AutoHarvestGuiCreateEvent();
            Bukkit.getPluginManager().registerEvents(autoHarvestEvent, Main.getInstance());
            Bukkit.getPluginManager().registerEvents(autoHarvestGuiCreateEvent, Main.getInstance());
            applyConfiguration();
            active = true;
            String messagex = "&3[" + GLib.getInstance().getName() + "] &a" + getName() + " enabled.";
            ChatUtils.sendMessage(Bukkit.getConsoleSender(), messagex);
            ChatUtils.sendMessage(Bukkit.getConsoleSender(), "&3[" + GLib.getInstance().getName()
                    + "] &7Platform: " + PaperPlatform.getPlatformName());
        }
        else {
            String messagex = "&3[" + GLib.getInstance().getName() + "] &c" + getName() + " is not loaded.";
            ChatUtils.sendMessage(Bukkit.getConsoleSender(), messagex);
        }
    }

    /**
     * onReload method of module
     */
    @Override
    public void onReload() {
        if (!this.isEnabled())
            return;
        lifecycleGeneration.incrementAndGet();
        applyConfiguration();
    }

    private void applyConfiguration() {
        crops = parseCrops(configFile.getItems());
        requirePiston = configFile.isRequirePiston();
        checkAllDirections = configFile.isCheckAllDirections();
        withoutFarmer = configFile.isWithoutFarmer();
        checkStock = configFile.isCheckStock();
        customPerm = configFile.getCustomPerm() == null || configFile.getCustomPerm().isBlank()
                ? "farmer.autoharvest"
                : configFile.getCustomPerm().trim();
        setDefaultState(configFile.isDefaultStatus());
    }

    private Set<XMaterial> parseCrops(List<String> configuredCrops) {
        EnumSet<XMaterial> parsed = EnumSet.noneOf(XMaterial.class);
        if (configuredCrops == null) {
            return Collections.emptySet();
        }

        for (String crop : configuredCrops) {
            if (crop == null || crop.isBlank()) {
                continue;
            }
            XMaterial.matchXMaterial(crop.trim()).ifPresentOrElse(material ->
                    parsed.add(normalizeConfiguredCrop(material)), () ->
                    Main.getInstance().getLogger().warning("Ignoring invalid AutoHarvest crop: " + crop));
        }
        return parsed.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(parsed);
    }

    private XMaterial normalizeConfiguredCrop(XMaterial material) {
        return switch (material.name()) {
            case "BEETROOTS" -> XMaterial.BEETROOT;
            case "POTATOES" -> XMaterial.POTATO;
            case "CARROTS" -> XMaterial.CARROT;
            case "SWEET_BERRY_BUSH" -> XMaterial.SWEET_BERRIES;
            case "MELON" -> XMaterial.MELON_SLICE;
            case "COCOA" -> XMaterial.COCOA_BEANS;
            default -> material;
        };
    }

    /**
     * onDisable method of module
     */
    @Override
    public void onDisable() {
        active = false;
        lifecycleGeneration.incrementAndGet();
        if (autoHarvestEvent != null) {
            HandlerList.unregisterAll(autoHarvestEvent);
            autoHarvestEvent = null;
        }
        if (autoHarvestGuiCreateEvent != null) {
            autoHarvestGuiCreateEvent.clear();
            HandlerList.unregisterAll(autoHarvestGuiCreateEvent);
            autoHarvestGuiCreateEvent = null;
        }
        crops = Collections.emptySet();
    }

    /**
     * Checks if auto harvest collect this crop.
     *
     * @param material of crop
     * @return is crop can harvestable
     */
    public static boolean checkCrop(XMaterial material) {
        AutoHarvest module = instance;
        return module != null && module.active && module.crops.contains(material);
    }

    public void setupFile() {
        configFile = ConfigManager.create(ConfigFile.class, (it) -> {
            it.withConfigurer(new YamlBukkitConfigurer());
            it.withBindFile(new File(Main.getInstance().getDataFolder(), String.format("/modules/%s/config.yml", getName().toLowerCase())));
            it.saveDefaults();
            it.load(true);
        });
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

    public long getLifecycleGeneration() {
        return lifecycleGeneration.get();
    }

    public boolean isActiveGeneration(long generation) {
        return active && lifecycleGeneration.get() == generation;
    }

}
