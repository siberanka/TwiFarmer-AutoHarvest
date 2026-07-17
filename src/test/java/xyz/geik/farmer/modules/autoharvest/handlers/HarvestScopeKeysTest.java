package xyz.geik.farmer.modules.autoharvest.handlers;

import org.junit.jupiter.api.Test;
import xyz.geik.farmer.modules.autoharvest.configuration.HarvestPacingScope;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HarvestScopeKeysTest {

    @Test
    void farmerAndRegionScopesNeverResolveTheOwner() {
        AtomicInteger ownerLookups = new AtomicInteger();

        assertEquals("farmer:island-id:7", HarvestScopeKeys.resolve(
                HarvestPacingScope.FARMER, "island-id", 7,
                () -> {
                    ownerLookups.incrementAndGet();
                    throw new NullPointerException();
                }, "chunk:key"));
        assertEquals("region:island-id", HarvestScopeKeys.resolve(
                HarvestPacingScope.REGION, "island-id", 7,
                () -> {
                    ownerLookups.incrementAndGet();
                    throw new NullPointerException();
                }, "chunk:key"));
        assertEquals(0, ownerLookups.get());
    }

    @Test
    void ownerScopeFallsBackToTheValidatedRegion() {
        assertEquals("region:island-id", HarvestScopeKeys.resolve(
                HarvestPacingScope.OWNER, "island-id", 7,
                () -> { throw new NullPointerException(); }, "chunk:key"));
        UUID ownerId = UUID.randomUUID();
        assertEquals("owner:" + ownerId, HarvestScopeKeys.resolve(
                HarvestPacingScope.OWNER, "island-id", 7, () -> ownerId, "chunk:key"));
    }
}
