package xyz.geik.farmer.modules.autoharvest.configuration;

/** Low-frequency operational metrics for production diagnosis. */
public record TelemetrySettings(boolean enabled, int logIntervalSeconds) {
    public static final TelemetrySettings DEFAULT = new TelemetrySettings(true, 300);
    public static final TelemetrySettings DISABLED = new TelemetrySettings(false, 300);
}
