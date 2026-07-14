package xyz.geik.farmer.modules.autoharvest.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmerRegionAccessTest {

    @Test
    void recognizesTheKnownSuperiorSkyblockMissingIslandFailure() {
        NullPointerException exception = new NullPointerException(
                "Cannot invoke Island.getUniqueId() because SuperiorSkyblockAPI.getIslandAt(Location) is null");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("xyz.geik.farmer.integrations.superior.SuperiorSkyblock",
                        "getRegionID", "SuperiorSkyblock.java", 45)
        });

        assertTrue(FarmerRegionAccess.isMissingSuperiorIsland(exception));
    }

    @Test
    void doesNotHideUnrelatedNullPointerFailures() {
        NullPointerException exception = new NullPointerException("unexpected");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("example.OtherIntegration", "getRegionID", "Other.java", 10)
        });

        assertFalse(FarmerRegionAccess.isMissingSuperiorIsland(exception));
    }

    @Test
    void doesNotHideAnotherFailureInsideTheSuperiorIntegration() {
        NullPointerException exception = new NullPointerException("unexpected cache failure");
        exception.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("xyz.geik.farmer.integrations.superior.SuperiorSkyblock",
                        "getRegionID", "SuperiorSkyblock.java", 45)
        });

        assertFalse(FarmerRegionAccess.isMissingSuperiorIsland(exception));
    }
}
