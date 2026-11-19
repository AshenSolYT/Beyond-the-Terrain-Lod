package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.client.color.TileColors;
import com.anonymous.beyondthehorizon.tile.TileData;
import com.anonymous.beyondthehorizon.tile.TilePos;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Reads the samples of a tile, the heights, the biomes and what lies on the ground, into what {@link MeshBuilder} builds from:
 * the height of the ground, its color shaded by the slope alone (the way a block's faces are shaded, with no shadows cast), and
 * the water. Runs on a worker thread.
 */
public final class TileMesher {
    private static final int S = MeshBuilder.SAMPLES;
    private static final float SHADE_TOP = 1.0F;
    private static final float SHADE_NORTH_SOUTH = 0.8F;
    private static final float SHADE_EAST_WEST = 0.6F;
    private static final int UNKNOWN_WATER_DEPTH = 4;
    /** Coarse water lies a little under vanilla's, like the fine one. */
    private static final float WATER_UNDER_VANILLA = 1.0F / 9.0F;
    private static final Identifier SAND = Identifier.withDefaultNamespace("sand");
    private static final Identifier STONE = Identifier.withDefaultNamespace("stone");
    /** Slope (rise over run) where the ground starts to show stone, and where it is all stone: steep ground has no grass. */
    private static final float STONE_FROM = 0.7F;
    private static final float STONE_FULL = 1.6F;

    private TileMesher() {
    }

    public static MeshBuilder.@Nullable Mesh mesh(TileData data, TilePos pos, Registry<Biome> biomes, int seaLevel, int treeLevel, int treeDensity, int billboardLevel, int stepLevel) {
        int spacing = BeyondTheHorizonConstants.tileSize(pos.level()) / BeyondTheHorizonConstants.TILE_CELLS;
        List<Holder<Biome>> palette = new ArrayList<>(data.palette.size());
        for (Identifier id : data.palette) {
            palette.add(biomes.get(ResourceKey.create(Registries.BIOME, id)).orElse(null));
        }
        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        float[] ground = new float[MeshBuilder.GRID];
        boolean[] voids = new boolean[MeshBuilder.GRID];
        boolean[] water = new boolean[MeshBuilder.GRID];
        boolean anyWater = false;
        boolean coarse = data.subBiomes != null;
        float waterLevel = seaLevel - (coarse ? WATER_UNDER_VANILLA : 0.0F);
        for (int i = 0; i < MeshBuilder.GRID; i++) {
            int flags = data.flags[i];
            boolean isWater = (flags & TileData.FLAG_WATER) != 0 && (flags & TileData.FLAG_ICEBERG) == 0;
            float height = data.heights[i];
            voids[i] = (flags & TileData.FLAG_VOID) != 0;
            water[i] = isWater;
            anyWater |= isWater;
            if (isWater) {
                int depth = data.waterDepths == null ? 0 : data.waterDepths[i] & 0xFF;
                ground[i] = height - (depth > 0 ? depth : UNKNOWN_WATER_DEPTH);
            } else {
                ground[i] = height;
            }
        }
        // Colors, from the slope of the ground at each sample.
        int[] colors = new int[MeshBuilder.GRID];
        int[] waterColors = anyWater ? new int[MeshBuilder.GRID] : null;
        int baseX = (int) pos.minX();
        int baseZ = (int) pos.minZ();
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int i = x + z * S;
                if (voids[i]) {
                    continue;
                }
                float dx = (ground[Math.min(x + 1, S - 1) + z * S] - ground[Math.max(x - 1, 0) + z * S]) / (spacing * (Math.min(x + 1, S - 1) - Math.max(x - 1, 0)));
                float dz = (ground[x + Math.min(z + 1, S - 1) * S] - ground[x + Math.max(z - 1, 0) * S]) / (spacing * (Math.min(z + 1, S - 1) - Math.max(z - 1, 0)));
                float stepsX = Math.abs(dx);
                float stepsZ = Math.abs(dz);
                float area = 1.0F + stepsX + stepsZ;
                float shade = (SHADE_TOP + stepsZ * SHADE_NORTH_SOUTH + stepsX * SHADE_EAST_WEST) / area;
                float sides = (stepsX + stepsZ) / area;
                float slope = Math.max(stepsX, stepsZ);
                int wx = baseX + x * spacing;
                int wz = baseZ + z * spacing;
                int flags = data.flags[i];
                Holder<Biome> biome = palette.get(data.biomes[i] & 0xFF);
                int height = Math.round(data.heights[i]);
                // The block the world's surface rules put on top here, as the server worked it out; null when it did not.
                Identifier surfaceBlock = surfaceBlock(data, i);
                if (water[i]) {
                    int depth = Math.max(1, Math.round(data.heights[i] - ground[i]));
                    int sea = TileColors.blockColor(surfaceBlock != null ? surfaceBlock : SAND, biome, wx, Math.round(ground[i]), wz, sides);
                    float deep = (float) Math.exp(-depth / 12.0);
                    float seabed = 0.25F + (0.6F - 0.25F) * deep;
                    colors[i] = ARGB.opaque(ARGB.scaleRGB(sea, shade * seabed));
                    int surface = TileColors.color(biome, wx, height, wz, true, false, 0.0F, 0.0F, seaLevel, scratch);
                    float alpha = 0.7F + 0.25F * (1.0F - deep);
                    if (TileColors.isIcy(biome, wx, height, wz, seaLevel, scratch)) {
                        alpha = 0.95F;
                    }
                    waterColors[i] = Math.round(alpha * 255.0F) << 24 | ARGB.scaleRGB(surface, TileColors.WATER_DEPTH) & 0xFFFFFF;
                    continue;
                }
                boolean lava = (flags & TileData.FLAG_LAVA) != 0;
                int color;
                // Whether the color comes from the real surface block; guesses from the biome get a mix of stone on steep ground below.
                boolean exact = false;
                if ((flags & TileData.FLAG_ICEBERG) != 0) {
                    color = TileColors.icebergColor(biome, wx, height, wz, sides, flags);
                } else {
                    if (!lava && surfaceBlock != null) {
                        color = coarse
                            ? coarseSurfaceColor(data, i, palette, surfaceBlock, wx, height, wz, slope, sides, seaLevel, scratch)
                            : TileColors.surfaceColor(surfaceBlock, biome, wx, height, wz, slope, sides, seaLevel, scratch);
                        exact = true;
                    } else if (coarse) {
                        color = coarseColor(data, i, palette, wx, height, wz, slope, sides, seaLevel, scratch, lava);
                    } else {
                        color = TileColors.color(biome, wx, height, wz, false, lava, slope, sides, seaLevel, scratch);
                    }
                }
                if (!lava && !exact && (flags & TileData.FLAG_ICEBERG) == 0 && slope > STONE_FROM) {
                    float t = Math.min(1.0F, (slope - STONE_FROM) / (STONE_FULL - STONE_FROM));
                    color = ARGB.opaque(ARGB.srgbLerp(t * 0.85F, color, TileColors.blockColor(STONE, biome, wx, height, wz, sides)));
                }
                colors[i] = ARGB.opaque(ARGB.scaleRGB(color, lava ? 1.0F : shade));
            }
        }
        if (anyWater) {
            for (int i = 0; i < MeshBuilder.GRID; i++) {
                if (!water[i]) {
                    // Over land the water only shows where the ground is lower than its surface, with the color of a neighbour's.
                    waterColors[i] = nearestWaterColor(water, waterColors, i);
                }
            }
        }
        TreePlanter.Trees trees = treeLevel > 0 && spacing <= 1 << treeLevel
            ? TreePlanter.plant(data, pos, spacing, ground, voids, water, colors, treeDensity, palette, billboardLevel < 5 && spacing >= 1 << billboardLevel)
            : null;
        return MeshBuilder.build(new MeshBuilder.Input(spacing, ground, colors, voids, anyWater ? water : null, waterColors, waterLevel, trees, stepLevel > 0 && spacing <= 1 << stepLevel));
    }

    /** The block the server's surface rules put on sample {@code i}, or null when the tile does not say. */
    private static @Nullable Identifier surfaceBlock(TileData data, int i) {
        if (data.surface == null) {
            return null;
        }
        int number = data.surface[i] & 0xFF;
        return number == 0 ? null : data.blocks.get(number - 1);
    }

    /** The color of a coarse sample whose block is known: the mean over the biomes of its sub-samples, which tint it differently. */
    private static int coarseSurfaceColor(
        TileData data, int i, List<Holder<Biome>> palette, Identifier block, int wx, int height, int wz, float slope, float sides, int seaLevel,
        BlockPos.MutableBlockPos scratch
    ) {
        int r = 0;
        int g = 0;
        int b = 0;
        int n = TileData.SUB_SAMPLES;
        for (int k = 0; k < n; k++) {
            Holder<Biome> sub = palette.get(data.subBiomes[i * n + k] & 0xFF);
            int c = TileColors.surfaceColor(block, sub, wx, height, wz, slope, sides, seaLevel, scratch);
            r += c >>> 16 & 255;
            g += c >>> 8 & 255;
            b += c & 255;
        }
        return ARGB.color(255, r / n, g / n, b / n);
    }

    /** The color of a coarse sample: the mean of its sub-samples' biomes. */
    private static int coarseColor(
        TileData data, int i, List<Holder<Biome>> palette, int wx, int height, int wz, float slope, float sides, int seaLevel,
        BlockPos.MutableBlockPos scratch, boolean lava
    ) {
        int r = 0;
        int g = 0;
        int b = 0;
        int n = TileData.SUB_SAMPLES;
        for (int k = 0; k < n; k++) {
            Holder<Biome> sub = palette.get(data.subBiomes[i * n + k] & 0xFF);
            int c = TileColors.color(sub, wx, height, wz, false, lava, slope, sides, seaLevel, scratch);
            r += c >>> 16 & 255;
            g += c >>> 8 & 255;
            b += c & 255;
        }
        return ARGB.color(255, r / n, g / n, b / n);
    }

    private static int nearestWaterColor(boolean[] water, int[] colors, int i) {
        int x = i % S;
        int z = i / S;
        for (int r = 1; r < 4; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx >= 0 && nx < S && nz >= 0 && nz < S && water[nx + nz * S]) {
                        return colors[nx + nz * S];
                    }
                }
            }
        }
        return 0x99224466;
    }
}
