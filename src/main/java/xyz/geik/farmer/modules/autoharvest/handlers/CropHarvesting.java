package xyz.geik.farmer.modules.autoharvest.handlers;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Region-local crop classification and mutation helpers.
 *
 * @author poyraz
 * @author siberanka
 * @since 1.1.0
 */
public final class CropHarvesting {

    private static final Map<Material, XMaterial> BLOCK_MATERIALS = Map.ofEntries(
            Map.entry(Material.WHEAT, XMaterial.WHEAT),
            Map.entry(Material.CARROTS, XMaterial.CARROT),
            Map.entry(Material.POTATOES, XMaterial.POTATO),
            Map.entry(Material.BEETROOTS, XMaterial.BEETROOT),
            Map.entry(Material.SWEET_BERRY_BUSH, XMaterial.SWEET_BERRIES),
            Map.entry(Material.NETHER_WART, XMaterial.NETHER_WART),
            Map.entry(Material.COCOA, XMaterial.COCOA_BEANS),
            Map.entry(Material.SUGAR_CANE, XMaterial.SUGAR_CANE),
            Map.entry(Material.MELON, XMaterial.MELON_SLICE),
            Map.entry(Material.PUMPKIN, XMaterial.PUMPKIN),
            Map.entry(Material.CACTUS, XMaterial.CACTUS),
            Map.entry(Material.BAMBOO, XMaterial.BAMBOO),
            Map.entry(Material.KELP, XMaterial.KELP),
            Map.entry(Material.KELP_PLANT, XMaterial.KELP),
            Map.entry(Material.CHORUS_FLOWER, XMaterial.CHORUS_FLOWER),
            Map.entry(Material.CHORUS_PLANT, XMaterial.CHORUS_PLANT)
    );

    private CropHarvesting() {
    }

    public static @NotNull XMaterial normalize(@NotNull XMaterial material) {
        return switch (material.name()) {
            case "BEETROOTS" -> XMaterial.BEETROOT;
            case "POTATOES" -> XMaterial.POTATO;
            case "CARROTS" -> XMaterial.CARROT;
            case "SWEET_BERRY_BUSH" -> XMaterial.SWEET_BERRIES;
            case "MELON" -> XMaterial.MELON_SLICE;
            case "COCOA" -> XMaterial.COCOA_BEANS;
            case "KELP_PLANT" -> XMaterial.KELP;
            default -> material;
        };
    }

    public static boolean isSupportedCrop(@NotNull XMaterial material) {
        return isAgeableCrop(material) || isBlockCrop(material);
    }

    public static @Nullable XMaterial resolveBlockMaterial(@NotNull Material material) {
        return BLOCK_MATERIALS.get(material);
    }

    public static @NotNull Map<Material, XMaterial> configuredBlockMaterials(
            @NotNull Set<XMaterial> configuredCrops
    ) {
        if (configuredCrops.isEmpty()) {
            return Collections.emptyMap();
        }
        EnumMap<Material, XMaterial> mapped = new EnumMap<>(Material.class);
        BLOCK_MATERIALS.forEach((material, crop) -> {
            if (configuredCrops.contains(crop)) {
                mapped.put(material, crop);
            }
        });
        return mapped.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(mapped);
    }

    public static boolean isHarvestableGrowth(@NotNull BlockData data, @NotNull XMaterial material) {
        if (isBlockCrop(material)) {
            return true;
        }
        return isAgeableCrop(material)
                && data instanceof Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge();
    }

    static boolean isStillHarvestable(@NotNull Block block, @NotNull XMaterial expectedMaterial) {
        XMaterial current = resolveBlockMaterial(block.getType());
        if (current != expectedMaterial || !isHarvestableGrowth(block.getBlockData(), current)) {
            return false;
        }
        return !isStackCrop(current)
                || (isSameCrop(block.getRelative(BlockFace.DOWN), current)
                && !isSameCrop(block.getRelative(BlockFace.UP), current));
    }

    public static boolean isSnapshotHarvestable(
            @NotNull ChunkSnapshot snapshot,
            int x,
            int y,
            int z,
            @NotNull XMaterial material,
            int minimumY,
            int maximumY
    ) {
        if (!isHarvestableGrowth(snapshot.getBlockData(x, y, z), material)) {
            return false;
        }
        return !isStackCrop(material)
                || (y > minimumY
                && isSameCrop(snapshot, x, y - 1, z, material)
                && (y + 1 >= maximumY
                || !isSameCrop(snapshot, x, y + 1, z, material)));
    }

    static @NotNull List<ItemStack> snapshotDrops(@NotNull Block block) {
        Collection<ItemStack> rawDrops = block.getDrops();
        List<ItemStack> drops = new ArrayList<>(rawDrops.size());
        for (ItemStack drop : rawDrops) {
            if (drop != null && !isAir(drop.getType()) && drop.getAmount() > 0) {
                drops.add(drop.clone());
            }
        }
        return drops;
    }

    static boolean harvest(@NotNull Block block, @NotNull XMaterial material,
                           @NotNull Collection<ItemStack> drops) {
        if (!isStillHarvestable(block, material)) {
            return false;
        }

        BlockData blockData = block.getBlockData();
        if (blockData instanceof Ageable ageable && isAgeableCrop(material)) {
            ageable.setAge(0);
            block.setBlockData(ageable, false);
        }
        else if (isBlockCrop(material)) {
            block.setType(Material.AIR, false);
        }
        else {
            return false;
        }

        for (ItemStack drop : drops) {
            block.getWorld().dropItemNaturally(block.getLocation(), drop);
        }
        return true;
    }

    static boolean harvestStacked(@NotNull Block top, @NotNull XMaterial material, int maximumSegments) {
        List<Block> segments = stackedSegments(top, material, maximumSegments);
        if (segments.isEmpty()) {
            return false;
        }

        List<StackedSegment> plan = new ArrayList<>(segments.size());
        for (Block segment : segments) {
            if (!isSameCrop(segment, material)) {
                return false;
            }
            List<ItemStack> drops = snapshotDrops(segment);
            if (drops.isEmpty()) {
                ItemStack fallback = material.parseItem();
                if (fallback != null && !isAir(fallback.getType())) {
                    drops.add(fallback);
                }
            }
            plan.add(new StackedSegment(segment, drops));
        }

        // Mutate and pay each segment exactly once. A later failure can leave
        // unprocessed growth in place, but can never award its drops early.
        for (StackedSegment segment : plan) {
            if (!isSameCrop(segment.block(), material)) {
                return false;
            }
            segment.block().setType(Material.AIR, false);
            if (!isAir(segment.block().getType())) {
                return false;
            }
            for (ItemStack drop : segment.drops()) {
                segment.block().getWorld().dropItemNaturally(segment.block().getLocation(), drop);
            }
        }
        restoreStackBase(segments.getLast().getRelative(BlockFace.DOWN), material);
        return true;
    }

    static @NotNull List<Block> stackedSegments(
            @NotNull Block top,
            @NotNull XMaterial material,
            int maximumSegments
    ) {
        if (!isStackCrop(material) || maximumSegments <= 0
                || !isSameCrop(top, material)
                || isSameCrop(top.getRelative(BlockFace.UP), material)) {
            return List.of();
        }

        List<Block> harvestable = new ArrayList<>(Math.min(maximumSegments, 16));
        Block cursor = top;
        while (isSameCrop(cursor, material)) {
            Block below = cursor.getRelative(BlockFace.DOWN);
            if (!isSameCrop(below, material)) {
                return List.copyOf(harvestable);
            }
            if (harvestable.size() >= maximumSegments) {
                return List.of();
            }
            harvestable.add(cursor);
            cursor = below;
        }
        return List.of();
    }

    static boolean isBlockCrop(@NotNull XMaterial material) {
        return switch (material.name()) {
            case "SUGAR_CANE", "MELON_SLICE", "PUMPKIN", "CACTUS", "BAMBOO", "KELP",
                    "CHORUS_FLOWER", "CHORUS_PLANT" -> true;
            default -> false;
        };
    }

    public static boolean isStackCrop(@NotNull XMaterial material) {
        return switch (material.name()) {
            case "SUGAR_CANE", "CACTUS", "BAMBOO", "KELP" -> true;
            default -> false;
        };
    }

    static boolean isAgeableCrop(@NotNull XMaterial material) {
        return switch (material.name()) {
            case "WHEAT", "CARROT", "POTATO", "BEETROOT", "SWEET_BERRIES",
                    "NETHER_WART", "COCOA_BEANS" -> true;
            default -> false;
        };
    }

    private static boolean isSameCrop(@NotNull Block block, @NotNull XMaterial expected) {
        return resolveBlockMaterial(block.getType()) == expected;
    }

    private static boolean isAir(@Nullable Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static void restoreStackBase(@NotNull Block base, @NotNull XMaterial material) {
        if (material == XMaterial.KELP && base.getType() == Material.KELP_PLANT) {
            base.setType(Material.KELP, false);
        }
    }

    private static boolean isSameCrop(
            @NotNull ChunkSnapshot snapshot,
            int x,
            int y,
            int z,
            @NotNull XMaterial expected
    ) {
        Material material = snapshot.getBlockType(x, y, z);
        return material != null && resolveBlockMaterial(material) == expected;
    }

    private record StackedSegment(@NotNull Block block, @NotNull List<ItemStack> drops) {
    }
}
