package xyz.geik.farmer.modules.autoharvest.configuration;

/** Bounded GitHub release-check settings. */
public record UpdateSettings(
        boolean enabled,
        int checkIntervalHours,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds
) {
    public static final UpdateSettings DEFAULT = new UpdateSettings(true, 6, 5, 8);
}
