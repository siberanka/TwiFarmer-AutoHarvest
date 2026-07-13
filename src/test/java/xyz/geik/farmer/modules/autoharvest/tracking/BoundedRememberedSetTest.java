package xyz.geik.farmer.modules.autoharvest.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedRememberedSetTest {

    @Test
    void evictsTheOldestChunkAndConsumesRestoredEntries() {
        BoundedRememberedSet<String> remembered = new BoundedRememberedSet<>();
        remembered.remember("oldest", 2);
        remembered.remember("middle", 2);
        remembered.remember("newest", 2);

        assertEquals(2, remembered.size());
        assertFalse(remembered.take("oldest"));
        assertTrue(remembered.take("middle"));
        assertFalse(remembered.take("middle"));
        assertTrue(remembered.take("newest"));
        assertEquals(0, remembered.size());
    }
}
