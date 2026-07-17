package xyz.geik.farmer.modules.autoharvest.handlers;

import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.configuration.HarvestPacingScope;

import java.util.UUID;
import java.util.function.Supplier;

/** Builds pacing-only keys without unnecessary integration lookups. */
final class HarvestScopeKeys {

    private HarvestScopeKeys() {
    }

    static @NotNull String resolve(
            @NotNull HarvestPacingScope scope,
            @NotNull String regionId,
            int farmerId,
            @NotNull Supplier<UUID> ownerLookup,
            @NotNull String chunkScope
    ) {
        return switch (scope) {
            case OWNER -> owner(ownerLookup, regionId);
            case FARMER -> "farmer:" + regionId + ':' + farmerId;
            case REGION -> "region:" + regionId;
            case CHUNK -> chunkScope;
        };
    }

    private static String owner(Supplier<UUID> ownerLookup, String regionId) {
        try {
            UUID ownerId = ownerLookup.get();
            return ownerId == null ? "region:" + regionId : "owner:" + ownerId;
        }
        catch (RuntimeException exception) {
            return "region:" + regionId;
        }
    }
}
