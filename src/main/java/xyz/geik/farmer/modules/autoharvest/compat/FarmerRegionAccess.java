package xyz.geik.farmer.modules.autoharvest.compat;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;

/** Defensive adapter for host integrations that throw when no region exists. */
public final class FarmerRegionAccess {

    private static final String SUPERIOR_INTEGRATION =
            "xyz.geik.farmer.integrations.superior.SuperiorSkyblock";

    private FarmerRegionAccess() {
    }

    public static String resolveRegionId(@NotNull Location location) {
        try {
            String regionId = Main.getIntegration().getRegionID(location);
            return regionId == null || regionId.isBlank() ? null : regionId;
        }
        catch (NullPointerException exception) {
            // Farmer v6-b117 dereferences SuperiorSkyblock's null island result.
            // No island at this location is a normal negative lookup, not a fault.
            if (isMissingSuperiorIsland(exception)) {
                return null;
            }
            throw exception;
        }
    }

    static boolean isMissingSuperiorIsland(Throwable exception) {
        String message = exception.getMessage();
        boolean missingIslandMessage = message == null
                || (message.contains("SuperiorSkyblockAPI.getIslandAt") && message.contains("getUniqueId()"));
        if (!missingIslandMessage) {
            return false;
        }
        for (StackTraceElement frame : exception.getStackTrace()) {
            if (SUPERIOR_INTEGRATION.equals(frame.getClassName())
                    && "getRegionID".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
