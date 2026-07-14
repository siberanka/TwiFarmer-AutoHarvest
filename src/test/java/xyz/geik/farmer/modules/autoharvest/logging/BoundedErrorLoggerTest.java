package xyz.geik.farmer.modules.autoharvest.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BoundedErrorLoggerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAndRotatesErrorsWithinConfiguredBounds() throws Exception {
        Path errorFile = temporaryDirectory.resolve("error.log");
        BoundedErrorLogger logger = new BoundedErrorLogger(
                errorFile, Logger.getLogger("BoundedErrorLoggerTest"), false, 1_024L, 2);

        for (int index = 0; index < 8; index++) {
            logger.error("failure-" + index + "-" + "x".repeat(600),
                    new IllegalStateException("test-" + index));
        }
        logger.close();

        assertTrue(Files.exists(errorFile));
        assertTrue(Files.size(errorFile) <= 1_024L);
        assertTrue(Files.exists(temporaryDirectory.resolve("error.log.1")));
        assertTrue(Files.size(temporaryDirectory.resolve("error.log.1")) <= 1_024L);
        assertTrue(Files.exists(temporaryDirectory.resolve("error.log.2")));
        assertFalse(Files.exists(temporaryDirectory.resolve("error.log.3")));
        assertTrue(Files.readString(errorFile).contains("IllegalStateException"));
    }

    @Test
    void consoleDiagnosticsRequireDebugToBeEnabled() {
        Logger console = mock(Logger.class);
        BoundedErrorLogger logger = new BoundedErrorLogger(
                temporaryDirectory.resolve("error.log"), console, false, 1_024L, 0);

        logger.debug("hidden");
        logger.configure(true, 1_024L, 0);
        logger.debug("visible");
        logger.close();

        verify(console, never()).info("hidden");
        verify(console).info("visible");
    }
}
