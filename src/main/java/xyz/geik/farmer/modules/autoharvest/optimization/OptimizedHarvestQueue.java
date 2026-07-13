package xyz.geik.farmer.modules.autoharvest.optimization;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.OptimizationSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.TelemetrySettings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Globally budgeted, fair per-chunk harvest queue. Queue overflow is deferred
 * to reconciliation and never converted into an unbounded scheduler task.
 */
public final class OptimizedHarvestQueue {

    private static final long OVERFLOW_LOG_INTERVAL_NANOS = 30_000_000_000L;

    private final ConcurrentMap<ChunkKey, ChunkQueue> queues = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockKey, Boolean> pendingBlocks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ReadyQueue> readyQueues = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingJobs = new AtomicInteger();
    private final AtomicInteger globalPermits = new AtomicInteger();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong dispatcherTicks = new AtomicLong();
    private final AtomicLong nextOverflowLogNanos = new AtomicLong();
    private final AtomicLong nextTelemetryNanos = new AtomicLong();
    private final LongAdder processedJobs = new LongAdder();
    private final LongAdder deferredJobs = new LongAdder();
    private final LongAdder coalescedJobs = new LongAdder();
    private final LongAdder schedulerFailures = new LongAdder();
    private final LongAdder pausedTicks = new LongAdder();
    private final AdaptiveBackpressure backpressure = new AdaptiveBackpressure();
    private final Object tickerLock = new Object();

    private volatile OptimizationSettings settings = OptimizationSettings.stopped();
    private volatile TelemetrySettings telemetry = TelemetrySettings.DISABLED;
    private volatile ScheduledTask ticker;
    private volatile Logger logger;

    public void configure(
            @NotNull OptimizationSettings nextSettings,
            @NotNull BackpressureSettings backpressureSettings,
            @NotNull TelemetrySettings telemetrySettings
    ) {
        settings = OptimizationSettings.stopped();
        revision.incrementAndGet();
        clear();
        backpressure.configure(backpressureSettings);
        telemetry = telemetrySettings;
        settings = nextSettings;
    }

    public @NotNull SubmitResult submit(
            @NotNull Plugin plugin,
            @NotNull Location location,
            @NotNull Runnable action,
            @NotNull Logger operationLogger
    ) {
        OptimizationSettings current = settings;
        if (!current.enabled()) {
            return SubmitResult.STOPPED;
        }
        logger = operationLogger;

        World world = location.getWorld();
        if (world == null) {
            deferredJobs.increment();
            return SubmitResult.DEFERRED;
        }

        BlockKey blockKey = new BlockKey(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (current.coalesceDuplicates() && pendingBlocks.putIfAbsent(blockKey, Boolean.TRUE) != null) {
            coalescedJobs.increment();
            return SubmitResult.COALESCED;
        }

        if (!reserveSlot(current.maxPendingJobs())) {
            pendingBlocks.remove(blockKey);
            deferredJobs.increment();
            logOverflow(operationLogger);
            return SubmitResult.DEFERRED;
        }

        ChunkKey chunkKey = new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        QueueJob job = new QueueJob(blockKey, action);
        ChunkQueue queue;
        boolean ready = false;
        while (true) {
            queue = queues.computeIfAbsent(chunkKey, ignored -> new ChunkQueue(location.clone()));
            synchronized (queue) {
                if (queues.get(chunkKey) != queue) {
                    continue;
                }
                queue.jobs.addLast(job);
                if (!queue.scheduled && !queue.ready) {
                    queue.ready = true;
                    ready = true;
                }
                break;
            }
        }

        if (ready) {
            readyQueues.offer(new ReadyQueue(chunkKey, queue, readyAtTick(current.initialDelayTicks())));
        }
        if (!ensureTicker(plugin, operationLogger)) {
            removeJob(chunkKey, queue, job);
            ChunkQueue failedQueue = queue;
            readyQueues.removeIf(entry -> entry.queue() == failedQueue);
            deferredJobs.increment();
            return SubmitResult.DEFERRED;
        }
        return SubmitResult.ENQUEUED;
    }

    public void clear() {
        synchronized (tickerLock) {
            ScheduledTask currentTicker = ticker;
            ticker = null;
            if (currentTicker != null) {
                currentTicker.cancel();
            }
        }
        for (var entry : queues.entrySet()) {
            discardQueue(entry.getKey(), entry.getValue());
        }
        queues.clear();
        readyQueues.clear();
        pendingBlocks.clear();
        pendingJobs.set(0);
        globalPermits.set(0);
        dispatcherTicks.set(0L);
    }

    public @NotNull QueueStats stats() {
        return new QueueStats(pendingJobs.get(), queues.size(), processedJobs.sum(), deferredJobs.sum(),
                coalescedJobs.sum(), schedulerFailures.sum(), pausedTicks.sum());
    }

    private boolean ensureTicker(Plugin plugin, Logger operationLogger) {
        if (ticker != null) {
            return true;
        }
        synchronized (tickerLock) {
            if (ticker != null) {
                return true;
            }
            long currentRevision = revision.get();
            try {
                ticker = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                        task -> tick(plugin, task, currentRevision), 1L, 1L);
                return true;
            }
            catch (RuntimeException exception) {
                schedulerFailures.increment();
                operationLogger.log(Level.WARNING, "AutoHarvest could not start its bounded harvest dispatcher.", exception);
                return false;
            }
        }
    }

    private void tick(Plugin plugin, ScheduledTask task, long expectedRevision) {
        OptimizationSettings current = settings;
        if (!current.enabled() || expectedRevision != revision.get()) {
            stopTicker(task);
            return;
        }

        long currentTick = dispatcherTicks.incrementAndGet();
        int workPercent = backpressure.workScalePercent();
        if (workPercent <= 0) {
            globalPermits.set(0);
            pausedTicks.increment();
            logTelemetry();
            return;
        }
        globalPermits.set(AdaptiveBackpressure.scaleLimit(current.globalMaxJobsPerTick(), workPercent));

        int submissionLimit = AdaptiveBackpressure.scaleLimit(
                current.maxSchedulerSubmissionsPerTick(), workPercent);
        int attempts = Math.min(queues.size(), submissionLimit * 4);
        int submitted = 0;
        while (attempts-- > 0 && submitted < submissionLimit) {
            ReadyQueue ready = readyQueues.poll();
            if (ready == null) {
                break;
            }
            ChunkQueue queue = ready.queue();
            synchronized (queue) {
                if (queues.get(ready.key()) != queue || !queue.ready || queue.jobs.isEmpty()) {
                    continue;
                }
                if (ready.readyAtTick() > currentTick) {
                    readyQueues.offer(ready);
                    continue;
                }
                queue.ready = false;
                queue.scheduled = true;
            }
            if (!scheduleRegionRun(plugin, ready.key(), queue, expectedRevision)) {
                discardQueue(ready.key(), queue);
            }
            submitted++;
        }
        logTelemetry();

        if (readyQueues.isEmpty()) {
            stopTicker(task);
        }
    }

    private boolean scheduleRegionRun(Plugin plugin, ChunkKey key, ChunkQueue queue, long expectedRevision) {
        try {
            long scheduledAt = System.nanoTime();
            Bukkit.getRegionScheduler().run(plugin, queue.anchor,
                    ignored -> drain(plugin, key, queue, expectedRevision, scheduledAt));
            return true;
        }
        catch (RuntimeException exception) {
            schedulerFailures.increment();
            Logger currentLogger = logger;
            if (currentLogger != null) {
                currentLogger.log(Level.WARNING, "AutoHarvest could not schedule a bounded region batch.", exception);
            }
            return false;
        }
    }

    private void drain(Plugin plugin, ChunkKey key, ChunkQueue queue, long expectedRevision, long scheduledAt) {
        backpressure.observeRegionTaskDelay(scheduledAt);
        OptimizationSettings current = settings;
        if (!current.enabled() || expectedRevision != revision.get()) {
            discardQueue(key, queue);
            return;
        }

        int runLimit = backpressure.scaleLimit(current.maxJobsPerRun());
        int processed = 0;
        while (processed < runLimit && acquireGlobalPermit()) {
            QueueJob job;
            synchronized (queue) {
                job = queue.jobs.pollFirst();
            }
            if (job == null) {
                globalPermits.incrementAndGet();
                break;
            }
            try {
                job.action.run();
            }
            catch (RuntimeException exception) {
                Logger currentLogger = logger;
                if (currentLogger != null) {
                    currentLogger.log(Level.WARNING, "AutoHarvest rejected a queued crop operation.", exception);
                }
            }
            finally {
                processed++;
                processedJobs.increment();
                releaseJob(job);
            }
        }

        boolean hasMore;
        synchronized (queue) {
            queue.scheduled = false;
            hasMore = !queue.jobs.isEmpty();
            if (hasMore) {
                queue.ready = true;
            }
            else {
                queues.remove(key, queue);
            }
        }
        if (hasMore) {
            readyQueues.offer(new ReadyQueue(key, queue, readyAtTick(current.continuationDelayTicks())));
            Logger currentLogger = logger;
            if (currentLogger != null) {
                ensureTicker(plugin, currentLogger);
            }
        }
    }

    private boolean acquireGlobalPermit() {
        while (true) {
            int current = globalPermits.get();
            if (current <= 0) {
                return false;
            }
            if (globalPermits.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    private void stopTicker(ScheduledTask task) {
        synchronized (tickerLock) {
            if (ticker == task) {
                ticker = null;
                task.cancel();
            }
        }
        if (!readyQueues.isEmpty()) {
            Logger currentLogger = logger;
            Plugin plugin = task.getOwningPlugin();
            if (currentLogger != null) {
                ensureTicker(plugin, currentLogger);
            }
        }
    }

    private void removeJob(ChunkKey key, ChunkQueue queue, QueueJob job) {
        synchronized (queue) {
            queue.jobs.remove(job);
            if (queue.jobs.isEmpty()) {
                queue.ready = false;
                queue.scheduled = false;
                queues.remove(key, queue);
            }
        }
        releaseJob(job);
    }

    private void discardQueue(ChunkKey key, ChunkQueue queue) {
        Deque<QueueJob> discarded;
        synchronized (queue) {
            discarded = new ArrayDeque<>(queue.jobs);
            queue.jobs.clear();
            queue.ready = false;
            queue.scheduled = false;
            queues.remove(key, queue);
        }
        discarded.forEach(this::releaseJob);
    }

    private boolean reserveSlot(int limit) {
        while (true) {
            int current = pendingJobs.get();
            if (current >= limit) {
                return false;
            }
            if (pendingJobs.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseJob(QueueJob job) {
        pendingBlocks.remove(job.blockKey());
        pendingJobs.updateAndGet(current -> Math.max(0, current - 1));
    }

    private long readyAtTick(int delayTicks) {
        return dispatcherTicks.get() + delayTicks;
    }

    private void logOverflow(Logger operationLogger) {
        long now = System.nanoTime();
        long next = nextOverflowLogNanos.get();
        if (now >= next && nextOverflowLogNanos.compareAndSet(next, now + OVERFLOW_LOG_INTERVAL_NANOS)) {
            operationLogger.warning("AutoHarvest harvest queue is full; crops are deferred to bounded reconciliation.");
        }
    }

    private void logTelemetry() {
        TelemetrySettings current = telemetry;
        Logger currentLogger = logger;
        if (!current.enabled() || currentLogger == null) {
            return;
        }
        long now = System.nanoTime();
        long interval = current.logIntervalSeconds() * 1_000_000_000L;
        long next = nextTelemetryNanos.get();
        if (now >= next && nextTelemetryNanos.compareAndSet(next, now + interval)) {
            QueueStats stats = stats();
            if (stats.pendingJobs() > 0 || stats.deferredJobs() > 0 || stats.schedulerFailures() > 0) {
                currentLogger.info("AutoHarvest queue: pending=" + stats.pendingJobs()
                        + ", chunks=" + stats.pendingChunks() + ", processed=" + stats.processedJobs()
                        + ", deferred=" + stats.deferredJobs() + ", coalesced=" + stats.coalescedJobs()
                        + ", scheduler-failures=" + stats.schedulerFailures()
                        + ", work-percent=" + backpressure.workScalePercent()
                        + ", backpressure-paused=" + backpressure.isPaused());
            }
        }
    }

    public enum SubmitResult {
        STOPPED,
        ENQUEUED,
        COALESCED,
        DEFERRED
    }

    public record QueueStats(
            int pendingJobs,
            int pendingChunks,
            long processedJobs,
            long deferredJobs,
            long coalescedJobs,
            long schedulerFailures,
            long pausedTicks
    ) {
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record BlockKey(UUID worldId, int blockX, int blockY, int blockZ) {
    }

    private record QueueJob(BlockKey blockKey, Runnable action) {
    }

    private record ReadyQueue(ChunkKey key, ChunkQueue queue, long readyAtTick) {
    }

    private static final class ChunkQueue {
        private final Location anchor;
        private final Deque<QueueJob> jobs = new ArrayDeque<>();
        private boolean scheduled;
        private boolean ready;

        private ChunkQueue(Location anchor) {
            this.anchor = anchor;
        }
    }
}
