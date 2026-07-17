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
        var integration = Main.getIntegration();
        try {
            String regionId = integration.getRegionID(location);
            return regionId == null || regionId.isBlank() ? null : regionId;
        }
        catch (NullPointerException exception) {
            // Farmer v6-b117 dereferences SuperiorSkyblock's null island result.
            // HotSpot may omit both message and stack trace after repeated throws,
            // so the active integration class is the only durable discriminator.
            if (isSuperiorIntegration(integration)) {
                return null;
            }
            throw exception;
        }
    }

    public static boolean isSuperiorIntegrationActive() {
        return isSuperiorIntegration(Main.getIntegration());
    }

    static boolean isSuperiorIntegration(Object integration) {
        return integration != null && isSuperiorIntegrationName(integration.getClass().getName());
    }

    static boolean isSuperiorIntegrationName(String className) {
        return SUPERIOR_INTEGRATION.equals(className);
    }
}
