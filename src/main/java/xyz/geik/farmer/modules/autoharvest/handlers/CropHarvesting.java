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
                || (block.getRelative(BlockFace.DOWN).getType() == block.getType()
                && block.getRelative(BlockFace.UP).getType() != block.getType());
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
                && snapshot.getBlockType(x, y - 1, z) == snapshot.getBlockType(x, y, z)
                && (y + 1 >= maximumY
                || snapshot.getBlockType(x, y + 1, z) != snapshot.getBlockType(x, y, z)));
    }

    static @NotNull List<ItemStack> snapshotDrops(@NotNull Block block) {
        Collection<ItemStack> rawDrops = block.getDrops();
        List<ItemStack> drops = new ArrayList<>(rawDrops.size());
        for (ItemStack drop : rawDrops) {
            if (drop != null && !drop.getType().isAir() && drop.getAmount() > 0) {
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

    static boolean isBlockCrop(@NotNull XMaterial material) {
        return switch (material.name()) {
            case "SUGAR_CANE", "MELON_SLICE", "PUMPKIN", "CACTUS",
                    "CHORUS_FLOWER", "CHORUS_PLANT" -> true;
            default -> false;
        };
    }

    private static boolean isStackCrop(@NotNull XMaterial material) {
        return material == XMaterial.SUGAR_CANE || material == XMaterial.CACTUS;
    }

    static boolean isAgeableCrop(@NotNull XMaterial material) {
        return switch (material.name()) {
            case "WHEAT", "CARROT", "POTATO", "BEETROOT", "SWEET_BERRIES",
                    "NETHER_WART", "COCOA_BEANS" -> true;
            default -> false;
        };
    }
}
