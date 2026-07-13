package xyz.geik.farmer.modules.autoharvest.tracking;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.junit.jupiter.api.Test;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkCropScannerTest {

    @Test
    void findsMatureCropsAndSkipsEmptySections() {
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        Ageable wheat = mock(Ageable.class);
        when(snapshot.isSectionEmpty(1)).thenReturn(false);
        when(snapshot.isSectionEmpty(0)).thenReturn(true);
        when(snapshot.getBlockType(3, 20, 5)).thenReturn(Material.WHEAT);
        when(snapshot.getBlockData(3, 20, 5)).thenReturn(wheat);
        when(wheat.getAge()).thenReturn(7);
        when(wheat.getMaximumAge()).thenReturn(7);

        ChunkCropScanner.ScanResult result = ChunkCropScanner.scan(
                snapshot, 0, 32, Map.of(Material.WHEAT, XMaterial.WHEAT), 64);

        assertTrue(result.containsCrop());
        assertEquals(1, result.candidates().size());
        assertEquals(XMaterial.WHEAT, result.candidates().getFirst().material());
    }

    @Test
    void keepsImmatureCropChunksTrackedWithoutHarvesting() {
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        Ageable wheat = mock(Ageable.class);
        when(snapshot.getBlockType(3, 20, 5)).thenReturn(Material.WHEAT);
        when(snapshot.getBlockData(3, 20, 5)).thenReturn(wheat);
        when(wheat.getAge()).thenReturn(4);
        when(wheat.getMaximumAge()).thenReturn(7);

        ChunkCropScanner.ScanResult result = ChunkCropScanner.scan(
                snapshot, 0, 32, Map.of(Material.WHEAT, XMaterial.WHEAT), 64);

        assertTrue(result.containsCrop());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void preservesTheBaseOfStackingCrops() {
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        when(snapshot.getBlockType(4, 10, 4)).thenReturn(Material.SUGAR_CANE);
        when(snapshot.getBlockType(4, 11, 4)).thenReturn(Material.SUGAR_CANE);

        ChunkCropScanner.ScanResult result = ChunkCropScanner.scan(
                snapshot, 0, 16, Map.of(Material.SUGAR_CANE, XMaterial.SUGAR_CANE), 64);

        assertTrue(result.containsCrop());
        assertEquals(1, result.candidates().size());
        assertEquals(11, result.candidates().getFirst().y());
    }

    @Test
    void respectsThePerSnapshotCandidateBound() {
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        when(snapshot.getBlockType(1, 10, 1)).thenReturn(Material.PUMPKIN);
        when(snapshot.getBlockType(2, 10, 2)).thenReturn(Material.PUMPKIN);

        ChunkCropScanner.ScanResult result = ChunkCropScanner.scan(
                snapshot, 0, 16, Map.of(Material.PUMPKIN, XMaterial.PUMPKIN), 1);

        assertTrue(result.containsCrop());
        assertEquals(1, result.candidates().size());
        assertFalse(result.candidates().isEmpty());
    }
}
