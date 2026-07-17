package xyz.geik.farmer.modules.autoharvest.compat;

import org.bukkit.Chunk;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cached optional bridge to SuperiorSkyblock's loaded-island chunk API. */
public final class SuperiorFarmerAreaAccess {

    private static final Accessor ACCESSOR = Accessor.resolve();

    private SuperiorFarmerAreaAccess() {
    }

    public static @NotNull Optional<LoadedFarmerArea> findLoadedArea(@NotNull UUID playerId) {
        if (!FarmerRegionAccess.isSuperiorIntegrationActive() || ACCESSOR == null) {
            return Optional.empty();
        }
        return ACCESSOR.find(playerId);
    }

    public record LoadedFarmerArea(@NotNull String regionId, @NotNull List<Chunk> chunks) {
    }

    private record Accessor(
            Method getPlayer,
            Method getIsland,
            Method getUniqueId,
            Method getLoadedChunks
    ) {
        private static Accessor resolve() {
            try {
                ClassLoader loader = SuperiorFarmerAreaAccess.class.getClassLoader();
                Class<?> api = Class.forName(
                        "com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI", false, loader);
                Class<?> player = Class.forName(
                        "com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer", false, loader);
                Class<?> island = Class.forName(
                        "com.bgsoftware.superiorskyblock.api.island.Island", false, loader);
                return new Accessor(
                        api.getMethod("getPlayer", UUID.class),
                        player.getMethod("getIsland"),
                        island.getMethod("getUniqueId"),
                        island.getMethod("getLoadedChunks")
                );
            }
            catch (ClassNotFoundException | NoSuchMethodException exception) {
                return null;
            }
        }

        private Optional<LoadedFarmerArea> find(UUID playerId) {
            try {
                Object superiorPlayer = getPlayer.invoke(null, playerId);
                Object island = superiorPlayer == null ? null : getIsland.invoke(superiorPlayer);
                if (island == null) {
                    return Optional.empty();
                }
                Object rawRegionId = getUniqueId.invoke(island);
                Object rawChunks = getLoadedChunks.invoke(island);
                if (!(rawRegionId instanceof UUID regionId) || !(rawChunks instanceof List<?> chunks)) {
                    return Optional.empty();
                }
                List<Chunk> loadedChunks = new ArrayList<>(chunks.size());
                for (Object chunk : chunks) {
                    if (chunk instanceof Chunk loaded) {
                        loadedChunks.add(loaded);
                    }
                }
                return Optional.of(new LoadedFarmerArea(regionId.toString(), List.copyOf(loadedChunks)));
            }
            catch (IllegalAccessException | InvocationTargetException exception) {
                Throwable cause = exception instanceof InvocationTargetException invocation
                        && invocation.getCause() != null ? invocation.getCause() : exception;
                throw new IllegalStateException("Unable to read SuperiorSkyblock's loaded Farmer chunks.", cause);
            }
        }
    }
}
