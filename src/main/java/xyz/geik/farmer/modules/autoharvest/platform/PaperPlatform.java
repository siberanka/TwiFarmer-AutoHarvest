package xyz.geik.farmer.modules.autoharvest.platform;

import org.bukkit.Bukkit;

/**
 * Detects the Paper scheduler API required by this module.
 *
 * @author siberanka
 * @since 1.1.0
 */
public final class PaperPlatform {

    private static final String REGION_SCHEDULER_CLASS =
            "io.papermc.paper.threadedregions.scheduler.RegionScheduler";

    private PaperPlatform() {
    }

    public static boolean isSupported() {
        try {
            Class.forName(REGION_SCHEDULER_CLASS, false, Bukkit.class.getClassLoader());
            Bukkit.class.getMethod("getRegionScheduler");
            return true;
        }
        catch (ClassNotFoundException | NoSuchMethodException ignored) {
            return false;
        }
    }

    public static String getPlatformName() {
        return Bukkit.getName() + " " + Bukkit.getVersion();
    }
}
