package xyz.geik.farmer.modules.autoharvest.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FarmerRegionAccessTest {

    @Test
    void doesNotClassifyOtherIntegrationsAsSuperiorSkyblock() {
        assertTrue(FarmerRegionAccess.isSuperiorIntegrationName(
                "xyz.geik.farmer.integrations.superior.SuperiorSkyblock"));
        assertFalse(FarmerRegionAccess.isSuperiorIntegration(mock(Object.class)));
        assertFalse(FarmerRegionAccess.isSuperiorIntegration(null));
    }
}
