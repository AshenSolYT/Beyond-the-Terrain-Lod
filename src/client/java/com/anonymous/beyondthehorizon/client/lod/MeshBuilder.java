package com.anonymous.beyondthehorizon.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/**
 * Turns the samples of one tile into vertices and triangles, nothing else: the colors and heights come ready. No game classes.
 * <p>
 * A vertex is 16 bytes: x, y, z as floats (x and z from the tile's north-west corner, y the height in the world) and the color as
 * RGBA bytes. The ground is the {@link #SAMPLES} by {@link #SAMPLES} grid, followed by a skirt around the tile that hides the cracks
 * towards tiles of other sizes. Every tile of a kind has the same triangles ({@link #sharedIndices}); only tiles with holes (the
 * void between the End's islands) bring their own. The water is a flat grid of its own, with only the cells that have water.
 * <p>
 * The buffer: ground vertices, water vertices, own ground triangles, water triangles; indices are unsigned shorts.
 */
public final class MeshBuilder {
    public static final int CELLS = 32;
    public static final int SAMPLES = CELLS + 1;
    public static final int GRID = SAMPLES * SAMPLES;
    public static final int GROUND_VERTICES = GRID + 4 * SAMPLES;
    public static final int VERTEX_SIZE = 16;
    public static final int SHARED_INDEX_COUNT = CELLS * CELLS * 6 + 4 * CELLS * 6;

    /**
     * What a tile is built from, all of {@link #GRID} samples, x + z * SAMPLES.
     *
     * @param ground     height of the ground
     * @param colors     ARGB color of the ground, shaded
     * @param voids      no ground here, may be null
     * @param water      water over this sample, may be null
     * @param waterColor ARGB of the water, its alpha the opacity; with {@code water}
     * @param waterLevel height of the water surface
     */
    public record Input(
        int spacing, float[] ground, int[] colors, boolean[] voids, boolean[] water, int[] waterColor, float waterLevel,
        TreePlanter.@Nullable Trees trees
    ) {
    }

    /** {@code -1} offsets are absent. */
    public record Mesh(
        ByteBuffer data, int groundIndexCount, int ownGroundIndexOffset, int waterVertexOffset, int waterIndexOffset, int waterIndexCount,
        float minY, float maxY, int treeVertexOffset, int treeVertexCount, int treeIndexOffset, int treeIndexCount
    ) {
        public boolean sharedIndices() {
            return this.ownGroundIndexOffset < 0;
        }

        public boolean hasWater() {
            return this.waterIndexCount > 0;
        }
    }

    private MeshBuilder() {
    }

    public static Mesh build(Input in) {
        int spacing = in.spacing();
        boolean hasVoid = false;
        if (in.voids() != null) {
            for (boolean v : in.voids()) {
                hasVoid |= v;
            }
        }
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < GRID; i++) {
            if (in.voids() == null || !in.voids()[i]) {
                minY = Math.min(minY, in.ground()[i]);
                maxY = Math.max(maxY, in.ground()[i]);
            }
        }
        if (minY > maxY) {
            return null;
        }
        // Cells with water, and the water grid's triangles.
        short[] waterIndices = new short[CELLS * CELLS * 6];
        int waterCount = 0;
        if (in.water() != null) {
            for (int z = 0; z < CELLS; z++) {
                for (int x = 0; x < CELLS; x++) {
                    int a = x + z * SAMPLES;
                    boolean wet = in.water()[a] || in.water()[a + 1] || in.water()[a + SAMPLES] || in.water()[a + SAMPLES + 1];
                    if (wet) {
                        waterCount = cell(waterIndices, waterCount, a, a + 1, a + SAMPLES, a + SAMPLES + 1, ((x + z) & 1) == 0);
                    }
                }
            }
        }
        short[] ownIndices = null;
        int ownCount = 0;
        if (hasVoid) {
            ownIndices = new short[SHARED_INDEX_COUNT];
            boolean[] v = in.voids();
            for (int z = 0; z < CELLS; z++) {
                for (int x = 0; x < CELLS; x++) {
                    int a = x + z * SAMPLES;
                    if (!(v[a] || v[a + 1] || v[a + SAMPLES] || v[a + SAMPLES + 1])) {
                        ownCount = cell(ownIndices, ownCount, a, a + 1, a + SAMPLES, a + SAMPLES + 1, ((x + z) & 1) == 0);
                    }
                }
            }
            // No skirts around islands: they end where the ground does.
        }
        int groundBytes = GROUND_VERTICES * VERTEX_SIZE;
        int waterVertexBytes = waterCount > 0 ? GRID * VERTEX_SIZE : 0;
        int ownBytes = ownCount * 2;
        int waterIndexBytes = waterCount * 2;
        int total = groundBytes + waterVertexBytes + ownBytes + waterIndexBytes;
        TreePlanter.Trees trees = in.trees();
        int treeVertices = trees == null ? 0 : trees.vertexCount();
        int treeIndices = trees == null ? 0 : trees.indexCount();
        int treeVertexOffset = total;
        total += treeVertices * VERTEX_SIZE + treeIndices * 2;
        ByteBuffer data = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder());
        float skirt = Math.max(spacing * 3.0F, 8.0F);
        for (int z = 0; z < SAMPLES; z++) {
            for (int x = 0; x < SAMPLES; x++) {
                int i = x + z * SAMPLES;
                vertex(data, x * spacing, in.ground()[i], z * spacing, in.colors()[i]);
            }
        }
        for (int edge = 0; edge < 4; edge++) {
            for (int i = 0; i < SAMPLES; i++) {
                int s = edgeX(edge, i) + edgeZ(edge, i) * SAMPLES;
                vertex(data, edgeX(edge, i) * spacing, in.ground()[s] - skirt, edgeZ(edge, i) * spacing, darker(in.colors()[s]));
            }
        }
        if (waterCount > 0) {
            for (int z = 0; z < SAMPLES; z++) {
                for (int x = 0; x < SAMPLES; x++) {
                    int i = x + z * SAMPLES;
                    vertex(data, x * spacing, in.waterLevel(), z * spacing, in.waterColor()[i]);
                }
            }
        }
        for (int i = 0; i < ownCount; i++) {
            data.putShort(ownIndices[i]);
        }
        for (int i = 0; i < waterCount; i++) {
            data.putShort(waterIndices[i]);
        }
        for (int i = 0; i < treeVertices; i++) {
            vertex(data, trees.xyz()[i * 3], trees.xyz()[i * 3 + 1], trees.xyz()[i * 3 + 2], trees.colors()[i]);
        }
        for (int i = 0; i < treeIndices; i++) {
            data.putShort(trees.indices()[i]);
        }
        data.flip();
        int ownOffset = hasVoid ? groundBytes + waterVertexBytes : -1;
        int waterIndexOffset = waterCount > 0 ? groundBytes + waterVertexBytes + ownBytes : -1;
        return new Mesh(
            data, hasVoid ? ownCount : CELLS * CELLS * 6 + (hasVoid ? 0 : 4 * CELLS * 6), ownOffset, waterCount > 0 ? groundBytes : -1,
            waterIndexOffset, waterCount, Math.min(minY, in.waterLevel() < minY && waterCount > 0 ? in.waterLevel() : minY) - skirt,
            Math.max(Math.max(maxY, trees == null ? maxY : trees.maxY()), waterCount > 0 ? in.waterLevel() : maxY),
            treeVertices > 0 ? treeVertexOffset : -1, treeVertices, treeIndices > 0 ? treeVertexOffset + treeVertices * VERTEX_SIZE : -1, treeIndices
        );
    }

    /** The triangles every tile without holes has: the ground grid, then the skirts. */
    public static short[] sharedIndices() {
        short[] out = new short[SHARED_INDEX_COUNT];
        int n = 0;
        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int a = x + z * SAMPLES;
                n = cell(out, n, a, a + 1, a + SAMPLES, a + SAMPLES + 1, ((x + z) & 1) == 0);
            }
        }
        for (int edge = 0; edge < 4; edge++) {
            int skirtBase = GRID + edge * SAMPLES;
            for (int i = 0; i < CELLS; i++) {
                int top0 = edgeX(edge, i) + edgeZ(edge, i) * SAMPLES;
                int top1 = edgeX(edge, i + 1) + edgeZ(edge, i + 1) * SAMPLES;
                // Along the north and east edges the samples run the other way round the tile.
                if (edge == 0 || edge == 3) {
                    n = quad(out, n, top1, top0, skirtBase + i + 1, skirtBase + i);
                } else {
                    n = quad(out, n, top0, top1, skirtBase + i, skirtBase + i + 1);
                }
            }
        }
        return out;
    }

    /** Two triangles over a cell, facing up; the diagonal alternates like a chessboard. */
    private static int cell(short[] out, int n, int a, int b, int c, int d, boolean even) {
        if (even) {
            return quad(out, n, a, b, c, d);
        }
        out[n++] = (short) a;
        out[n++] = (short) c;
        out[n++] = (short) d;
        out[n++] = (short) a;
        out[n++] = (short) d;
        out[n++] = (short) b;
        return n;
    }

    private static int quad(short[] out, int n, int a, int b, int c, int d) {
        out[n++] = (short) a;
        out[n++] = (short) c;
        out[n++] = (short) b;
        out[n++] = (short) b;
        out[n++] = (short) c;
        out[n++] = (short) d;
        return n;
    }

    private static int edgeX(int edge, int i) {
        return switch (edge) {
            case 0, 1 -> i;
            case 2 -> 0;
            default -> CELLS;
        };
    }

    private static int edgeZ(int edge, int i) {
        return switch (edge) {
            case 0 -> 0;
            case 1 -> CELLS;
            default -> i;
        };
    }

    private static void vertex(ByteBuffer out, float x, float y, float z, int argb) {
        out.putFloat(x).putFloat(y).putFloat(z);
        out.put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24));
    }

    private static int darker(int argb) {
        int r = (argb >>> 16 & 255) * 7 / 10;
        int g = (argb >>> 8 & 255) * 7 / 10;
        int b = (argb & 255) * 7 / 10;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }
}
