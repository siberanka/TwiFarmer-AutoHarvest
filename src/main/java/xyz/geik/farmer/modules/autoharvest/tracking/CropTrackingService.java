package xyz.geik.farmer.modules.autoharvest.tracking;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.handlers.FarmerBoughtEvent;
import xyz.geik.farmer.helpers.WorldHelper;
import xyz.geik.farmer.modules.autoharvest.AutoHarvest;
import xyz.geik.farmer.modules.autoharvest.configuration.TrackingSettings;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestEvent;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Reconciles crops missed by growth events without force-loading chunks. Chunk
 * snapshots are captured on their owning region and analyzed asynchronously.
 */
public final class CropTrackingService implements Listener {

    private final AutoHarvest module;
    private final AutoHarvestEvent harvestHandler;
    private final Plugin plugin;
    private final ConcurrentHashMap<ChunkKey, Long> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TrackedChunk> trackedOrder = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChunkKey> scanQueue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkKey> pendingScans = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingScanCount = new AtomicInteger();
    private final AtomicInteger activeScans = new AtomicInteger();
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicLong trackingRevision = new AtomicLong();

    private volatile TrackingSettings settings = TrackingSettings.baseline();
    private volatile ScheduledTask ticker;
    private volatile boolean running;
    private long ticks;

    public CropTrackingService(@NotNull AutoHarvest module, @NotNull AutoHarvestEvent harvestHandler) {
        this.module = module;
        this.harvestHandler = harvestHandler;
        this.plugin = Main.getInstance();
    }

    public void start(@NotNull TrackingSettings nextSettings) {
        stop();
        settings = nextSettings;
        running = true;
        long generation = module.getLifecycleGeneration();
        ticker = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, ignored -> tick(generation), 1L, 1L);

        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            schedulePlayerScan(player, settings.bootstrapRadiusChunks());
        }
    }

    public void stop() {
        running = false;
        ScheduledTask currentTicker = ticker;
        ticker = null;
        if (currentTicker != null) {
            currentTicker.cancel();
        }
        trackedChunks.clear();
        trackedOrder.clear();
        scanQueue.clear();
        pendingScans.clear();
        pendingScanCount.set(0);
        activeScans.set(0);
        ticks = 0L;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkLoad(@NotNull ChunkLoadEvent event) {
        requestScan(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(@NotNull ChunkUnloadEvent event) {
        untrack(new ChunkKey(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropPlaced(@NotNull BlockPlaceEvent event) {
        XMaterial crop = module.resolveCrop(event.getBlockPlaced().getType());
        if (crop != null) {
            track(event.getBlockPlaced().getChunk());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropGrowthObserved(@NotNull BlockGrowEvent event) {
        BlockState state = event.getNewState();
        if (module.resolveCrop(state.getType()) != null) {
            track(state.getChunk());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropFertilized(@NotNull BlockFertilizeEvent event) {
        for (BlockState state : event.getBlocks()) {
            if (module.resolveCrop(state.getType()) != null) {
                track(state.getChunk());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFarmerBought(@NotNull FarmerBoughtEvent event) {
        Player owner = Bukkit.getPlayer(event.getOwnerUUID());
        if (owner != null) {
            schedulePlayerScan(owner, settings.purchaseRadiusChunks());
        }
    }

    @EventHandler
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        schedulePlayerScan(event.getPlayer(), settings.bootstrapRadiusChunks());
    }

    public void scanAround(@NotNull Player player, int radiusChunks) {
        if (!running) {
            return;
        }
        Location center = player.getLocation();
        World world = center.getWorld();
        if (!WorldHelper.isFarmerAllowed(world.getName())) {
            return;
        }
        int centerX = center.getBlockX() >> 4;
        int centerZ = center.getBlockZ() >> 4;
        List<ChunkCoordinate> visibleChunks = new ArrayList<>();
        for (long key : player.getSentChunkKeys()) {
            int chunkX = (int) key;
            int chunkZ = (int) (key >>> 32);
            if (Math.abs(chunkX - centerX) <= radiusChunks
                    && Math.abs(chunkZ - centerZ) <= radiusChunks) {
                visibleChunks.add(new ChunkCoordinate(chunkX, chunkZ));
            }
        }
        visibleChunks.sort(Comparator.comparingInt(chunk ->
                Math.max(Math.abs(chunk.x() - centerX), Math.abs(chunk.z() - centerZ))));
        for (ChunkCoordinate chunk : visibleChunks) {
            requestScan(world, chunk.x(), chunk.z());
        }
    }

    private void schedulePlayerScan(Player player, int radius) {
        player.getScheduler().run(plugin, ignored -> scanAround(player, radius), null);
    }

    private void tick(long generation) {
        if (!running || !module.isActiveGeneration(generation)) {
            return;
        }
        TrackingSettings current = settings;
        if (++ticks >= current.reconcileIntervalTicks()) {
            ticks = 0L;
            enqueueTracked(current.maxChunksPerCycle());
        }
        dispatch(current, generation);
    }

    private void enqueueTracked(int maximum) {
        int visited = 0;
        while (visited++ < maximum) {
            TrackedChunk tracked = trackedOrder.poll();
            if (tracked == null) {
                return;
            }
            ChunkKey key = tracked.key();
            Long currentRevision = trackedChunks.get(key);
            if (currentRevision != null && currentRevision == tracked.revision()) {
                trackedOrder.offer(tracked);
                World world = Bukkit.getWorld(key.worldId());
                if (world != null) {
                    requestScan(world, key.chunkX(), key.chunkZ());
                }
            }
        }
    }

    private void dispatch(TrackingSettings current, long generation) {
        while (activeScans.get() < current.maxConcurrentScans()) {
            ChunkKey key = scanQueue.poll();
            if (key == null) {
                return;
            }
            World world = Bukkit.getWorld(key.worldId());
            if (world == null || !module.isActiveGeneration(generation)) {
                releasePending(key);
                continue;
            }
            activeScans.incrementAndGet();
            try {
                Bukkit.getRegionScheduler().run(plugin, world, key.chunkX(), key.chunkZ(),
                        ignored -> captureSnapshot(world, key, current, generation));
            }
            catch (RuntimeException exception) {
                finishScan(key);
                logFailure("could not schedule a chunk snapshot", exception);
            }
        }
    }

    private void captureSnapshot(World world, ChunkKey key, TrackingSettings current, long generation) {
        if (!running || !module.isActiveGeneration(generation)
                || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
            finishScan(key);
            return;
        }

        try {
            Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            int minimumY = world.getMinHeight();
            int maximumY = world.getMaxHeight();
            Bukkit.getAsyncScheduler().runNow(plugin, ignored ->
                    analyzeSnapshot(world, key, snapshot, minimumY, maximumY, current, generation));
        }
        catch (RuntimeException exception) {
            finishScan(key);
            logFailure("could not capture a chunk snapshot", exception);
        }
    }

    private void analyzeSnapshot(
            World world,
            ChunkKey key,
            ChunkSnapshot snapshot,
            int minimumY,
            int maximumY,
            TrackingSettings current,
            long generation
    ) {
        try {
            if (!running || !module.isActiveGeneration(generation)) {
                return;
            }
            ChunkCropScanner.ScanResult result = ChunkCropScanner.scan(
                    snapshot, minimumY, maximumY, module.getCropMaterials(), current.maxCandidatesPerScan());
            if (result.containsCrop()) {
                track(key);
            }
            else {
                untrack(key);
            }

            int baseX = key.chunkX() << 4;
            int baseZ = key.chunkZ() << 4;
            for (ChunkCropScanner.CropCandidate candidate : result.candidates()) {
                Location location = new Location(world,
                        baseX + candidate.localX(), candidate.y(), baseZ + candidate.localZ());
                harvestHandler.scheduleCandidate(location, candidate.material());
            }
        }
        catch (RuntimeException exception) {
            logFailure("rejected an asynchronous crop snapshot", exception);
        }
        finally {
            finishScan(key);
        }
    }

    private void requestScan(World world, int chunkX, int chunkZ) {
        if (!running || !WorldHelper.isFarmerAllowed(world.getName())) {
            return;
        }
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        if (pendingScans.contains(key) || !reservePending(settings.maxPendingScans())) {
            return;
        }
        if (pendingScans.add(key)) {
            scanQueue.offer(key);
        }
        else {
            pendingScanCount.decrementAndGet();
        }
    }

    private void track(Chunk chunk) {
        track(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    private void track(ChunkKey key) {
        synchronized (trackedChunks) {
            if (trackedChunks.containsKey(key) || trackedChunks.size() >= settings.maxTrackedChunks()) {
                return;
            }
            long revision = trackingRevision.incrementAndGet();
            trackedChunks.put(key, revision);
            trackedOrder.offer(new TrackedChunk(key, revision));
        }
    }

    private void untrack(ChunkKey key) {
        synchronized (trackedChunks) {
            trackedChunks.remove(key);
        }
    }

    private void finishScan(ChunkKey key) {
        releasePending(key);
        activeScans.updateAndGet(value -> Math.max(0, value - 1));
    }

    private boolean reservePending(int limit) {
        while (true) {
            int current = pendingScanCount.get();
            if (current >= limit) {
                return false;
            }
            if (pendingScanCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releasePending(ChunkKey key) {
        if (pendingScans.remove(key)) {
            pendingScanCount.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private void logFailure(String message, RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        if (now >= next && nextFailureLogNanos.compareAndSet(next, now + 5_000_000_000L)) {
            Main.getInstance().getLogger().log(Level.WARNING, "AutoHarvest " + message + '.', exception);
        }
    }

    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record ChunkCoordinate(int x, int z) {
    }

    private record TrackedChunk(ChunkKey key, long revision) {
    }
}
