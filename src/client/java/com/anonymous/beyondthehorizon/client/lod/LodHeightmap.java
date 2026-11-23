package com.anonymous.beyondthehorizon.client.lod;

/**
 * Smooths the height grid of the coarser tiles. The heights come as whole blocks, so over a gentle hill or plain the ground is a few
 * wide plateaus with a one-block step between them, and drawn as it is (the samples are four or more blocks apart here) they show as
 * terraces. Each sample is taken as a control point of a bicubic B-spline surface and the surface is read at the sample: a
 * binomial 1-2-1 kernel along both axes (the cubic B-spline at its knots is 1/6, 4/6, 1/6, which is what two passes of the
 * binomial come close to), repeated a few times so that it spans several samples, and weighed down where the neighbour is much
 * higher or lower, so that cliffs, shores and the edges of voids stay as they are.
 * <p>
 * The ring of samples around the tile is shared with the neighbouring tiles of the same level, who have the very same values
 * there; so that the two sides of a border come out the same, a sample on the ring is smoothed along the border only (using samples
 * of the ring alone, which both tiles have) and a corner is left alone. Interior samples use everything around them.
 */
public final class LodHeightmap {
    /** Tiles with a sample spacing (in blocks) under this are drawn as they are: the heights are exact enough. 4 -> 8 in 0.8.18.8: the tree zone's tiles (spacing 4) looked flat when smoothed. */
    public static final int MIN_SMOOTH_SPACING = 8;
    /** The stepped columns (flat tops, walls) are kept for tiles up to a spacing of 2 to this power; coarser tiles are smooth. */
    public static final int MAX_STEP_LEVEL = 1;
    private static final int PASSES = 2;
    private static final int S = MeshBuilder.SAMPLES;
    private static final ThreadLocal<float[]> SCRATCH = ThreadLocal.withInitial(() -> new float[MeshBuilder.GRID]);

    private LodHeightmap() {
    }

    /** Smooths {@code ground} in place; {@code voids} and {@code water} are per sample, like it. */
    public static void smooth(float[] ground, boolean[] voids, boolean[] water, int spacing) {
        if (spacing < MIN_SMOOTH_SPACING) {
            return;
        }
        // A step of this many blocks between neighbours is taken as a step in the ground, not as the rounding of the heights: it is
        // smoothed fully up to the first and not at all from the second (a gentle slope has more blocks between samples the wider they are).
        float full = Math.max(1.5F, 0.35F * spacing);
        float none = 2.0F * full;
        float[] out = SCRATCH.get();
        for (int pass = 0; pass < PASSES; pass++) {
            for (int z = 0; z < S; z++) {
                for (int x = 0; x < S; x++) {
                    int i = x + z * S;
                    out[i] = voids[i] ? ground[i] : smoothed(ground, voids, water, x, z, full, none);
                }
            }
            System.arraycopy(out, 0, ground, 0, MeshBuilder.GRID);
        }
    }

    private static float smoothed(float[] h, boolean[] voids, boolean[] water, int x, int z, float full, float none) {
        boolean ringX = x == 0 || x == S - 1;
        boolean ringZ = z == 0 || z == S - 1;
        int i = x + z * S;
        if (ringX && ringZ) {
            return h[i];
        }
        float center = h[i];
        float sum;
        float weight;
        if (ringX || ringZ) {
            // Along the border only: the two neighbours on the ring.
            sum = 2.0F * center;
            weight = 2.0F;
            int step = ringX ? S : 1;
            for (int sign = -1; sign <= 1; sign += 2) {
                int j = i + sign * step;
                float w = neighbour(h, voids, water, i, j, center, full, none);
                sum += w * h[j];
                weight += w;
            }
        } else {
            sum = 4.0F * center;
            weight = 4.0F;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int j = i + dx + dz * S;
                    float w = (dx == 0 || dz == 0 ? 2.0F : 1.0F) * neighbour(h, voids, water, i, j, center, full, none);
                    sum += w * h[j];
                    weight += w;
                }
            }
        }
        return sum / weight;
    }

    /** How much of a neighbour's height counts: 1 for ground like this one, 0 for a void, another surface (water or land) or a cliff. */
    private static float neighbour(float[] h, boolean[] voids, boolean[] water, int i, int j, float center, float full, float none) {
        if (voids[j] || water[j] != water[i]) {
            return 0.0F;
        }
        float step = Math.abs(h[j] - center);
        if (step <= full) {
            return 1.0F;
        }
        return step >= none ? 0.0F : (none - step) / (none - full);
    }
}
