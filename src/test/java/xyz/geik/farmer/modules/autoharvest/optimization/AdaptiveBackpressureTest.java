package xyz.geik.farmer.modules.autoharvest.optimization;

import org.junit.jupiter.api.Test;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveBackpressureTest {

    @Test
    void delayedRegionCallbackClosesTheGateForItsCooldown() {
        AdaptiveBackpressure backpressure = new AdaptiveBackpressure();
        backpressure.configure(new BackpressureSettings(true, 45.0, 40.0, 20, 100, 100));

        backpressure.observeRegionTaskDelay(System.nanoTime() - 200_000_000L);

        assertTrue(backpressure.isPaused());
        assertFalse(backpressure.permitsWork());
    }
}
