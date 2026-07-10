package xyz.geik.farmer.modules.autoharvest.handlers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
