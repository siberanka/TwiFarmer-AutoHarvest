package xyz.geik.farmer.modules.autoharvest.tracking;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.handlers.CropHarvesting;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Performs bounded, resumable crop discovery on an immutable chunk snapshot. */
public final class ChunkCropScanner {

    private ChunkCropScanner() {
    }

    public static @NotNull ScanCursor cursor(int minimumY, int maximumY, int maximumCandidates) {
        return new ScanCursor(minimumY, maximumY, maximumCandidates);
    }

    public static @NotNull SliceResult scanSlice(
            @NotNull ChunkSnapshot snapshot,
            @NotNull Map<Material, XMaterial> cropMaterials,
            @NotNull ScanCursor cursor,
            int maximumBlockChecks
    ) {
        if (maximumBlockChecks <= 0 || cursor.complete) {
            return new SliceResult(cursor.complete, 0);
        }

        int checked = 0;
        while (cursor.y >= cursor.minimumY && checked < maximumBlockChecks) {
            int section = Math.floorDiv(cursor.y, 16);
            if (section != cursor.currentSection) {
                cursor.currentSection = section;
                if (snapshot.isSectionEmpty(section)) {
                    cursor.y = Math.max(cursor.minimumY - 1, section * 16 - 1);
                    cursor.x = 0;
                    cursor.z = 0;
                    continue;
                }
            }

            int x = cursor.x;
            int y = cursor.y;
            int z = cursor.z;
            advance(cursor);
            checked++;
            cursor.checkedBlocks++;

            Material blockType = snapshot.getBlockType(x, y, z);
            XMaterial crop = blockType == null ? null : cropMaterials.get(blockType);
            if (crop == null) {
                continue;
            }
            cursor.containsCrop = true;
            if (CropHarvesting.isSnapshotHarvestable(
                    snapshot, x, y, z, crop, cursor.minimumY, cursor.maximumY)) {
                cursor.candidates.add(new CropCandidate(x, y, z, crop));
                if (cursor.candidates.size() >= cursor.maximumCandidates) {
                    cursor.candidateLimitReached = true;
                    cursor.complete = true;
                    break;
                }
            }
        }

        if (cursor.y < cursor.minimumY) {
            cursor.complete = true;
        }
        return new SliceResult(cursor.complete, checked);
    }

    public static @NotNull ScanResult result(@NotNull ScanCursor cursor) {
        if (!cursor.complete) {
            throw new IllegalStateException("A partial crop scan has no final result.");
        }
        return new ScanResult(cursor.containsCrop, List.copyOf(cursor.candidates),
                cursor.checkedBlocks, cursor.candidateLimitReached);
    }

    public static @NotNull ScanResult scan(
            @NotNull ChunkSnapshot snapshot,
            int minimumY,
            int maximumY,
            @NotNull Map<Material, XMaterial> cropMaterials,
            int maximumCandidates
    ) {
        ScanCursor cursor = cursor(minimumY, maximumY, maximumCandidates);
        while (!scanSlice(snapshot, cropMaterials, cursor, 4_096).complete()) {
            // Compatibility helper for tests and bounded off-thread callers.
        }
        return result(cursor);
    }

    private static void advance(ScanCursor cursor) {
        if (++cursor.x < 16) {
            return;
        }
        cursor.x = 0;
        if (++cursor.z < 16) {
            return;
        }
        cursor.z = 0;
        cursor.y--;
    }

    public record CropCandidate(int localX, int y, int localZ, @NotNull XMaterial material) {
    }

    public record ScanResult(
            boolean containsCrop,
            @NotNull List<CropCandidate> candidates,
            long checkedBlocks,
            boolean candidateLimitReached
    ) {
    }

    public record SliceResult(boolean complete, int checkedBlocks) {
    }

    public static final class ScanCursor {
        private final int minimumY;
        private final int maximumY;
        private final int maximumCandidates;
        private final List<CropCandidate> candidates;
        private int x;
        private int y;
        private int z;
        private int currentSection = Integer.MIN_VALUE;
        private long checkedBlocks;
        private boolean containsCrop;
        private boolean candidateLimitReached;
        private boolean complete;

        private ScanCursor(int minimumY, int maximumY, int maximumCandidates) {
            if (maximumY <= minimumY) {
                throw new IllegalArgumentException("maximumY must be greater than minimumY");
            }
            if (maximumCandidates <= 0) {
                throw new IllegalArgumentException("maximumCandidates must be positive");
            }
            this.minimumY = minimumY;
            this.maximumY = maximumY;
            this.maximumCandidates = maximumCandidates;
            this.candidates = new ArrayList<>(Math.min(maximumCandidates, 64));
            this.y = maximumY - 1;
        }
    }
}
