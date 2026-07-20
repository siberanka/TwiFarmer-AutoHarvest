package xyz.geik.farmer.modules.autoharvest.tracking;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairFarmerAreaQueueTest {

    @Test
    void rotatesEveryFarmerBeforeReturningToTheFirst() {
        FairFarmerAreaQueue queue = new FairFarmerAreaQueue();
        queue.register("island-a");
        queue.register("island-b");
        queue.register("island-c");

        FairFarmerAreaQueue.Lease first = queue.poll();
        assertEquals("island-a", first.regionId());
        queue.complete(first, 4, true);

        FairFarmerAreaQueue.Lease second = queue.poll();
        assertEquals("island-b", second.regionId());
        queue.complete(second, 7, true);

        FairFarmerAreaQueue.Lease third = queue.poll();
        assertEquals("island-c", third.regionId());
        queue.complete(third, 2, true);

        FairFarmerAreaQueue.Lease rotated = queue.poll();
        assertEquals("island-a", rotated.regionId());
        assertEquals(4, rotated.cursor());
    }

    @Test
    void queueFullRetryCanRetainTheSameChunkCursor() {
        FairFarmerAreaQueue queue = new FairFarmerAreaQueue();
        queue.register("island-a");
        queue.register("island-b");

        FairFarmerAreaQueue.Lease first = queue.poll();
        queue.complete(first, first.cursor(), true);
        FairFarmerAreaQueue.Lease second = queue.poll();
        queue.complete(second, 1, true);

        FairFarmerAreaQueue.Lease retry = queue.poll();
        assertEquals("island-a", retry.regionId());
        assertEquals(0, retry.cursor());
    }

    @Test
    void duplicateRegistrationAndStaleCompletionCannotDuplicateAnArea() {
        FairFarmerAreaQueue queue = new FairFarmerAreaQueue();
        assertTrue(queue.register("island"));
        assertFalse(queue.register("island"));

        FairFarmerAreaQueue.Lease lease = queue.poll();
        assertTrue(queue.complete(lease, 1, true));
        assertFalse(queue.complete(lease, 2, true));
        assertEquals(1, queue.size());

        FairFarmerAreaQueue.Lease current = queue.poll();
        assertTrue(queue.complete(current, 0, false));
        assertEquals(0, queue.size());
        assertNull(queue.poll());
    }

    @Test
    void aSmallCycleBudgetEventuallyVisitsThousandsOfAreas() {
        FairFarmerAreaQueue queue = new FairFarmerAreaQueue();
        int areaCount = 5_000;
        int cycleBudget = 7;
        for (int index = 0; index < areaCount; index++) {
            queue.register("island-" + index);
        }

        Set<String> visited = new HashSet<>();
        int cycles = (areaCount + cycleBudget - 1) / cycleBudget;
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (int slot = 0; slot < cycleBudget && visited.size() < areaCount; slot++) {
                FairFarmerAreaQueue.Lease lease = queue.poll();
                visited.add(lease.regionId());
                queue.complete(lease, lease.cursor() + 1, true);
            }
        }

        assertEquals(areaCount, visited.size());
        assertEquals(areaCount, queue.size());
    }
}
