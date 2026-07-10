package xyz.geik.farmer.modules.autoharvest.optimization;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import xyz.geik.farmer.modules.autoharvest.configuration.OptimizationSettings;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class OptimizedHarvestQueueTest {

    private static final Logger LOGGER = Logger.getLogger("OptimizedHarvestQueueTest");

    @Test
    void disabledOptimizationDoesNotScheduleOrRunWork() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        AtomicInteger executions = new AtomicInteger();

        OptimizedHarvestQueue.SubmitResult result = queue.submit(
                mock(Plugin.class), mock(Location.class), executions::incrementAndGet, LOGGER);

        assertEquals(OptimizedHarvestQueue.SubmitResult.DISABLED, result);
        assertEquals(0, executions.get());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void enabledOptimizationRunsTheBatchOnPaperRegionScheduler() {
        OptimizedHarvestQueue queue = new OptimizedHarvestQueue();
        queue.configure(new OptimizationSettings(true, 2, 1, 32, 64, true));

        Plugin plugin = mock(Plugin.class);
        Location location = mock(Location.class);
        Location anchor = mock(Location.class);
        World world = mock(World.class);
        RegionScheduler scheduler = mock(RegionScheduler.class);
        AtomicInteger executions = new AtomicInteger();

        when(location.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(location.getBlockX()).thenReturn(8);
        when(location.getBlockY()).thenReturn(64);
        when(location.getBlockZ()).thenReturn(8);
        when(location.clone()).thenReturn(anchor);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getRegionScheduler).thenReturn(scheduler);
            doAnswer(invocation -> {
                Consumer callback = invocation.getArgument(2);
                callback.accept(null);
                return null;
            }).when(scheduler).runDelayed(eq(plugin), eq(anchor), any(), eq(2L));

            OptimizedHarvestQueue.SubmitResult result = queue.submit(
                    plugin, location, executions::incrementAndGet, LOGGER);

            assertEquals(OptimizedHarvestQueue.SubmitResult.ENQUEUED, result);
            assertEquals(1, executions.get());
        }
    }
}
