package dev.colonypaths.heatmap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;

// Per-chunk 16x16 grid of footfall counts. Single-dimension for the spike — two colonies in
// different dimensions at the same chunk coords would collide. Server-thread access only,
// so no synchronization.
public final class HeatMap {
    private static final HeatMap INSTANCE = new HeatMap();
    public static HeatMap get() { return INSTANCE; }

    private final Map<Long, int[]> chunks = new HashMap<>();

    public void increment(BlockPos pos) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        int[] grid = chunks.computeIfAbsent(key, k -> new int[256]);
        int localX = pos.getX() & 0xF;
        int localZ = pos.getZ() & 0xF;
        grid[localZ * 16 + localX]++;
    }

    public int countAt(int blockX, int blockZ) {
        long key = ChunkPos.asLong(blockX >> 4, blockZ >> 4);
        int[] grid = chunks.get(key);
        if (grid == null) return 0;
        return grid[(blockZ & 0xF) * 16 + (blockX & 0xF)];
    }

    public int peak() {
        int max = 0;
        for (int[] grid : chunks.values()) {
            for (int v : grid) if (v > max) max = v;
        }
        return max;
    }

    public int totalSamples() {
        long sum = 0;
        for (int[] grid : chunks.values()) {
            for (int v : grid) sum += v;
        }
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }

    public int chunksTracked() { return chunks.size(); }

    public void clear() { chunks.clear(); }
}
