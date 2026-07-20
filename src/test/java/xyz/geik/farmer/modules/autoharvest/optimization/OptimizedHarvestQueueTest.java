package xyz.geik.farmer.modules.autoharvest.optimization;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import xyz.geik.farmer.modules.autoharvest.configuration.BackpressureSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.HarvestPacingScope;
import xyz.geik.farmer.modules.autoharvest.configuration.OptimizationSettings;
import xyz.geik.farmer.modules.autoharvest.configuration.TelemetrySettings;
import xyz.geik.farmer.modules.autoharvest.logging.ModuleDiagnostics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizedHarvestQueueTest {

    private static final Logger LOGGER = Logger.getLogger("OptimizedHarvestQueueTest");

    @Test
    void stoppedQueueDoesNotScheduleOrRunWork() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        AtomicInteger executions = new AtomicInteger();

        OptimizedHarvestQueue.SubmitResult result = queue.submit(
                mock(Plugin.class), mock(Location.class), executions::incrementAndGet, LOGGER);

        assertEquals(OptimizedHarvestQueue.SubmitResult.STOPPED, result);
        assertEquals(0, executions.get());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void globalBudgetIsSharedAcrossManyChunkQueues() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        queue.configure(settings(8, 10, 64, 256), BackpressureSettings.DEFAULT, TelemetrySettings.DISABLED);

        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask ticker = mock(ScheduledTask.class);
        UUID worldId = UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Consumer<ScheduledTask>> tick = new AtomicReference<>();
        when(world.getUID()).thenReturn(worldId);
        when(ticker.getOwningPlugin()).thenReturn(plugin);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);
            bukkit.when(Bukkit::getAverageTickTime).thenReturn(10.0);
            doAnswer(invocation -> {
                tick.set(invocation.getArgument(1));
                return ticker;
            }).when(globalScheduler).runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L));
            doAnswer(invocation -> {
                Consumer callback = invocation.getArgument(2);
                callback.accept(null);
                return null;
            }).when(regionScheduler).run(eq(plugin), any(Location.class), any());

            for (int index = 0; index < 100; index++) {
                assertEquals(OptimizedHarvestQueue.SubmitResult.ENQUEUED,
                        queue.submit(plugin, location(world, index * 16), executions::incrementAndGet, LOGGER));
            }
            tick.get().accept(ticker);

            assertEquals(10, executions.get());
            assertEquals(90, queue.stats().pendingJobs());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void smallGlobalBudgetEventuallyHarvestsEveryFarmerScope() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        queue.configure(settings(1, 3, 3, 512), BackpressureSettings.DEFAULT, TelemetrySettings.DISABLED);

        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask ticker = mock(ScheduledTask.class);
        AtomicReference<Consumer<ScheduledTask>> tick = new AtomicReference<>();
        Set<Integer> harvestedFarmers = new HashSet<>();
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(ticker.getOwningPlugin()).thenReturn(plugin);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);
            bukkit.when(Bukkit::getAverageTickTime).thenReturn(10.0);
            doAnswer(invocation -> {
                tick.set(invocation.getArgument(1));
                return ticker;
            }).when(globalScheduler).runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L));
            doAnswer(invocation -> {
                Consumer callback = invocation.getArgument(2);
                callback.accept(null);
                return null;
            }).when(regionScheduler).run(eq(plugin), any(Location.class), any());

            int farmerCount = 200;
            for (int index = 0; index < farmerCount; index++) {
                int farmerId = index;
                assertEquals(OptimizedHarvestQueue.SubmitResult.ENQUEUED,
                        queue.submit(plugin, location(world, index * 16), "farmer:" + index,
                                () -> harvestedFarmers.add(farmerId), LOGGER));
            }
            for (int cycle = 0; cycle < 100 && harvestedFarmers.size() < farmerCount; cycle++) {
                tick.get().accept(ticker);
            }

            assertEquals(farmerCount, harvestedFarmers.size());
            assertEquals(0, queue.stats().pendingJobs());
            assertEquals(0, queue.stats().pendingScopes());
        }
    }

    @Test
    void overflowDefersWithoutSubmittingARegionTask() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        ModuleDiagnostics diagnostics = mock(ModuleDiagnostics.class);
        queue.configure(settings(4, 8, 4, 64), BackpressureSettings.DEFAULT,
                TelemetrySettings.DISABLED, diagnostics);
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(globalScheduler.runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L)))
                .thenReturn(mock(ScheduledTask.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);
            for (int index = 0; index < 64; index++) {
                assertEquals(OptimizedHarvestQueue.SubmitResult.ENQUEUED,
                        queue.submit(plugin, location(world, index * 16), () -> { }));
            }

            assertEquals(OptimizedHarvestQueue.SubmitResult.DEFERRED,
                    queue.submit(plugin, location(world, 4096), () -> { }));
            verify(regionScheduler, never()).run(any(), any(Location.class), any());
            verify(diagnostics).debug("AutoHarvest harvest queue is full; crops are deferred to bounded reconciliation.");
            assertEquals(1, queue.stats().deferredJobs());
        }
    }

    @Test
    void duplicateFloodConsumesOneBoundedSlot() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        queue.configure(settings(4, 8, 4, 64), BackpressureSettings.DEFAULT, TelemetrySettings.DISABLED);
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        Location location = location(world, 8);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(globalScheduler.runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L)))
                .thenReturn(mock(ScheduledTask.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            assertEquals(OptimizedHarvestQueue.SubmitResult.ENQUEUED,
                    queue.submit(plugin, location, () -> { }, LOGGER));
            for (int index = 0; index < 65_536; index++) {
                assertEquals(OptimizedHarvestQueue.SubmitResult.COALESCED,
                        queue.submit(plugin, location, () -> { }, LOGGER));
            }

            assertEquals(1, queue.stats().pendingJobs());
            assertEquals(65_536L, queue.stats().coalescedJobs());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pacesOneFarmerWithoutBlockingAnotherFarmer() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        queue.configure(new OptimizationSettings(true, 1, 1, 8, 10, 64, 256,
                        true, HarvestPacingScope.FARMER, true, 2, false, 64, 20),
                BackpressureSettings.DEFAULT, TelemetrySettings.DISABLED);

        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask ticker = mock(ScheduledTask.class);
        AtomicInteger farmerA = new AtomicInteger();
        AtomicInteger farmerB = new AtomicInteger();
        AtomicInteger drainedChunks = new AtomicInteger();
        AtomicReference<Consumer<ScheduledTask>> tick = new AtomicReference<>();
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(ticker.getOwningPlugin()).thenReturn(plugin);
        queue.setChunkDrainListener((worldId, chunkX, chunkZ) -> drainedChunks.incrementAndGet());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);
            bukkit.when(Bukkit::getAverageTickTime).thenReturn(10.0);
            doAnswer(invocation -> {
                tick.set(invocation.getArgument(1));
                return ticker;
            }).when(globalScheduler).runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L));
            doAnswer(invocation -> {
                Consumer callback = invocation.getArgument(2);
                callback.accept(null);
                return null;
            }).when(regionScheduler).run(eq(plugin), any(Location.class), any());

            queue.submit(plugin, location(world, 0), "farmer:a", farmerA::incrementAndGet, LOGGER);
            queue.submit(plugin, location(world, 32), "farmer:a", farmerA::incrementAndGet, LOGGER);
            queue.submit(plugin, location(world, 64), "farmer:b", farmerB::incrementAndGet, LOGGER);

            tick.get().accept(ticker);
            assertEquals(1, farmerA.get());
            assertEquals(1, farmerB.get());

            tick.get().accept(ticker);
            assertEquals(1, farmerA.get());

            tick.get().accept(ticker);
            assertEquals(2, farmerA.get());
            assertEquals(1, farmerB.get());
            assertEquals(3, drainedChunks.get());
            assertEquals(0, queue.stats().pendingJobs());
            assertEquals(0, queue.stats().pendingScopes());
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pausesOneFarmerAfterItsConfiguredBatchWithoutBlockingAnotherFarmer() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        queue.configure(new OptimizationSettings(true, 1, 1, 8, 10, 64, 256,
                        true, HarvestPacingScope.FARMER, false, 2, true, 2, 3),
                BackpressureSettings.DEFAULT, TelemetrySettings.DISABLED);

        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask ticker = mock(ScheduledTask.class);
        AtomicInteger farmerA = new AtomicInteger();
        AtomicInteger farmerB = new AtomicInteger();
        AtomicReference<Consumer<ScheduledTask>> tick = new AtomicReference<>();
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(ticker.getOwningPlugin()).thenReturn(plugin);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);
            bukkit.when(Bukkit::getAverageTickTime).thenReturn(10.0);
            doAnswer(invocation -> {
                tick.set(invocation.getArgument(1));
                return ticker;
            }).when(globalScheduler).runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L));
            doAnswer(invocation -> {
                Consumer callback = invocation.getArgument(2);
                callback.accept(null);
                return null;
            }).when(regionScheduler).run(eq(plugin), any(Location.class), any());

            queue.submit(plugin, location(world, 0), "farmer:a", farmerA::incrementAndGet, LOGGER);
            queue.submit(plugin, location(world, 32), "farmer:a", farmerA::incrementAndGet, LOGGER);
            queue.submit(plugin, location(world, 64), "farmer:a", farmerA::incrementAndGet, LOGGER);
            queue.submit(plugin, location(world, 96), "farmer:b", farmerB::incrementAndGet, LOGGER);

            tick.get().accept(ticker);
            assertEquals(2, farmerA.get());
            assertEquals(1, farmerB.get());

            tick.get().accept(ticker);
            tick.get().accept(ticker);
            assertEquals(2, farmerA.get());

            tick.get().accept(ticker);
            assertEquals(3, farmerA.get());
            assertEquals(0, queue.stats().pendingJobs());
            assertEquals(0, queue.stats().pendingScopes());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconfigureInvalidatesAnAlreadyScheduledDispatcher() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        queue.configure(settings(4, 8, 4, 64), BackpressureSettings.DEFAULT, TelemetrySettings.DISABLED);
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask ticker = mock(ScheduledTask.class);
        AtomicReference<Consumer<ScheduledTask>> tick = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        when(world.getUID()).thenReturn(UUID.randomUUID());
        doAnswer(invocation -> {
            tick.set(invocation.getArgument(1));
            return ticker;
        }).when(globalScheduler).runAtFixedRate(eq(plugin), any(), eq(1L), eq(1L));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
            queue.submit(plugin, location(world, 8), executions::incrementAndGet, LOGGER);
            queue.configure(OptimizationSettings.stopped(), BackpressureSettings.BASELINE,
                    TelemetrySettings.DISABLED);
            tick.get().accept(ticker);

            assertEquals(0, executions.get());
            assertEquals(0, queue.stats().pendingJobs());
        }
    }

    private OptimizationSettings settings(int perRun, int global, int submissions, int pending) {
        return new OptimizationSettings(true, 1, 1, perRun, global, submissions, pending,
                true, HarvestPacingScope.FARMER, false, 1, false, 64, 20);
    }

    private Location location(World world, int blockX) {
        Location location = mock(Location.class);
        Location anchor = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlockX()).thenReturn(blockX);
        when(location.getBlockY()).thenReturn(64);
        when(location.getBlockZ()).thenReturn(8);
        when(location.clone()).thenReturn(anchor);
        return location;
    }
}
