package xyz.geik.farmer.modules.autoharvest.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairScanQueueTest {

    @Test
    void prioritizesCropPressureWithoutStarvingDiscovery() {
        FairScanQueue<String> queue = new FairScanQueue<>();
        queue.offer("normal-a", 0, 10);
        queue.offer("normal-b", 0, 10);
        queue.offer("crop-low", 5, 10);
        queue.offer("crop-high", 100, 10);
        queue.offer("crop-medium", 20, 10);

        assertEquals("crop-high", queue.poll(2));
        queue.complete("crop-high");
        assertEquals("crop-medium", queue.poll(2));
        queue.complete("crop-medium");
        assertEquals("normal-a", queue.poll(2));
        queue.complete("normal-a");
        assertEquals("crop-low", queue.poll(2));
        queue.complete("crop-low");
        assertEquals("normal-b", queue.poll(2));
    }

    @Test
    void promotesAQueuedDiscoveryWithoutDuplicatingItsState() {
        FairScanQueue<String> queue = new FairScanQueue<>();
        assertEquals(FairScanQueue.OfferResult.ENQUEUED, queue.offer("chunk", 0, 1));
        assertEquals(FairScanQueue.OfferResult.PROMOTED, queue.offer("chunk", 64, 1));
        assertEquals(1, queue.size());
        assertEquals("chunk", queue.poll(3));
        assertNull(queue.poll(3));
        assertTrue(queue.complete("chunk"));
        assertFalse(queue.contains("chunk"));
    }

    @Test
    void activeAndBoundedEntriesCannotFloodTheQueue() {
        FairScanQueue<String> queue = new FairScanQueue<>();
        assertEquals(FairScanQueue.OfferResult.ENQUEUED, queue.offer("active", 0, 1));
        assertEquals("active", queue.poll(3));
        assertEquals(FairScanQueue.OfferResult.DUPLICATE, queue.offer("active", 100, 1));
        assertEquals(FairScanQueue.OfferResult.FULL, queue.offer("other", 0, 1));
        assertFalse(queue.hasQueued());
    }
}
