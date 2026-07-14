package xyz.geik.farmer.modules.autoharvest.configuration;

/** Console diagnostics and bounded error-file settings. */
public record LoggingSettings(
        boolean debugEnabled,
        int debugIntervalSeconds,
        int errorMaxSizeMegabytes,
        int errorHistoryFiles
) {
    public static final LoggingSettings DEFAULT = new LoggingSettings(false, 300, 5, 2);

    public TelemetrySettings telemetry() {
        return debugEnabled
                ? new TelemetrySettings(true, debugIntervalSeconds)
                : TelemetrySettings.DISABLED;
    }

    public long errorMaxSizeBytes() {
        return errorMaxSizeMegabytes * 1_048_576L;
    }
}
