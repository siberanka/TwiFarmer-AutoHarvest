package xyz.geik.farmer.modules.autoharvest.optimization;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.configuration.OptimizationSettings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Batches harvest work per chunk without touching Bukkit world state away from
 * the owning Paper/Folia region scheduler.
 *
 * @author siberanka
 * @since 1.2.0
 */
public final class OptimizedHarvestQueue {

    private static final long OVERFLOW_LOG_INTERVAL_NANOS = 5_000_000_000L;

    private final ConcurrentMap<ChunkKey, ChunkQueue> queues = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockKey, Boolean> pendingBlocks = new ConcurrentHashMap<>();
    private final AtomicInteger pendingJobs = new AtomicInteger();
    private final AtomicLong nextOverflowLogNanos = new AtomicLong();

    private volatile OptimizationSettings settings = OptimizationSettings.disabled();

    public void configure(@NotNull OptimizationSettings nextSettings) {
        settings = OptimizationSettings.disabled();
        clear();
        settings = nextSettings;
    }

    public @NotNull SubmitResult submit(
            @NotNull Plugin plugin,
            @NotNull Location location,
            @NotNull Runnable action,
            @NotNull Logger logger
    ) {
        OptimizationSettings current = settings;
        if (!current.enabled()) {
            return SubmitResult.DISABLED;
        }

        World world = location.getWorld();
        if (world == null) {
            return SubmitResult.FALLBACK_TO_DIRECT;
        }

        BlockKey blockKey = new BlockKey(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (current.coalesceDuplicates() && pendingBlocks.putIfAbsent(blockKey, Boolean.TRUE) != null) {
            return SubmitResult.COALESCED;
        }

        if (!reserveSlot(current.maxPendingJobs())) {
            if (current.coalesceDuplicates()) {
                pendingBlocks.remove(blockKey);
            }
            logOverflow(logger);
            return SubmitResult.FALLBACK_TO_DIRECT;
        }

        ChunkKey chunkKey = new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        QueueJob job = new QueueJob(blockKey, action);
        ChunkQueue queue;
        boolean schedule;

        while (true) {
            queue = queues.computeIfAbsent(chunkKey, ignored -> new ChunkQueue(location.clone()));
            synchronized (queue) {
                if (queues.get(chunkKey) != queue) {
                    continue;
                }
                queue.jobs.addLast(job);
                schedule = !queue.scheduled;
                if (schedule) {
                    queue.scheduled = true;
                }
                break;
            }
        }

        if (schedule && !scheduleRun(plugin, chunkKey, queue, current.initialDelayTicks(), logger)) {
            removeJob(chunkKey, queue, job);
            return SubmitResult.FALLBACK_TO_DIRECT;
        }
        return SubmitResult.ENQUEUED;
    }

    public void clear() {
        for (ChunkQueue queue : queues.values()) {
            synchronized (queue) {
                queue.jobs.clear();
                queue.scheduled = false;
            }
        }
        queues.clear();
        pendingBlocks.clear();
        pendingJobs.set(0);
    }

    private void drain(@NotNull Plugin plugin, @NotNull ChunkKey key, @NotNull ChunkQueue queue, @NotNull Logger logger) {
        OptimizationSettings current = settings;
        if (!current.enabled()) {
            discardQueue(key, queue);
            return;
        }

        int processed = 0;
        while (processed++ < current.maxJobsPerRun()) {
            QueueJob job;
            synchronized (queue) {
                job = queue.jobs.pollFirst();
            }
            if (job == null) {
                break;
            }

            try {
                job.action.run();
            }
            catch (RuntimeException exception) {
                logger.log(Level.WARNING, "AutoHarvest optimized queue rejected a queued operation.", exception);
            }
            finally {
                releaseJob(job);
            }
        }

        boolean reschedule;
        synchronized (queue) {
            reschedule = !queue.jobs.isEmpty();
            if (!reschedule) {
                queue.scheduled = false;
                queues.remove(key, queue);
            }
        }
        if (reschedule && !scheduleRun(plugin, key, queue, current.continuationDelayTicks(), logger)) {
            discardQueue(key, queue);
        }
    }

    private boolean scheduleRun(
            Plugin plugin,
            ChunkKey key,
            ChunkQueue queue,
            int delayTicks,
            Logger logger
    ) {
        try {
            Bukkit.getRegionScheduler().runDelayed(plugin, queue.anchor, ignored -> drain(plugin, key, queue, logger), delayTicks);
            return true;
        }
        catch (RuntimeException exception) {
            logger.log(Level.WARNING, "AutoHarvest could not schedule an optimized region batch.", exception);
            return false;
        }
    }

    private void removeJob(ChunkKey key, ChunkQueue queue, QueueJob job) {
        synchronized (queue) {
            queue.jobs.remove(job);
            if (queue.jobs.isEmpty()) {
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
            queue.scheduled = false;
            queues.remove(key, queue);
        }
        for (QueueJob job : discarded) {
            releaseJob(job);
        }
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
        pendingBlocks.remove(job.blockKey);
        pendingJobs.updateAndGet(current -> Math.max(0, current - 1));
    }

    private void logOverflow(Logger logger) {
        long now = System.nanoTime();
        long next = nextOverflowLogNanos.get();
        if (now >= next && nextOverflowLogNanos.compareAndSet(next, now + OVERFLOW_LOG_INTERVAL_NANOS)) {
            logger.warning("AutoHarvest optimize-module queue is full; using a normal region task for this crop.");
        }
    }

    public enum SubmitResult {
        DISABLED,
        ENQUEUED,
        COALESCED,
        FALLBACK_TO_DIRECT
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record BlockKey(UUID worldId, int blockX, int blockY, int blockZ) {
    }

    private record QueueJob(BlockKey blockKey, Runnable action) {
    }

    private static final class ChunkQueue {
        private final Location anchor;
        private final Deque<QueueJob> jobs = new ArrayDeque<>();
        private boolean scheduled;

        private ChunkQueue(Location anchor) {
            this.anchor = anchor;
        }
    }
}
