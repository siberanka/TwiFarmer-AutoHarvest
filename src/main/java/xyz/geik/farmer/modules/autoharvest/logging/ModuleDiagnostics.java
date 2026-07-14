package xyz.geik.farmer.modules.autoharvest.logging;

import org.jetbrains.annotations.NotNull;

/** Thread-safe operational logging boundary used by AutoHarvest hot paths. */
public interface ModuleDiagnostics {

    ModuleDiagnostics NOOP = new ModuleDiagnostics() {
        @Override
        public void debug(@NotNull String message) {
        }

        @Override
        public void error(@NotNull String message, Throwable exception) {
        }
    };

    void debug(@NotNull String message);

    void error(@NotNull String message, Throwable exception);
}
