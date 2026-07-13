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
        TrackingMode mode,
        boolean growthEvents,
        boolean fertilizeEvents,
        boolean cropPlaceEvents,
        boolean scanOnChunkLoad,
        boolean scanOnFarmerPurchase,
        boolean scanOnPlayerJoin,
        boolean scanOnPlayerChunkLoad,
        boolean farmerRegionsOnly,
        int reconcileIntervalTicks,
        int maxChunksPerCycle,
        int maxTrackedChunks,
        int maxConcurrentScans,
        int maxSnapshotCapturesPerTick,
        int maxScanStartsPerSecond,
        int maxSectionsPerSecond,
        int maxBlockChecksPerSlice,
        int maxPendingScans,
        int maxCandidatesPerScan,
        int maxCandidateAdmissionsPerTick,
        int purchaseRadiusChunks,
        int bootstrapRadiusChunks
) {

    public static final TrackingSettings BASELINE = new TrackingSettings(
            TrackingMode.EVENT_DRIVEN,
            true, true, true, false, true, true, true, true,
            200, 1, 2_048, 1, 1, 2, 16, 4_096,
            512, 512, 16, 8, 2
    );

    public static TrackingSettings baseline() {
        return BASELINE;
    }

    public boolean listensToGrowth() {
        return mode.usesEvents() && growthEvents;
    }

    public boolean listensToFertilize() {
        return mode.usesEvents() && fertilizeEvents;
    }

    public boolean reconcilesLoadedChunks() {
        return mode.scansLoadedChunks();
    }
}
