package xyz.geik.farmer.modules.autoharvest.optimization;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;

/** Thread-safe hysteresis gate backed by Paper's rolling average tick time. */
public final class AdaptiveBackpressure {

    private volatile BackpressureSettings settings = BackpressureSettings.BASELINE;
    private volatile boolean paused;
    private volatile int workScalePercent = 100;
    private volatile long nextCheckNanos;
    private final java.util.concurrent.atomic.AtomicLong regionPressureUntilNanos =
            new java.util.concurrent.atomic.AtomicLong();

    public void configure(@NotNull BackpressureSettings nextSettings) {
        settings = nextSettings;
        paused = false;
        workScalePercent = 100;
        nextCheckNanos = 0L;
        regionPressureUntilNanos.set(0L);
    }

    public boolean permitsWork() {
        return workScalePercent() > 0;
    }

    public int workScalePercent() {
        BackpressureSettings current = settings;
        if (!current.enabled()) {
            return 100;
        }
        long now = System.nanoTime();
        if (now < regionPressureUntilNanos.get()) {
            return 0;
        }
        if (now < nextCheckNanos) {
            return paused ? 0 : workScalePercent;
        }
        synchronized (this) {
            if (now < nextCheckNanos) {
                return paused ? 0 : workScalePercent;
            }
            nextCheckNanos = now + current.checkIntervalTicks() * 50_000_000L;
            double mspt;
            try {
                mspt = Bukkit.getAverageTickTime();
            }
            catch (RuntimeException ignored) {
                return paused ? 0 : workScalePercent;
            }
            if (paused) {
                paused = mspt > current.resumeBelowMspt();
            }
            else {
                paused = mspt >= current.pauseAboveMspt();
            }
            workScalePercent = paused ? 0 : scaleForMspt(current, mspt);
            return workScalePercent;
        }
    }

    public int scaleLimit(int configuredLimit) {
        return scaleLimit(configuredLimit, workScalePercent());
    }

    public static int scaleLimit(int configuredLimit, int percent) {
        if (configuredLimit <= 0 || percent <= 0) {
            return 0;
        }
        if (percent >= 100) {
            return configuredLimit;
        }
        return Math.max(1, (int) ((long) configuredLimit * percent / 100L));
    }

    static int scaleForMspt(@NotNull BackpressureSettings settings, double mspt) {
        if (!settings.enabled() || !Double.isFinite(mspt) || mspt <= settings.slowdownAboveMspt()) {
            return 100;
        }
        if (mspt >= settings.pauseAboveMspt()) {
            return 0;
        }
        double range = settings.pauseAboveMspt() - settings.slowdownAboveMspt();
        double remaining = settings.pauseAboveMspt() - mspt;
        double variable = (100.0 - settings.minimumWorkPercent()) * remaining / range;
        return Math.max(settings.minimumWorkPercent(),
                Math.min(100, (int) Math.ceil(settings.minimumWorkPercent() + variable)));
    }

    public boolean isPaused() {
        return paused || System.nanoTime() < regionPressureUntilNanos.get();
    }

    public void observeRegionTaskDelay(long scheduledAtNanos) {
        BackpressureSettings current = settings;
        if (!current.enabled()) {
            return;
        }
        long now = System.nanoTime();
        long delayMillis = Math.max(0L, now - scheduledAtNanos) / 1_000_000L;
        if (delayMillis < current.pauseAboveRegionTaskDelayMillis()) {
            return;
        }
        long pauseUntil = now + current.regionCooldownTicks() * 50_000_000L;
        regionPressureUntilNanos.accumulateAndGet(pauseUntil, Math::max);
    }
}
