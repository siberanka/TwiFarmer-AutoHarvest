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
import xyz.geik.farmer.model.Farmer;
import xyz.geik.farmer.modules.autoharvest.AutoHarvest;
import xyz.geik.farmer.modules.autoharvest.compat.FarmerAccess;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.TelemetrySettings;
import xyz.geik.farmer.modules.autoharvest.configuration.TrackingSettings;
import xyz.geik.farmer.modules.autoharvest.handlers.AutoHarvestEvent;
import xyz.geik.farmer.modules.autoharvest.optimization.AdaptiveBackpressure;
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
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

/**
 * Event-driven and periodic crop reconciliation with global snapshot, scan and
 * memory budgets. All Bukkit world access stays on the owning region thread.
 */
public final class CropTrackingService implements Listener {

    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long PLAYER_JOIN_SCAN_DELAY_TICKS = 40L;

    private final AutoHarvest module;
    private final AutoHarvestEvent harvestHandler;
    private final Plugin plugin;
    private final ConcurrentHashMap<ChunkKey, Long> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TrackedChunk> trackedOrder = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<ChunkKey, Long> loadedChunks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TrackedChunk> loadedOrder = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChunkKey> scanQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ScanWork> sliceQueue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkKey> pendingScans = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingScanCount = new AtomicInteger();
    private final AtomicInteger activeScans = new AtomicInteger();
    private final AtomicInteger blockPermits = new AtomicInteger();
    private final AtomicInteger trackedOrderEntries = new AtomicInteger();
    private final AtomicInteger loadedOrderEntries = new AtomicInteger();
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicLong trackingRevision = new AtomicLong();
    private final LongAdder completedScans = new LongAdder();
    private final LongAdder capturedSnapshots = new LongAdder();
    private final LongAdder scannedBlocks = new LongAdder();
    private final LongAdder droppedScanRequests = new LongAdder();
    private final LongAdder pausedTicks = new LongAdder();
    private final AdaptiveBackpressure backpressure = new AdaptiveBackpressure();
    private final Object tickerLock = new Object();

    private volatile TrackingSettings settings = TrackingSettings.baseline();
    private volatile TelemetrySettings telemetry = TelemetrySettings.DISABLED;
    private volatile ScheduledTask ticker;
    private volatile boolean running;
    private volatile long tickerDueNanos;
    private long nextReconcileNanos;
    private long budgetWindowNanos;
    private int scanStartsInWindow;
    private long nextTelemetryNanos;

    public CropTrackingService(@NotNull AutoHarvest module, @NotNull AutoHarvestEvent harvestHandler) {
        this.module = module;
        this.harvestHandler = harvestHandler;
        this.plugin = Main.getInstance();
    }

    public void start(
            @NotNull TrackingSettings nextSettings,
            @NotNull BackpressureSettings backpressureSettings,
            @NotNull TelemetrySettings telemetrySettings
    ) {
        stop();
        settings = nextSettings;
        telemetry = telemetrySettings;
        backpressure.configure(backpressureSettings);
        running = true;
        long now = System.nanoTime();
        budgetWindowNanos = now;
        blockPermits.set(sectionBlockBudget(nextSettings));
        nextReconcileNanos = now + ticksToNanos(nextSettings.reconcileIntervalTicks());
        nextTelemetryNanos = now + telemetrySettings.logIntervalSeconds() * SECOND_NANOS;

        if (nextSettings.scanOnPlayerJoin()) {
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                schedulePlayerScan(player, nextSettings.bootstrapRadiusChunks(), false);
            }
        }
        scheduleTick(nextSettings.reconcileIntervalTicks());
    }

    public void stop() {
        running = false;
        synchronized (tickerLock) {
            ScheduledTask currentTicker = ticker;
            ticker = null;
            tickerDueNanos = 0L;
            if (currentTicker != null) {
                currentTicker.cancel();
            }
        }
        trackedChunks.clear();
        trackedOrder.clear();
        loadedChunks.clear();
        loadedOrder.clear();
        scanQueue.clear();
        sliceQueue.clear();
        pendingScans.clear();
        pendingScanCount.set(0);
        activeScans.set(0);
        blockPermits.set(0);
        trackedOrderEntries.set(0);
        loadedOrderEntries.set(0);
        scanStartsInWindow = 0;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkLoad(@NotNull ChunkLoadEvent event) {
        TrackingSettings current = settings;
        if (current.reconcilesLoadedChunks() && !current.farmerRegionsOnly()) {
            trackLoaded(event.getChunk());
        }
        if (current.scanOnChunkLoad() && (!current.farmerRegionsOnly() || module.isWithoutFarmer())) {
            requestScan(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(@NotNull ChunkUnloadEvent event) {
        ChunkKey key = new ChunkKey(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
        untrack(key);
        loadedChunks.remove(key);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropPlaced(@NotNull BlockPlaceEvent event) {
        TrackingSettings current = settings;
        if (current.mode().usesEvents() && current.cropPlaceEvents()
                && module.resolveCrop(event.getBlockPlaced().getType()) != null
                && trackingLocationAllowed(event.getBlockPlaced().getLocation(), current)) {
            track(event.getBlockPlaced().getChunk());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropGrowthObserved(@NotNull BlockGrowEvent event) {
        TrackingSettings current = settings;
        BlockState state = event.getNewState();
        if (current.listensToGrowth() && module.resolveCrop(state.getType()) != null
                && trackingLocationAllowed(state.getLocation(), current)) {
            track(state.getChunk());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropFertilized(@NotNull BlockFertilizeEvent event) {
        TrackingSettings current = settings;
        if (!current.listensToFertilize()) {
            return;
        }
        for (BlockState state : event.getBlocks()) {
            if (module.resolveCrop(state.getType()) != null
                    && trackingLocationAllowed(state.getLocation(), current)) {
                track(state.getChunk());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFarmerBought(@NotNull FarmerBoughtEvent event) {
        if (!settings.scanOnFarmerPurchase()) {
            return;
        }
        Player owner = Bukkit.getPlayer(event.getOwnerUUID());
        if (owner != null) {
            schedulePlayerScan(owner, settings.purchaseRadiusChunks(), true);
        }
    }

    @EventHandler
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        if (settings.scanOnPlayerJoin()) {
            schedulePlayerScan(event.getPlayer(), settings.bootstrapRadiusChunks(), false,
                    PLAYER_JOIN_SCAN_DELAY_TICKS);
        }
    }

    public void scanAround(@NotNull Player player, int radiusChunks) {
        scanAround(player, radiusChunks, true);
    }

    public void requestReconciliation(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null || !running) {
            return;
        }
        ChunkKey key = new ChunkKey(world.getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        track(key);
        requestScan(world, key.chunkX(), key.chunkZ(), true);
    }

    private void scanAround(Player player, int radiusChunks, boolean farmerScoped) {
        if (!running) {
            return;
        }
        Location center = player.getLocation();
        World world = center.getWorld();
        TrackingSettings current = settings;
        if (!WorldHelper.isFarmerAllowed(world.getName())
                || (current.farmerRegionsOnly() && !farmerScoped
                && !module.isWithoutFarmer() && !hasEnabledFarmer(center))) {
            return;
        }
        int centerX = center.getBlockX() >> 4;
        int centerZ = center.getBlockZ() >> 4;
        List<ChunkCoordinate> visibleChunks = new ArrayList<>();
        for (long key : player.getSentChunkKeys()) {
            int chunkX = (int) key;
            int chunkZ = (int) (key >>> 32);
            if (Math.abs(chunkX - centerX) <= radiusChunks && Math.abs(chunkZ - centerZ) <= radiusChunks) {
                visibleChunks.add(new ChunkCoordinate(chunkX, chunkZ));
            }
        }
        if (visibleChunks.isEmpty() && world.isChunkLoaded(centerX, centerZ)) {
            visibleChunks.add(new ChunkCoordinate(centerX, centerZ));
        }
        visibleChunks.sort(Comparator.comparingInt(chunk ->
                Math.max(Math.abs(chunk.x() - centerX), Math.abs(chunk.z() - centerZ))));
        for (ChunkCoordinate chunk : visibleChunks) {
            ChunkKey key = new ChunkKey(world.getUID(), chunk.x(), chunk.z());
            if (current.reconcilesLoadedChunks()) {
                trackLoaded(key);
            }
            requestScan(world, chunk.x(), chunk.z(), true);
        }
    }

    private void schedulePlayerScan(Player player, int radius, boolean farmerScoped) {
        player.getScheduler().run(plugin, ignored -> scanAround(player, radius, farmerScoped), null);
    }

    private void schedulePlayerScan(Player player, int radius, boolean farmerScoped, long delayTicks) {
        player.getScheduler().runDelayed(plugin,
                ignored -> scanAround(player, radius, farmerScoped), null, delayTicks);
    }

    private void tick(long generation) {
        if (!running || !module.isActiveGeneration(generation)) {
            return;
        }
        TrackingSettings current = settings;
        long now = System.nanoTime();
        refillBudgets(current, now);
        if (now >= nextReconcileNanos) {
            enqueueTracked(current.maxChunksPerCycle(), current.reconcilesLoadedChunks());
            nextReconcileNanos = now + ticksToNanos(current.reconcileIntervalTicks());
        }

        if (backpressure.permitsWork()) {
            dispatchCaptures(current, generation);
            dispatchSlices(current, generation);
        }
        else {
            pausedTicks.increment();
        }
        logTelemetry(now);
        scheduleNextTick(current, now);
    }

    private void refillBudgets(TrackingSettings current, long now) {
        if (now - budgetWindowNanos >= SECOND_NANOS) {
            budgetWindowNanos = now;
            scanStartsInWindow = 0;
            blockPermits.set(sectionBlockBudget(current));
        }
    }

    private void enqueueTracked(int maximum, boolean useLoaded) {
        ConcurrentLinkedQueue<TrackedChunk> order = useLoaded ? loadedOrder : trackedOrder;
        ConcurrentHashMap<ChunkKey, Long> index = useLoaded ? loadedChunks : trackedChunks;
        AtomicInteger orderEntries = useLoaded ? loadedOrderEntries : trackedOrderEntries;
        int visited = 0;
        while (visited++ < maximum) {
            TrackedChunk tracked = order.poll();
            if (tracked == null) {
                return;
            }
            orderEntries.updateAndGet(value -> Math.max(0, value - 1));
            ChunkKey key = tracked.key();
            Long currentRevision = index.get(key);
            if (currentRevision != null && currentRevision == tracked.revision()) {
                order.offer(tracked);
                orderEntries.incrementAndGet();
                World world = Bukkit.getWorld(key.worldId());
                if (world != null) {
                    requestScan(world, key.chunkX(), key.chunkZ(), false);
                }
            }
        }
    }

    private void dispatchCaptures(TrackingSettings current, long generation) {
        int captures = 0;
        while (captures < current.maxSnapshotCapturesPerTick()
                && activeScans.get() < current.maxConcurrentScans()
                && scanStartsInWindow < current.maxScanStartsPerSecond()) {
            ChunkKey key = scanQueue.poll();
            if (key == null) {
                return;
            }
            if (!pendingScans.contains(key)) {
                continue;
            }
            World world = Bukkit.getWorld(key.worldId());
            if (world == null || !module.isActiveGeneration(generation)) {
                releasePending(key);
                continue;
            }
            activeScans.incrementAndGet();
            scanStartsInWindow++;
            captures++;
            try {
                long scheduledAt = System.nanoTime();
                Bukkit.getRegionScheduler().run(plugin, world, key.chunkX(), key.chunkZ(),
                        ignored -> {
                            backpressure.observeRegionTaskDelay(scheduledAt);
                            captureSnapshot(world, key, current, generation);
                        });
            }
            catch (RuntimeException exception) {
                retainForReconciliation(key);
                finishScan(key);
                logFailure("could not schedule a chunk snapshot", exception);
            }
        }
    }

    private void captureSnapshot(World world, ChunkKey key, TrackingSettings current, long generation) {
        if (!running || !module.isActiveGeneration(generation)
                || !pendingScans.contains(key) || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
            finishScan(key);
            return;
        }
        try {
            Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            ChunkCropScanner.ScanCursor cursor = ChunkCropScanner.cursor(
                    world.getMinHeight(), world.getMaxHeight(), current.maxCandidatesPerScan());
            capturedSnapshots.increment();
            sliceQueue.offer(new ScanWork(world, key, snapshot, cursor));
            scheduleTick(1L);
        }
        catch (RuntimeException exception) {
            retainForReconciliation(key);
            finishScan(key);
            logFailure("could not capture a chunk snapshot", exception);
        }
    }

    private void dispatchSlices(TrackingSettings current, long generation) {
        int attempts = sliceQueue.size();
        while (attempts-- > 0) {
            int granted = reserveBlockPermits(current.maxBlockChecksPerSlice());
            if (granted <= 0) {
                return;
            }
            ScanWork work = sliceQueue.poll();
            if (work == null) {
                blockPermits.addAndGet(granted);
                return;
            }
            try {
                Bukkit.getAsyncScheduler().runNow(plugin,
                        ignored -> scanSlice(work, granted, current, generation));
            }
            catch (RuntimeException exception) {
                blockPermits.addAndGet(granted);
                retainForReconciliation(work.key());
                finishScan(work.key());
                logFailure("could not schedule an asynchronous scan slice", exception);
            }
        }
    }

    private void scanSlice(ScanWork work, int granted, TrackingSettings current, long generation) {
        try {
            if (!running || !module.isActiveGeneration(generation) || !pendingScans.contains(work.key())) {
                finishScan(work.key());
                return;
            }
            ChunkCropScanner.SliceResult slice = ChunkCropScanner.scanSlice(
                    work.snapshot(), module.getCropMaterials(), work.cursor(), granted);
            scannedBlocks.add(slice.checkedBlocks());
            int unused = granted - slice.checkedBlocks();
            if (unused > 0) {
                blockPermits.addAndGet(unused);
            }
            if (slice.complete()) {
                completeScan(work, ChunkCropScanner.result(work.cursor()));
            }
            else {
                sliceQueue.offer(work);
                scheduleTick(1L);
            }
        }
        catch (RuntimeException exception) {
            retainForReconciliation(work.key());
            finishScan(work.key());
            logFailure("rejected an asynchronous crop snapshot slice", exception);
        }
    }

    private void completeScan(ScanWork work, ChunkCropScanner.ScanResult result) {
        TrackingSettings current = settings;
        if (result.containsCrop() && !current.reconcilesLoadedChunks()) {
            track(work.key());
        }
        else if (!result.containsCrop() && !current.reconcilesLoadedChunks()) {
            untrack(work.key());
        }

        int baseX = work.key().chunkX() << 4;
        int baseZ = work.key().chunkZ() << 4;
        for (ChunkCropScanner.CropCandidate candidate : result.candidates()) {
            Location location = new Location(work.world(),
                    baseX + candidate.localX(), candidate.y(), baseZ + candidate.localZ());
            harvestHandler.scheduleCandidate(location, candidate.material());
        }
        completedScans.increment();
        finishScan(work.key());
    }

    private void requestScan(World world, int chunkX, int chunkZ, boolean wake) {
        if (!running || !WorldHelper.isFarmerAllowed(world.getName())) {
            return;
        }
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        if (pendingScans.contains(key) || !reservePending(settings.maxPendingScans())) {
            if (!pendingScans.contains(key)) {
                droppedScanRequests.increment();
            }
            return;
        }
        if (pendingScans.add(key)) {
            scanQueue.offer(key);
            if (wake) {
                scheduleTick(1L);
            }
        }
        else {
            pendingScanCount.decrementAndGet();
        }
    }

    private void track(Chunk chunk) {
        track(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    private void track(ChunkKey key) {
        trackBounded(key, trackedChunks, trackedOrder, trackedOrderEntries);
    }

    private void trackLoaded(Chunk chunk) {
        trackLoaded(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    private void trackLoaded(ChunkKey key) {
        trackBounded(key, loadedChunks, loadedOrder, loadedOrderEntries);
    }

    private void trackBounded(
            ChunkKey key,
            ConcurrentHashMap<ChunkKey, Long> index,
            ConcurrentLinkedQueue<TrackedChunk> order,
            AtomicInteger orderEntries
    ) {
        synchronized (index) {
            int limit = settings.maxTrackedChunks();
            if (index.containsKey(key) || index.size() >= limit || !reserveOrderEntry(orderEntries, limit * 2)) {
                return;
            }
            long revision = trackingRevision.incrementAndGet();
            index.put(key, revision);
            order.offer(new TrackedChunk(key, revision));
        }
    }

    private boolean reserveOrderEntry(AtomicInteger counter, int limit) {
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void untrack(ChunkKey key) {
        trackedChunks.remove(key);
    }

    private void retainForReconciliation(ChunkKey key) {
        if (running && !settings.reconcilesLoadedChunks()) {
            track(key);
        }
    }

    private boolean trackingLocationAllowed(Location location, TrackingSettings current) {
        return !current.farmerRegionsOnly() || module.isWithoutFarmer() || hasEnabledFarmer(location);
    }

    private boolean hasEnabledFarmer(Location location) {
        try {
            String regionId = Main.getIntegration().getRegionID(location);
            Farmer farmer = FarmerAccess.findByRegionId(regionId);
            return farmer != null && farmer.getAttributeStatus("autoharvest");
        }
        catch (RuntimeException exception) {
            logFailure("could not validate a Farmer tracking scope", exception);
            return false;
        }
    }

    private void finishScan(ChunkKey key) {
        releasePending(key);
        activeScans.updateAndGet(value -> Math.max(0, value - 1));
        if (running) {
            scheduleTick(1L);
        }
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

    private int reserveBlockPermits(int requested) {
        while (true) {
            int current = blockPermits.get();
            if (current <= 0) {
                return 0;
            }
            int granted = Math.min(current, requested);
            if (blockPermits.compareAndSet(current, current - granted)) {
                return granted;
            }
        }
    }

    private void releasePending(ChunkKey key) {
        if (pendingScans.remove(key)) {
            pendingScanCount.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private void scheduleNextTick(TrackingSettings current, long now) {
        boolean immediate = (!scanQueue.isEmpty() && activeScans.get() < current.maxConcurrentScans()
                && scanStartsInWindow < current.maxScanStartsPerSecond())
                || (!sliceQueue.isEmpty() && blockPermits.get() > 0);
        if (immediate && !backpressure.isPaused()) {
            scheduleTick(1L);
            return;
        }
        boolean waiting = !scanQueue.isEmpty() || !sliceQueue.isEmpty();
        long target = nextReconcileNanos;
        if (waiting) {
            target = Math.min(target, budgetWindowNanos + SECOND_NANOS);
        }
        if (backpressure.isPaused()) {
            target = Math.min(target, now + ticksToNanos(20));
        }
        scheduleTick(Math.max(1L, nanosToTicks(target - now)));
    }

    private void scheduleTick(long delayTicks) {
        if (!running) {
            return;
        }
        long due = System.nanoTime() + ticksToNanos(delayTicks);
        synchronized (tickerLock) {
            if (ticker != null && tickerDueNanos <= due) {
                return;
            }
            if (ticker != null) {
                ticker.cancel();
                ticker = null;
            }
            long generation = module.getLifecycleGeneration();
            try {
                tickerDueNanos = due;
                ticker = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                    boolean execute;
                    synchronized (tickerLock) {
                        execute = ticker == task;
                        if (execute) {
                            ticker = null;
                            tickerDueNanos = 0L;
                        }
                    }
                    if (execute) {
                        tick(generation);
                    }
                }, delayTicks);
            }
            catch (RuntimeException exception) {
                ticker = null;
                tickerDueNanos = 0L;
                logFailure("could not schedule its tracking dispatcher", exception);
            }
        }
    }

    private int sectionBlockBudget(TrackingSettings current) {
        return Math.multiplyExact(current.maxSectionsPerSecond(), 4_096);
    }

    private long ticksToNanos(long ticks) {
        return ticks * 50_000_000L;
    }

    private long nanosToTicks(long nanos) {
        return Math.max(1L, (nanos + 49_999_999L) / 50_000_000L);
    }

    private void logTelemetry(long now) {
        TelemetrySettings current = telemetry;
        if (!current.enabled() || now < nextTelemetryNanos) {
            return;
        }
        nextTelemetryNanos = now + current.logIntervalSeconds() * SECOND_NANOS;
        if (pendingScanCount.get() > 0 || droppedScanRequests.sum() > 0) {
            Main.getInstance().getLogger().info("AutoHarvest tracking: mode=" + settings.mode()
                    + ", pending=" + pendingScanCount.get() + ", active=" + activeScans.get()
                    + ", tracked=" + trackedChunks.size() + ", loaded=" + loadedChunks.size()
                    + ", completed=" + completedScans.sum() + ", blocks=" + scannedBlocks.sum()
                    + ", dropped=" + droppedScanRequests.sum()
                    + ", backpressure-paused=" + backpressure.isPaused());
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

    private record ScanWork(
            World world,
            ChunkKey key,
            ChunkSnapshot snapshot,
            ChunkCropScanner.ScanCursor cursor
    ) {
    }
}
