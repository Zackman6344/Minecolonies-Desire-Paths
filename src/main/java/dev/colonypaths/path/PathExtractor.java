package dev.colonypaths.path;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildingextensions.IBuildingExtension;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.IBuildingModule;
import com.minecolonies.core.colony.buildingextensions.FarmField;
import com.minecolonies.core.colony.buildings.modules.BuildingExtensionsModule;
import dev.colonypaths.ColonyPaths;
import dev.colonypaths.heatmap.HeatMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Tuple;

import java.util.Arrays;

// Extracts a 1-block-wide path skeleton from the heatmap via threshold + Zhang-Suen thinning.
//
// Output is a boolean mask in local coords (dx+radius, dz+radius). True == path cell.
// This is the upstream half of the desire-path pipeline: turn a 2D footfall density grid
// into a connected 1-cell-wide line that traces the route citizens actually walk.
//
// Future iterations will trace the mask into ordered segments (List<List<BlockPos>>) so
// each segment can become a blueprint with correct block orientation along its direction.
public final class PathExtractor {

    public static final class Skeleton {
        public final BlockPos center;
        public final int radius;
        public final boolean[][] mask;  // [dx + radius][dz + radius]
        public final int threshold;
        public final int peakHeat;
        public final int cellCount;
        public final int maskedBuildingCells;
        public final int maskedBuildings;
        public final int maskedFieldCells;       // cells from field/pasture/plantation extensions
        public final int maskedFields;

        Skeleton(BlockPos center, int radius, boolean[][] mask, int threshold, int peakHeat,
                int maskedBuildingCells, int maskedBuildings,
                int maskedFieldCells, int maskedFields) {
            this.center = center;
            this.radius = radius;
            this.mask = mask;
            this.threshold = threshold;
            this.peakHeat = peakHeat;
            this.maskedBuildingCells = maskedBuildingCells;
            this.maskedBuildings = maskedBuildings;
            this.maskedFieldCells = maskedFieldCells;
            this.maskedFields = maskedFields;
            int n = 0;
            for (boolean[] row : mask) for (boolean b : row) if (b) n++;
            this.cellCount = n;
        }

        public boolean isPath(int dx, int dz) {
            int x = dx + radius;
            int z = dz + radius;
            int side = 2 * radius + 1;
            if (x < 0 || x >= side || z < 0 || z >= side) return false;
            return mask[x][z];
        }
    }

    // Default radius for non-FarmField extensions (PlantationField, etc.) whose precise
    // bounds we don't easily reach. 6 blocks comfortably covers a plantation plot.
    private static final int DEFAULT_FIELD_HALF_EXTENT = 6;

    // Reasonable default threshold for raw footfall counts. We want to capture obvious paths
    // without filling the map with noise; peak/16 + floor of 2 is a sane starting heuristic.
    public static int defaultThreshold(int peakHeat) {
        return Math.max(2, peakHeat / 16);
    }

    public static Skeleton extract(BlockPos center, int radius, int threshold) {
        return extract(center, radius, threshold, true);
    }

    // When maskBuildings is true, cells inside any building's claimed bounds
    // (Minecolonies' IBuilding.getCorners()) AND inside any field/plantation extension's
    // working area are excluded BEFORE thresholding. This kills:
    //   1. Interior-occupancy density (citizens idling/working/sleeping inside huts)
    //   2. Field-pacing density (farmers walking laps inside their FarmField, plantation
    //      workers cycling through their plot)
    // What survives the mask should be actual movement between these areas — the desire-path
    // signal we care about.
    public static Skeleton extract(BlockPos center, int radius, int threshold, boolean maskBuildings) {
        int side = 2 * radius + 1;
        boolean[][] mask = new boolean[side][side];
        boolean[][] excluded = new boolean[side][side];
        int[] counters = new int[4];  // [0]=buildingCells [1]=buildings [2]=fieldCells [3]=fields

        if (maskBuildings) {
            int originX = center.getX() - radius;
            int originZ = center.getZ() - radius;
            try {
                for (IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getAllColonies()) {
                    for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                        addBuildingMask(building, excluded, side, originX, originZ, counters);
                        addExtensionMasks(building, excluded, side, originX, originZ, counters);
                    }
                }
            } catch (Throwable t) {
                ColonyPaths.LOGGER.warn("Mask step failed; continuing with whatever was masked so far: {}", t.toString());
            }
        }

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int lx = dx + radius;
                int lz = dz + radius;
                if (excluded[lx][lz]) continue;
                int count = HeatMap.get().countAt(center.getX() + dx, center.getZ() + dz);
                if (count >= threshold) mask[lx][lz] = true;
            }
        }

        zhangSuen(mask, side);
        return new Skeleton(center, radius, mask, threshold, HeatMap.get().peak(),
                counters[0], counters[1], counters[2], counters[3]);
    }

    private static void addBuildingMask(IBuilding building, boolean[][] excluded,
                                        int side, int originX, int originZ, int[] counters) {
        Tuple<BlockPos, BlockPos> corners = building.getCorners();
        if (corners == null) return;
        BlockPos a = corners.getA();
        BlockPos b = corners.getB();
        if (a == null || b == null) return;

        int bMinX = Math.min(a.getX(), b.getX());
        int bMaxX = Math.max(a.getX(), b.getX());
        int bMinZ = Math.min(a.getZ(), b.getZ());
        int bMaxZ = Math.max(a.getZ(), b.getZ());

        if (markRect(excluded, side, originX, originZ, bMinX, bMaxX, bMinZ, bMaxZ, counters, 0)) {
            counters[1]++;
        }
    }

    // Walks the building's modules for BuildingExtensionsModule instances (farmers, plantation,
    // shepherds, etc.). For each owned extension, masks the cells the extension claims.
    // FarmField exposes per-direction radii so we use those exactly; everything else falls
    // back to a square around the extension's position.
    private static void addExtensionMasks(IBuilding building, boolean[][] excluded,
                                          int side, int originX, int originZ, int[] counters) {
        for (IBuildingModule module : building.getModules()) {
            if (!(module instanceof BuildingExtensionsModule extMod)) continue;
            for (IBuildingExtension ext : extMod.getOwnedExtensions()) {
                BlockPos p = ext.getPosition();
                if (p == null) continue;

                int minX, maxX, minZ, maxZ;
                if (ext instanceof FarmField farm) {
                    // Direction semantics: NORTH=-Z, SOUTH=+Z, EAST=+X, WEST=-X
                    minX = p.getX() - farm.getRadius(Direction.WEST);
                    maxX = p.getX() + farm.getRadius(Direction.EAST);
                    minZ = p.getZ() - farm.getRadius(Direction.NORTH);
                    maxZ = p.getZ() + farm.getRadius(Direction.SOUTH);
                } else {
                    int h = DEFAULT_FIELD_HALF_EXTENT;
                    minX = p.getX() - h;
                    maxX = p.getX() + h;
                    minZ = p.getZ() - h;
                    maxZ = p.getZ() + h;
                }

                if (markRect(excluded, side, originX, originZ, minX, maxX, minZ, maxZ, counters, 2)) {
                    counters[3]++;
                }
            }
        }
    }

    // Marks the (clamped) rectangle of world-space coords into the local mask. Counts each
    // newly-masked cell in counters[cellSlot]. Returns true if at least one new cell was added.
    private static boolean markRect(boolean[][] excluded, int side, int originX, int originZ,
                                    int wMinX, int wMaxX, int wMinZ, int wMaxZ,
                                    int[] counters, int cellSlot) {
        int lMinX = Math.max(0, wMinX - originX);
        int lMaxX = Math.min(side - 1, wMaxX - originX);
        int lMinZ = Math.max(0, wMinZ - originZ);
        int lMaxZ = Math.min(side - 1, wMaxZ - originZ);
        if (lMinX > lMaxX || lMinZ > lMaxZ) return false;

        boolean touched = false;
        for (int x = lMinX; x <= lMaxX; x++) {
            for (int z = lMinZ; z <= lMaxZ; z++) {
                if (!excluded[x][z]) {
                    excluded[x][z] = true;
                    counters[cellSlot]++;
                    touched = true;
                }
            }
        }
        return touched;
    }

    // Zhang-Suen iterative thinning. Two sub-iterations per pass; repeat until no pixels removed.
    // Reference: Zhang & Suen 1984, "A fast parallel algorithm for thinning digital patterns."
    private static void zhangSuen(boolean[][] mask, int side) {
        boolean[][] toDelete = new boolean[side][side];
        while (true) {
            boolean changed = false;

            // Sub-iteration 1
            clear(toDelete);
            for (int x = 1; x < side - 1; x++) {
                for (int z = 1; z < side - 1; z++) {
                    if (!mask[x][z]) continue;
                    int b = nonZeroNeighbors(mask, x, z);
                    if (b < 2 || b > 6) continue;
                    if (transitions(mask, x, z) != 1) continue;
                    if (mask[x][z - 1] && mask[x + 1][z] && mask[x][z + 1]) continue;
                    if (mask[x + 1][z] && mask[x][z + 1] && mask[x - 1][z]) continue;
                    toDelete[x][z] = true;
                    changed = true;
                }
            }
            apply(mask, toDelete);

            // Sub-iteration 2
            clear(toDelete);
            for (int x = 1; x < side - 1; x++) {
                for (int z = 1; z < side - 1; z++) {
                    if (!mask[x][z]) continue;
                    int b = nonZeroNeighbors(mask, x, z);
                    if (b < 2 || b > 6) continue;
                    if (transitions(mask, x, z) != 1) continue;
                    if (mask[x][z - 1] && mask[x + 1][z] && mask[x - 1][z]) continue;
                    if (mask[x][z - 1] && mask[x][z + 1] && mask[x - 1][z]) continue;
                    toDelete[x][z] = true;
                    changed = true;
                }
            }
            apply(mask, toDelete);

            if (!changed) break;
        }
    }

    private static int nonZeroNeighbors(boolean[][] mask, int x, int z) {
        int n = 0;
        if (mask[x][z - 1]) n++;       // p2 N
        if (mask[x + 1][z - 1]) n++;   // p3 NE
        if (mask[x + 1][z]) n++;       // p4 E
        if (mask[x + 1][z + 1]) n++;   // p5 SE
        if (mask[x][z + 1]) n++;       // p6 S
        if (mask[x - 1][z + 1]) n++;   // p7 SW
        if (mask[x - 1][z]) n++;       // p8 W
        if (mask[x - 1][z - 1]) n++;   // p9 NW
        return n;
    }

    // Count of 0→1 transitions traversing the neighbor cycle p2,p3,...,p9,p2.
    private static int transitions(boolean[][] mask, int x, int z) {
        boolean[] p = {
                mask[x][z - 1],
                mask[x + 1][z - 1],
                mask[x + 1][z],
                mask[x + 1][z + 1],
                mask[x][z + 1],
                mask[x - 1][z + 1],
                mask[x - 1][z],
                mask[x - 1][z - 1]
        };
        int t = 0;
        for (int i = 0; i < 8; i++) {
            if (!p[i] && p[(i + 1) % 8]) t++;
        }
        return t;
    }

    private static void clear(boolean[][] arr) {
        for (boolean[] row : arr) Arrays.fill(row, false);
    }

    private static void apply(boolean[][] mask, boolean[][] toDelete) {
        for (int x = 0; x < mask.length; x++) {
            for (int z = 0; z < mask[x].length; z++) {
                if (toDelete[x][z]) mask[x][z] = false;
            }
        }
    }
}
