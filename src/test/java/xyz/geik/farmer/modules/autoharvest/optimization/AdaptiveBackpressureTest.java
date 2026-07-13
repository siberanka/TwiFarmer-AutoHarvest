package xyz.geik.farmer.modules.autoharvest.optimization;

import org.junit.jupiter.api.Test;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveBackpressureTest {

    @Test
    void delayedRegionCallbackClosesTheGateForItsCooldown() {
        AdaptiveBackpressure backpressure = new AdaptiveBackpressure();
        backpressure.configure(new BackpressureSettings(true, 35.0, 45.0, 40.0,
                10, 20, 100, 100));

        backpressure.observeRegionTaskDelay(System.nanoTime() - 200_000_000L);

        assertTrue(backpressure.isPaused());
        assertFalse(backpressure.permitsWork());
    }

    @Test
    void graduallyScalesBudgetsBeforeThePauseThreshold() {
        BackpressureSettings settings = new BackpressureSettings(true, 30.0, 50.0, 45.0,
                20, 20, 100, 100);

        assertEquals(100, AdaptiveBackpressure.scaleForMspt(settings, 30.0));
        assertEquals(60, AdaptiveBackpressure.scaleForMspt(settings, 40.0));
        assertEquals(24, AdaptiveBackpressure.scaleForMspt(settings, 49.0));
        assertEquals(0, AdaptiveBackpressure.scaleForMspt(settings, 50.0));
        assertEquals(6, AdaptiveBackpressure.scaleLimit(10, 60));
        assertEquals(1, AdaptiveBackpressure.scaleLimit(1, 10));
        assertEquals(0, AdaptiveBackpressure.scaleLimit(10, 0));
    }
}
