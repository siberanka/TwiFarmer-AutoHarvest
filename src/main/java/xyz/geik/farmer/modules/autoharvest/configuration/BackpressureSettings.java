package xyz.geik.farmer.modules.autoharvest.configuration;

/** Hysteresis settings used to pause new work while the server is overloaded. */
public record BackpressureSettings(
        boolean enabled,
        double pauseAboveMspt,
        double resumeBelowMspt,
        int checkIntervalTicks,
        int pauseAboveRegionTaskDelayMillis,
        int regionCooldownTicks
) {
    public static final BackpressureSettings DEFAULT = new BackpressureSettings(true, 45.0, 40.0, 20, 100, 100);
    public static final BackpressureSettings BASELINE = new BackpressureSettings(true, 47.0, 42.0, 20, 150, 100);
}
