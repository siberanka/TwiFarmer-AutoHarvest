package xyz.geik.farmer.modules.autoharvest.configuration;

/**
 * Bounded crop discovery settings. The baseline remains active so harvesting
 * is self-healing even when the optional optimization module is disabled.
 * Configured values only replace it while optimize-module is enabled.
 *
 * @author siberanka
 * @since 1.2.1
 */
public record TrackingSettings(
        int reconcileIntervalTicks,
        int maxChunksPerCycle,
        int maxTrackedChunks,
        int maxConcurrentScans,
        int maxPendingScans,
        int maxCandidatesPerScan,
        int purchaseRadiusChunks,
        int bootstrapRadiusChunks
) {

    public static final TrackingSettings BASELINE = new TrackingSettings(
            200, 1, 2_048, 1, 512, 256, 8, 2
    );

    public static TrackingSettings baseline() {
        return BASELINE;
    }
}
