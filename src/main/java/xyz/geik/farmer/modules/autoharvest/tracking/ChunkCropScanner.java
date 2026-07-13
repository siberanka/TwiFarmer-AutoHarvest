package xyz.geik.farmer.modules.autoharvest.tracking;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.handlers.CropHarvesting;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Performs read-only crop discovery on an immutable chunk snapshot. */
public final class ChunkCropScanner {

    private ChunkCropScanner() {
    }

    public static @NotNull ScanResult scan(
            @NotNull ChunkSnapshot snapshot,
            int minimumY,
            int maximumY,
            @NotNull Map<Material, XMaterial> cropMaterials,
            int maximumCandidates
    ) {
        List<CropCandidate> candidates = new ArrayList<>(Math.min(maximumCandidates, 64));
        boolean containsCrop = false;
        int firstSection = Math.floorDiv(minimumY, 16);
        int lastSection = Math.floorDiv(maximumY - 1, 16);

        for (int section = lastSection; section >= firstSection; section--) {
            if (snapshot.isSectionEmpty(section)) {
                continue;
            }
            int sectionMinimum = Math.max(minimumY, section * 16);
            int sectionMaximum = Math.min(maximumY - 1, section * 16 + 15);
            for (int y = sectionMaximum; y >= sectionMinimum; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Material blockType = snapshot.getBlockType(x, y, z);
                        if (blockType == null) {
                            continue;
                        }
                        XMaterial crop = cropMaterials.get(blockType);
                        if (crop == null) {
                            continue;
                        }
                        containsCrop = true;
                        if (candidates.size() < maximumCandidates
                                && CropHarvesting.isSnapshotHarvestable(
                                snapshot, x, y, z, crop, minimumY, maximumY)) {
                            candidates.add(new CropCandidate(x, y, z, crop));
                        }
                    }
                }
            }
        }
        return new ScanResult(containsCrop, List.copyOf(candidates));
    }

    public record CropCandidate(int localX, int y, int localZ, @NotNull XMaterial material) {
    }

    public record ScanResult(boolean containsCrop, @NotNull List<CropCandidate> candidates) {
    }
}
