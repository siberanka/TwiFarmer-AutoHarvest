package xyz.geik.farmer.modules.autoharvest.configuration;

/** Gradual work scaling and hysteresis settings used while the server is overloaded. */
public record BackpressureSettings(
        boolean enabled,
        double slowdownAboveMspt,
        double pauseAboveMspt,
        double resumeBelowMspt,
        int minimumWorkPercent,
        int checkIntervalTicks,
        int pauseAboveRegionTaskDelayMillis,
        int regionCooldownTicks
) {
    public static final BackpressureSettings DEFAULT =
            new BackpressureSettings(true, 35.0, 45.0, 40.0, 10, 20, 100, 100);
    public static final BackpressureSettings BASELINE =
            new BackpressureSettings(true, 38.0, 47.0, 42.0, 10, 20, 150, 100);
}
