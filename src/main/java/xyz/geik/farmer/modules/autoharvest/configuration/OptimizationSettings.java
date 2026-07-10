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
        int maxPendingJobs,
        boolean coalesceDuplicates
) {

    public static final OptimizationSettings DEFAULT = new OptimizationSettings(
            false, 2, 1, 32, 4096, true
    );

    public static OptimizationSettings disabled() {
        return new OptimizationSettings(
                false,
                DEFAULT.initialDelayTicks,
                DEFAULT.continuationDelayTicks,
                DEFAULT.maxJobsPerRun,
                DEFAULT.maxPendingJobs,
                DEFAULT.coalesceDuplicates
        );
    }
}
