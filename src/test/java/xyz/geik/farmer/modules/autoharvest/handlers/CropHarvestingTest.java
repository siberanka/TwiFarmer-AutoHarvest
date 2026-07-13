package xyz.geik.farmer.modules.autoharvest.handlers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CropHarvestingTest {

    @Test
    void normalizesBlockMaterialsToFarmerItems() {
        assertTrue(CropHarvesting.normalize(XMaterial.CARROTS) == XMaterial.CARROT);
        assertTrue(CropHarvesting.normalize(XMaterial.POTATOES) == XMaterial.POTATO);
        assertTrue(CropHarvesting.normalize(XMaterial.BEETROOTS) == XMaterial.BEETROOT);
        assertTrue(CropHarvesting.normalize(XMaterial.MELON) == XMaterial.MELON_SLICE);
        assertTrue(CropHarvesting.normalize(XMaterial.COCOA) == XMaterial.COCOA_BEANS);
    }

    @Test
    void buildsConfiguredCropMapWithoutParsingLegacyMaterials() {
        Map<Material, XMaterial> materials = assertDoesNotThrow(() ->
                CropHarvesting.configuredBlockMaterials(Set.of(XMaterial.WHEAT, XMaterial.CARROT)));

        assertEquals(XMaterial.WHEAT, materials.get(Material.WHEAT));
        assertEquals(XMaterial.CARROT, materials.get(Material.CARROTS));
        assertFalse(materials.containsKey(Material.POTATOES));
        Material legacyAir = Material.getMaterial("LEGACY_AIR");
        assertNotNull(legacyAir);
        assertNull(CropHarvesting.resolveBlockMaterial(legacyAir));
    }

    @Test
    void onlyTreatsMaximumAgeAsHarvestable() {
        Ageable ageable = mock(Ageable.class);
        when(ageable.getMaximumAge()).thenReturn(7);
        when(ageable.getAge()).thenReturn(6, 7);

        assertFalse(CropHarvesting.isHarvestableGrowth(ageable, XMaterial.WHEAT));
        assertTrue(CropHarvesting.isHarvestableGrowth(ageable, XMaterial.WHEAT));
    }

    @Test
    void matureCropDropsOnceAndIsReplanted() {
        Block block = mock(Block.class);
        Ageable ageable = mock(Ageable.class);
        World world = mock(World.class);
        Location location = mock(Location.class);
        ItemStack wheat = mock(ItemStack.class);

        when(block.getType()).thenReturn(Material.WHEAT);
        when(block.getBlockData()).thenReturn(ageable);
        when(ageable.getAge()).thenReturn(7);
        when(ageable.getMaximumAge()).thenReturn(7);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(location);

        assertTrue(CropHarvesting.harvest(block, XMaterial.WHEAT, List.of(wheat)));
        verify(ageable).setAge(0);
        verify(block).setBlockData(ageable, false);
        verify(world).dropItemNaturally(location, wheat);
        verify(block, never()).setType(Material.AIR, false);
    }

    @Test
    void staleDelayedTaskCannotHarvestAnImmatureCrop() {
        Block block = mock(Block.class);
        Ageable ageable = mock(Ageable.class);
        ItemStack wheat = mock(ItemStack.class);

        when(block.getType()).thenReturn(Material.WHEAT);
        when(block.getBlockData()).thenReturn(ageable);
        when(ageable.getAge()).thenReturn(2);
        when(ageable.getMaximumAge()).thenReturn(7);

        assertFalse(CropHarvesting.harvest(block, XMaterial.WHEAT, List.of(wheat)));
        verify(ageable, never()).setAge(0);
        verify(block, never()).setBlockData(ageable, false);
    }

    @Test
    void fruitGrowthIsRemovedAfterItsDropsAreCaptured() {
        Block block = mock(Block.class);
        BlockData blockData = mock(BlockData.class);
        World world = mock(World.class);
        Location location = mock(Location.class);
        ItemStack slices = mock(ItemStack.class);

        when(block.getType()).thenReturn(Material.MELON);
        when(block.getBlockData()).thenReturn(blockData);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(location);

        assertTrue(CropHarvesting.harvest(block, XMaterial.MELON_SLICE, List.of(slices)));
        verify(block).setType(Material.AIR, false);
        verify(world).dropItemNaturally(location, slices);
    }

    @Test
    void onlyTheTopOfAStackingCropIsHarvestable() {
        Block cane = mock(Block.class);
        Block below = mock(Block.class);
        Block above = mock(Block.class);
        BlockData data = mock(BlockData.class);

        when(cane.getType()).thenReturn(Material.SUGAR_CANE);
        when(cane.getBlockData()).thenReturn(data);
        when(cane.getRelative(BlockFace.DOWN)).thenReturn(below);
        when(cane.getRelative(BlockFace.UP)).thenReturn(above);
        when(below.getType()).thenReturn(Material.SUGAR_CANE);
        when(above.getType()).thenReturn(Material.SUGAR_CANE, Material.AIR);

        assertFalse(CropHarvesting.isStillHarvestable(cane, XMaterial.SUGAR_CANE));
        assertTrue(CropHarvesting.isStillHarvestable(cane, XMaterial.SUGAR_CANE));
    }

    @Test
    void stackedHarvestPreservesTheBaseAndPaysEachRemovedSegmentOnce() {
        Block top = statefulBlock(Material.BAMBOO);
        Block middle = statefulBlock(Material.BAMBOO);
        Block base = statefulBlock(Material.BAMBOO);
        Block soil = statefulBlock(Material.DIRT);
        Block air = statefulBlock(Material.AIR);
        World world = mock(World.class);
        Location topLocation = mock(Location.class);
        Location middleLocation = mock(Location.class);
        ItemStack topDrop = mock(ItemStack.class);
        ItemStack middleDrop = mock(ItemStack.class);

        when(top.getRelative(BlockFace.UP)).thenReturn(air);
        when(top.getRelative(BlockFace.DOWN)).thenReturn(middle);
        when(middle.getRelative(BlockFace.DOWN)).thenReturn(base);
        when(base.getRelative(BlockFace.DOWN)).thenReturn(soil);
        when(top.getDrops()).thenReturn(List.of(topDrop));
        when(middle.getDrops()).thenReturn(List.of(middleDrop));
        when(topDrop.getType()).thenReturn(Material.BAMBOO);
        when(middleDrop.getType()).thenReturn(Material.BAMBOO);
        when(topDrop.getAmount()).thenReturn(1);
        when(middleDrop.getAmount()).thenReturn(1);
        when(topDrop.clone()).thenReturn(topDrop);
        when(middleDrop.clone()).thenReturn(middleDrop);
        when(top.getWorld()).thenReturn(world);
        when(middle.getWorld()).thenReturn(world);
        when(top.getLocation()).thenReturn(topLocation);
        when(middle.getLocation()).thenReturn(middleLocation);

        assertTrue(CropHarvesting.harvestStacked(top, XMaterial.BAMBOO, 32));

        verify(top).setType(Material.AIR, false);
        verify(middle).setType(Material.AIR, false);
        verify(base, never()).setType(Material.AIR, false);
        verify(world).dropItemNaturally(topLocation, topDrop);
        verify(world).dropItemNaturally(middleLocation, middleDrop);
    }

    @Test
    void oversizedStackIsRejectedWithoutPartialMutation() {
        Block top = statefulBlock(Material.SUGAR_CANE);
        Block second = statefulBlock(Material.SUGAR_CANE);
        Block third = statefulBlock(Material.SUGAR_CANE);
        Block base = statefulBlock(Material.SUGAR_CANE);
        Block soil = statefulBlock(Material.SAND);
        Block air = statefulBlock(Material.AIR);

        when(top.getRelative(BlockFace.UP)).thenReturn(air);
        when(top.getRelative(BlockFace.DOWN)).thenReturn(second);
        when(second.getRelative(BlockFace.DOWN)).thenReturn(third);
        when(third.getRelative(BlockFace.DOWN)).thenReturn(base);
        when(base.getRelative(BlockFace.DOWN)).thenReturn(soil);

        assertFalse(CropHarvesting.harvestStacked(top, XMaterial.SUGAR_CANE, 2));
        verify(top, never()).setType(Material.AIR, false);
        verify(second, never()).setType(Material.AIR, false);
        verify(third, never()).setType(Material.AIR, false);
        verify(base, never()).setType(Material.AIR, false);
    }

    @Test
    void kelpBaseIsRestoredToAGrowableTip() {
        Block top = statefulBlock(Material.KELP);
        Block base = statefulBlock(Material.KELP_PLANT);
        Block soil = statefulBlock(Material.CLAY);
        Block air = statefulBlock(Material.AIR);
        World world = mock(World.class);
        Location location = mock(Location.class);
        ItemStack drop = mock(ItemStack.class);

        when(top.getRelative(BlockFace.UP)).thenReturn(air);
        when(top.getRelative(BlockFace.DOWN)).thenReturn(base);
        when(base.getRelative(BlockFace.DOWN)).thenReturn(soil);
        when(top.getDrops()).thenReturn(List.of(drop));
        when(drop.getType()).thenReturn(Material.KELP);
        when(drop.getAmount()).thenReturn(1);
        when(drop.clone()).thenReturn(drop);
        when(top.getWorld()).thenReturn(world);
        when(top.getLocation()).thenReturn(location);

        assertTrue(CropHarvesting.harvestStacked(top, XMaterial.KELP, 32));

        verify(top).setType(Material.AIR, false);
        verify(base).setType(Material.KELP, false);
        verify(base, never()).setType(Material.AIR, false);
        verify(world).dropItemNaturally(location, drop);
    }

    private Block statefulBlock(Material initialType) {
        Block block = mock(Block.class);
        AtomicReference<Material> type = new AtomicReference<>(initialType);
        when(block.getType()).thenAnswer(ignored -> type.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            type.set(invocation.getArgument(0));
            return null;
        }).when(block).setType(org.mockito.ArgumentMatchers.any(Material.class),
                org.mockito.ArgumentMatchers.anyBoolean());
        return block;
    }
}
