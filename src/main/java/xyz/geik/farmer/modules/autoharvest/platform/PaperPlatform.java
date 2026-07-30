package xyz.geik.farmer.modules.autoharvest.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * Detects the Paper scheduler API required by this module.
 *
 * @author siberanka
 * @since 1.1.0
 */
public final class PaperPlatform {

    private static final String[] REQUIRED_CLASSES = {
            "io.papermc.paper.threadedregions.scheduler.RegionScheduler",
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler",
            "io.papermc.paper.threadedregions.scheduler.AsyncScheduler",
            "io.papermc.paper.threadedregions.scheduler.EntityScheduler",
            "io.papermc.paper.event.packet.PlayerChunkLoadEvent"
    };

    private PaperPlatform() {
    }

    public static boolean isSupported() {
        try {
            ClassLoader loader = PaperPlatform.class.getClassLoader();
            Class<?> farmerCompatibility = Class.forName(
                    "xyz.geik.farmer.api.FarmerCompatibilityAPI", false, loader);
            farmerCompatibility.getMethod("requireModuleApi", int.class).invoke(null, 3);
            for (String className : REQUIRED_CLASSES) {
                Class.forName(className, false, loader);
            }
            Bukkit.class.getMethod("getRegionScheduler");
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            Bukkit.class.getMethod("getAsyncScheduler");
            Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
            Entity.class.getMethod("getScheduler");
            return true;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static String getPlatformName() {
        return Bukkit.getName() + " " + Bukkit.getVersion();
    }
}
