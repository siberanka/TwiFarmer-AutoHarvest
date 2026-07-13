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
        boolean perScopePacingEnabled,
        HarvestPacingScope pacingScope,
        int perScopeDelayTicks
) {

    public static final OptimizationSettings DEFAULT = new OptimizationSettings(
            false, 2, 1, 8, 32, 8, 8192, true, true, HarvestPacingScope.FARMER, 2
    );

    private static final OptimizationSettings BASELINE = new OptimizationSettings(
            true, 2, 1, 4, 16, 4, 2048, true, true, HarvestPacingScope.FARMER, 3
    );

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
                DEFAULT.perScopePacingEnabled,
                DEFAULT.pacingScope,
                DEFAULT.perScopeDelayTicks
        );
    }
}
