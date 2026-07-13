package xyz.geik.farmer.modules.autoharvest.configuration;

/**
 * Immutable production queue settings parsed from config.yml.
 *
 * @author siberanka
 * @since 1.2.0
 */
public record OptimizationSettings(
        boolean enabled,
        int initialDelayTicks,
        int continuationDelayTicks,
        int maxJobsPerRun,
        int globalMaxJobsPerTick,
        int maxSchedulerSubmissionsPerTick,
        int maxPendingJobs,
        boolean coalesceDuplicates,
        HarvestPacingScope harvestScope,
        boolean perHarvestDelayEnabled,
        int perHarvestDelayTicks,
        boolean batchPauseEnabled,
        int harvestsBeforePause,
        int batchPauseTicks
) {

    public static final OptimizationSettings DEFAULT = new OptimizationSettings(
            false, 2, 1, 8, 32, 8, 8192, true,
            HarvestPacingScope.FARMER, false, 2, false, 64, 20
    );

    private static final OptimizationSettings BASELINE = new OptimizationSettings(
            true, 2, 1, 4, 16, 4, 2048, true,
            HarvestPacingScope.FARMER, false, 3, false, 64, 20
    );

    public boolean usesScopedThrottling() {
        return perHarvestDelayEnabled || batchPauseEnabled;
    }

    public static OptimizationSettings baseline() {
        return BASELINE;
    }

    public static OptimizationSettings stopped() {
        return new OptimizationSettings(
                false,
                DEFAULT.initialDelayTicks,
                DEFAULT.continuationDelayTicks,
                DEFAULT.maxJobsPerRun,
                DEFAULT.globalMaxJobsPerTick,
                DEFAULT.maxSchedulerSubmissionsPerTick,
                DEFAULT.maxPendingJobs,
                DEFAULT.coalesceDuplicates,
                DEFAULT.harvestScope,
                DEFAULT.perHarvestDelayEnabled,
                DEFAULT.perHarvestDelayTicks,
                DEFAULT.batchPauseEnabled,
                DEFAULT.harvestsBeforePause,
                DEFAULT.batchPauseTicks
        );
    }
}
