package xyz.geik.farmer.modules.autoharvest.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPlatformTest {

    @Test
    void paperRegionSchedulerApiIsOnTheCompileClasspath() {
        assertTrue(PaperPlatform.isSupported());
    }
}
