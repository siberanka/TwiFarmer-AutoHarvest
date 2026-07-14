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
import xyz.geik.farmer.modules.autoharvest.logging.ModuleDiagnostics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Globally budgeted, fair per-chunk and per-Farmer harvest queue. Queue
 * overflow is deferred to reconciliation and never converted into an
 * unbounded scheduler task.
 */
public final class OptimizedHarvestQueue {

    private static final long OVERFLOW_LOG_INTERVAL_NANOS = 30_000_000_000L;

    private final ConcurrentMap<ChunkKey, ChunkQueue> queues = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockKey, Boolean> pendingBlocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScopeState> scopes = new ConcurrentHashMap<>();
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
    private volatile ModuleDiagnostics diagnostics = ModuleDiagnostics.NOOP;
    private volatile ChunkDrainListener chunkDrainListener;

    public void configure(
            @NotNull OptimizationSettings nextSettings,
            @NotNull BackpressureSettings backpressureSettings,
            @NotNull TelemetrySettings telemetrySettings,
            @NotNull ModuleDiagnostics moduleDiagnostics
    ) {
        settings = OptimizationSettings.stopped();
        revision.incrementAndGet();
        clear();
        backpressure.configure(backpressureSettings);
        telemetry = telemetrySettings;
        diagnostics = moduleDiagnostics;
        settings = nextSettings;
    }

    /** Retained for module-side source compatibility. */
    public void configure(
            @NotNull OptimizationSettings nextSettings,
            @NotNull BackpressureSettings backpressureSettings,
            @NotNull TelemetrySettings telemetrySettings
    ) {
        configure(nextSettings, backpressureSettings, telemetrySettings, ModuleDiagnostics.NOOP);
    }

    public @NotNull SubmitResult submit(
            @NotNull Plugin plugin,
            @NotNull Location location,
            @NotNull Runnable action
    ) {
        if (!settings.enabled()) {
            return SubmitResult.STOPPED;
        }
        World world = location.getWorld();
        if (world == null) {
            deferredJobs.increment();
            return SubmitResult.DEFERRED;
        }
        String chunkScope = "chunk:" + world.getUID() + ':'
                + (location.getBlockX() >> 4) + ':' + (location.getBlockZ() >> 4);
        return submit(plugin, location, chunkScope, action);
    }

    /** Retained for module-side source compatibility. */
    public @NotNull SubmitResult submit(
            @NotNull Plugin plugin,
            @NotNull Location location,
            @NotNull Runnable action,
            @SuppressWarnings("unused") @NotNull Logger operationLogger
    ) {
        return submit(plugin, location, action);
    }

    public @NotNull SubmitResult submit(
            @NotNull Plugin plugin,
            @NotNull Location location,
            @NotNull String scopeKey,
            @NotNull Runnable action
    ) {
        OptimizationSettings current = settings;
        if (!current.enabled()) {
            return SubmitResult.STOPPED;
        }
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
            logOverflow();
            return SubmitResult.DEFERRED;
        }

        ScopeState scope = retainScope(scopeKey);
        ChunkKey chunkKey = new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        QueueJob job = new QueueJob(blockKey, scopeKey, scope, action);
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
        if (!ensureTicker(plugin)) {
            removeJob(chunkKey, queue, job);
            ChunkQueue failedQueue = queue;
            readyQueues.removeIf(entry -> entry.queue() == failedQueue);
            deferredJobs.increment();
            return SubmitResult.DEFERRED;
        }
        return SubmitResult.ENQUEUED;
    }

    /** Retained for module-side source compatibility. */
    public @NotNull SubmitResult submit(
            @NotNull Plugin plugin,
            @NotNull Location location,
            @NotNull String scopeKey,
            @NotNull Runnable action,
            @SuppressWarnings("unused") @NotNull Logger operationLogger
    ) {
        return submit(plugin, location, scopeKey, action);
    }

    public void setChunkDrainListener(ChunkDrainListener listener) {
        chunkDrainListener = listener;
    }

    public boolean hasPendingJobs(@NotNull UUID worldId, int chunkX, int chunkZ) {
        return queues.containsKey(new ChunkKey(worldId, chunkX, chunkZ));
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
        scopes.clear();
        pendingJobs.set(0);
        globalPermits.set(0);
        dispatcherTicks.set(0L);
    }

    public @NotNull QueueStats stats() {
        return new QueueStats(pendingJobs.get(), queues.size(), scopes.size(), processedJobs.sum(), deferredJobs.sum(),
                coalescedJobs.sum(), schedulerFailures.sum(), pausedTicks.sum());
    }

    private boolean ensureTicker(Plugin plugin) {
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
                diagnostics.error("AutoHarvest could not start its bounded harvest dispatcher.", exception);
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
                QueueJob nextJob = queue.jobs.peekFirst();
                long scopeReadyAt = scopeReadyAtTick(nextJob, current);
                if (scopeReadyAt > currentTick) {
                    readyQueues.offer(new ReadyQueue(ready.key(), queue, scopeReadyAt));
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
            diagnostics.error("AutoHarvest could not schedule a bounded region batch.", exception);
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
                job = queue.jobs.peekFirst();
            }
            if (job == null) {
                globalPermits.incrementAndGet();
                break;
            }
            long currentTick = dispatcherTicks.get();
            if (!acquireScopePermit(job, current, currentTick)) {
                globalPermits.incrementAndGet();
                break;
            }
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
                diagnostics.error("AutoHarvest rejected a queued crop operation.", exception);
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
            QueueJob nextJob;
            synchronized (queue) {
                nextJob = queue.jobs.peekFirst();
            }
            long readyAt = Math.max(readyAtTick(current.continuationDelayTicks()),
                    scopeReadyAtTick(nextJob, current));
            readyQueues.offer(new ReadyQueue(key, queue, readyAt));
            ensureTicker(plugin);
        }
        else {
            notifyChunkDrained(key);
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
            Plugin plugin = task.getOwningPlugin();
            ensureTicker(plugin);
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
        scopes.computeIfPresent(job.scopeKey(), (key, current) -> {
            if (current != job.scope()) {
                return current;
            }
            current.pendingJobs--;
            return current.pendingJobs <= 0 ? null : current;
        });
        pendingJobs.updateAndGet(current -> Math.max(0, current - 1));
    }

    private ScopeState retainScope(String scopeKey) {
        return scopes.compute(scopeKey, (key, current) -> {
            ScopeState state = current == null ? new ScopeState() : current;
            state.pendingJobs++;
            return state;
        });
    }

    private boolean acquireScopePermit(QueueJob job, OptimizationSettings current, long currentTick) {
        if (!current.usesScopedThrottling()) {
            return true;
        }
        ScopeState scope = job.scope();
        synchronized (scope) {
            if (current.perHarvestDelayEnabled() && scope.nextAllowedTick > currentTick) {
                return false;
            }
            if (current.batchPauseEnabled() && scope.pauseUntilTick > currentTick) {
                return false;
            }

            if (current.perHarvestDelayEnabled()) {
                scope.nextAllowedTick = currentTick + current.perHarvestDelayTicks();
            }
            if (current.batchPauseEnabled()
                    && ++scope.harvestsSincePause >= current.harvestsBeforePause()) {
                scope.harvestsSincePause = 0;
                scope.pauseUntilTick = currentTick + current.batchPauseTicks();
            }
            return true;
        }
    }

    private long scopeReadyAtTick(QueueJob job, OptimizationSettings current) {
        if (job == null || !current.usesScopedThrottling()) {
            return 0L;
        }
        ScopeState scope = job.scope();
        synchronized (scope) {
            long readyAt = 0L;
            if (current.perHarvestDelayEnabled()) {
                readyAt = scope.nextAllowedTick;
            }
            if (current.batchPauseEnabled()) {
                readyAt = Math.max(readyAt, scope.pauseUntilTick);
            }
            return readyAt;
        }
    }

    private void notifyChunkDrained(ChunkKey key) {
        ChunkDrainListener listener = chunkDrainListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onChunkDrained(key.worldId(), key.chunkX(), key.chunkZ());
        }
        catch (RuntimeException exception) {
            diagnostics.error("AutoHarvest rejected a chunk-drain callback.", exception);
        }
    }

    private long readyAtTick(int delayTicks) {
        return dispatcherTicks.get() + delayTicks;
    }

    private void logOverflow() {
        long now = System.nanoTime();
        long next = nextOverflowLogNanos.get();
        if (now >= next && nextOverflowLogNanos.compareAndSet(next, now + OVERFLOW_LOG_INTERVAL_NANOS)) {
            diagnostics.debug("AutoHarvest harvest queue is full; crops are deferred to bounded reconciliation.");
        }
    }

    private void logTelemetry() {
        TelemetrySettings current = telemetry;
        if (!current.enabled()) {
            return;
        }
        long now = System.nanoTime();
        long interval = current.logIntervalSeconds() * 1_000_000_000L;
        long next = nextTelemetryNanos.get();
        if (now >= next && nextTelemetryNanos.compareAndSet(next, now + interval)) {
            QueueStats stats = stats();
            if (stats.pendingJobs() > 0 || stats.deferredJobs() > 0 || stats.schedulerFailures() > 0) {
                diagnostics.debug("AutoHarvest queue: pending=" + stats.pendingJobs()
                        + ", chunks=" + stats.pendingChunks() + ", scopes=" + stats.pendingScopes()
                        + ", processed=" + stats.processedJobs()
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
            int pendingScopes,
            long processedJobs,
            long deferredJobs,
            long coalescedJobs,
            long schedulerFailures,
            long pausedTicks
    ) {
    }

    @FunctionalInterface
    public interface ChunkDrainListener {
        void onChunkDrained(@NotNull UUID worldId, int chunkX, int chunkZ);
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record BlockKey(UUID worldId, int blockX, int blockY, int blockZ) {
    }

    private record QueueJob(BlockKey blockKey, String scopeKey, ScopeState scope, Runnable action) {
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

    private static final class ScopeState {
        private long nextAllowedTick;
        private long pauseUntilTick;
        private int harvestsSincePause;
        private int pendingJobs;
    }
}
