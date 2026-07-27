package xyz.geik.farmer.modules.autoharvest.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoHarvestEventTest {

    @Test
    void availablePrimaryCropIsNotBlockedBySecondaryDropCapacity() {
        assertTrue(AutoHarvestEvent.primaryStockPermitsHarvest(true, false, true, true));
    }

    @Test
    void fullConfiguredPrimaryCropStillStopsHarvest() {
        assertFalse(AutoHarvestEvent.primaryStockPermitsHarvest(true, false, true, false));
    }

    @Test
    void disabledStockCheckAndAutoSellerPreserveTheirBypass() {
        assertTrue(AutoHarvestEvent.primaryStockPermitsHarvest(false, false, true, false));
        assertTrue(AutoHarvestEvent.primaryStockPermitsHarvest(true, true, true, false));
    }

    @Test
    void unmanagedPrimaryCropRemainsANaturalWorldDrop() {
        assertTrue(AutoHarvestEvent.primaryStockPermitsHarvest(true, false, false, false));
    }
}
