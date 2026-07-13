package xyz.geik.farmer.modules.autoharvest.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVersionTest {

    @Test
    void comparesStableAndPrereleaseTags() {
        assertTrue(ReleaseVersion.isNewer("1.2.2", "v1.2.3"));
        assertTrue(ReleaseVersion.isNewer("1.2.3-beta.2", "v1.2.3"));
        assertTrue(ReleaseVersion.isNewer("1.2.3-beta.2", "1.2.3-beta.10"));
        assertFalse(ReleaseVersion.isNewer("1.2.3", "v1.2.3"));
        assertFalse(ReleaseVersion.isNewer("1.2.3", "v1.2.3-beta.1"));
        assertFalse(ReleaseVersion.isNewer("1.2.3", "v1.2.2"));
    }

    @Test
    void rejectsMalformedOrUnboundedVersions() {
        assertFalse(ReleaseVersion.isNewer("development", "v999999999999999999999.0.0"));
        assertTrue(ReleaseVersion.parse("1..2").isEmpty());
        assertTrue(ReleaseVersion.parse("1.2.3-").isEmpty());
        assertTrue(ReleaseVersion.parse("v" + "1".repeat(65)).isEmpty());
    }
}
