package xyz.geik.farmer.modules.autoharvest.optimization;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;

/** Thread-safe hysteresis gate backed by Paper's rolling average tick time. */
public final class AdaptiveBackpressure {

    private volatile BackpressureSettings settings = BackpressureSettings.BASELINE;
    private volatile boolean paused;
    private volatile long nextCheckNanos;
    private final java.util.concurrent.atomic.AtomicLong regionPressureUntilNanos =
            new java.util.concurrent.atomic.AtomicLong();

    public void configure(@NotNull BackpressureSettings nextSettings) {
        settings = nextSettings;
        paused = false;
        nextCheckNanos = 0L;
        regionPressureUntilNanos.set(0L);
    }

    public boolean permitsWork() {
        BackpressureSettings current = settings;
        if (!current.enabled()) {
            return true;
        }
        long now = System.nanoTime();
        if (now < regionPressureUntilNanos.get()) {
            return false;
        }
        if (now < nextCheckNanos) {
            return !paused;
        }
        synchronized (this) {
            if (now < nextCheckNanos) {
                return !paused;
            }
            nextCheckNanos = now + current.checkIntervalTicks() * 50_000_000L;
            double mspt;
            try {
                mspt = Bukkit.getAverageTickTime();
            }
            catch (RuntimeException ignored) {
                return !paused;
            }
            if (paused) {
                paused = mspt > current.resumeBelowMspt();
            }
            else {
                paused = mspt >= current.pauseAboveMspt();
            }
            return !paused;
        }
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
