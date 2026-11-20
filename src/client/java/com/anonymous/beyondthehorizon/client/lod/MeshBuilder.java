package com.anonymous.beyondthehorizon.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
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
        TreePlanter.@Nullable Trees trees, boolean stepped
    ) {
    }

    /** {@code -1} offsets are absent. */
    public record Mesh(
        ByteBuffer data, int groundIndexCount, int ownGroundIndexOffset, int waterVertexOffset, int waterIndexOffset, int waterIndexCount,
        float minY, float maxY, int treeVertexOffset, int treeVertexCount, int treeIndexOffset, int treeIndexCount,
        int stepVertexOffset, int stepVertexCount, int stepIndexOffset, int stepIndexCount, float waterLevel,
        /** The lowest point of the ground itself (and of the water over it), without the skirt that hangs below the edges of the tile. */
        float surfaceMinY
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
        // What comes after indices starts on a vertex boundary, which the renderer's base vertex draws need.
        total = alignVertex(total);
        int treeVertexOffset = total;
        total += treeVertices * VERTEX_SIZE + treeIndices * 2;
        total = alignVertex(total);
        // The stepped columns, for the tiles that have them: the ground is then drawn from these instead of the smooth grid.
        Steps steps = in.stepped() ? steps(in) : null;
        int stepVertices = steps == null ? 0 : steps.v;
        int stepIndices = steps == null ? 0 : steps.n;
        int stepVertexOffset = total;
        total += stepVertices * VERTEX_SIZE + stepIndices * 2;
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
        padToVertex(data);
        for (int i = 0; i < treeVertices; i++) {
            vertex(data, trees.xyz()[i * 3], trees.xyz()[i * 3 + 1], trees.xyz()[i * 3 + 2], trees.colors()[i]);
        }
        for (int i = 0; i < treeIndices; i++) {
            data.putShort(trees.indices()[i]);
        }
        padToVertex(data);
        for (int i = 0; i < stepVertices; i++) {
            vertex(data, steps.xyz[i * 3], steps.xyz[i * 3 + 1], steps.xyz[i * 3 + 2], steps.col[i]);
        }
        for (int i = 0; i < stepIndices; i++) {
            data.putShort(steps.idx[i]);
        }
        data.flip();
        int ownOffset = hasVoid ? groundBytes + waterVertexBytes : -1;
        int waterIndexOffset = waterCount > 0 ? groundBytes + waterVertexBytes + ownBytes : -1;
        return new Mesh(
            data, hasVoid ? ownCount : CELLS * CELLS * 6 + (hasVoid ? 0 : 4 * CELLS * 6), ownOffset, waterCount > 0 ? groundBytes : -1,
            waterIndexOffset, waterCount, Math.min(minY, in.waterLevel() < minY && waterCount > 0 ? in.waterLevel() : minY) - skirt,
            Math.max(Math.max(maxY, trees == null ? maxY : trees.maxY()), waterCount > 0 ? in.waterLevel() : maxY),
            treeVertices > 0 ? treeVertexOffset : -1, treeVertices, treeIndices > 0 ? treeVertexOffset + treeVertices * VERTEX_SIZE : -1, treeIndices,
            stepVertices > 0 ? stepVertexOffset : -1, stepVertices, stepIndices > 0 ? stepVertexOffset + stepVertices * VERTEX_SIZE : -1, stepIndices, in.waterLevel(),
            // The columns are rounded down to a block, so a stepped tile can lie a block under its lowest sample.
            Math.min(minY, in.waterLevel() < minY && waterCount > 0 ? in.waterLevel() : minY) - (steps != null ? 1.0F : 0.0F)
        );
    }

    private static int alignVertex(int bytes) {
        return (bytes + VERTEX_SIZE - 1) / VERTEX_SIZE * VERTEX_SIZE;
    }

    private static void padToVertex(ByteBuffer data) {
        while (data.position() % VERTEX_SIZE != 0) {
            data.put((byte) 0);
        }
    }

    /** The vertices and triangles of the stepped columns of one tile. */
    private static final class Steps {
        float[] xyz = new float[4096 * 3];
        int[] col = new int[4096];
        short[] idx = new short[6144];
        int v;
        int n;

        void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int argb) {
            if ((this.v + 4) * 3 > this.xyz.length) {
                this.xyz = java.util.Arrays.copyOf(this.xyz, this.xyz.length * 2);
                this.col = java.util.Arrays.copyOf(this.col, this.col.length * 2);
            }
            if (this.n + 6 > this.idx.length) {
                this.idx = java.util.Arrays.copyOf(this.idx, this.idx.length * 2);
            }
            int base = this.v;
            float[] c = {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3};
            for (int i = 0; i < 4; i++) {
                this.xyz[(base + i) * 3] = c[i * 3];
                this.xyz[(base + i) * 3 + 1] = c[i * 3 + 1];
                this.xyz[(base + i) * 3 + 2] = c[i * 3 + 2];
                this.col[base + i] = argb;
            }
            this.v += 4;
            this.idx[this.n++] = (short) base;
            this.idx[this.n++] = (short) (base + 1);
            this.idx[this.n++] = (short) (base + 2);
            this.idx[this.n++] = (short) base;
            this.idx[this.n++] = (short) (base + 2);
            this.idx[this.n++] = (short) (base + 3);
        }
    }

    /** How dark the walls of a column are against its top, by the side they face. */
    private static final float WALL_EAST_WEST = 0.70F;
    private static final float WALL_NORTH_SOUTH = 0.54F;

    /**
     * The ground as columns: a cell is a flat top at the height of its corners' mean, rounded down to a block, with a wall wherever
     * a neighbour is lower (the higher of the two draws it) and a skirt at the edges of the tile, where the neighbour is not known.
     */
    private static Steps steps(Input in) {
        int spacing = in.spacing();
        // The neighbour tile is not known: a short skirt leaves a see-through gap along a cliff where it is much lower.
        float skirt = Math.max(spacing * 3.0F, 48.0F);
        StepScratch scratch = STEP_SCRATCH.get();
        float[] tops = scratch.tops;
        int[] cellColors = scratch.colors;
        boolean[] has = scratch.has;
        boolean[] used = scratch.used;
        Arrays.fill(has, false);
        Arrays.fill(used, false);
        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int a = x + z * SAMPLES;
                int cell = x + z * CELLS;
                boolean[] v = in.voids();
                if (v != null && (v[a] || v[a + 1] || v[a + SAMPLES] || v[a + SAMPLES + 1])) {
                    continue;
                }
                float[] g = in.ground();
                int[] c = in.colors();
                if (spacing == 1) {
                    // One block per cell: the sample is the column itself, its neighbours' mean would blur the steps.
                    tops[cell] = (float) Math.floor(g[a]);
                    cellColors[cell] = 0xFF000000 | c[a] & 0xFFFFFF;
                    has[cell] = true;
                    continue;
                }
                tops[cell] = (float) Math.floor((g[a] + g[a + 1] + g[a + SAMPLES] + g[a + SAMPLES + 1]) / 4.0F);
                int r = ((c[a] >>> 16 & 255) + (c[a + 1] >>> 16 & 255) + (c[a + SAMPLES] >>> 16 & 255) + (c[a + SAMPLES + 1] >>> 16 & 255)) / 4;
                int gr = ((c[a] >>> 8 & 255) + (c[a + 1] >>> 8 & 255) + (c[a + SAMPLES] >>> 8 & 255) + (c[a + SAMPLES + 1] >>> 8 & 255)) / 4;
                int b = ((c[a] & 255) + (c[a + 1] & 255) + (c[a + SAMPLES] & 255) + (c[a + SAMPLES + 1] & 255)) / 4;
                cellColors[cell] = 0xFF000000 | r << 16 | gr << 8 | b;
                has[cell] = true;
            }
        }
        Steps out = new Steps();
        for (int z = 0; z < CELLS; z++) {
            int x = 0;
            while (x < CELLS) {
                int cell = x + z * CELLS;
                if (!has[cell] || used[cell]) {
                    x++;
                    continue;
                }
                float h = tops[cell];
                int color = cellColors[cell];
                // A rectangle of cells of one height and about one color is one top: far fewer vertices over flat and rolling ground.
                // First the run along the row, then the rows below it that have the same run.
                int end = x;
                while (end + 1 < CELLS && joins(has, used, tops, cellColors, cell + end + 1 - x, h, color)) {
                    end++;
                }
                int zEnd = z;
                while (zEnd + 1 < CELLS) {
                    boolean whole = true;
                    for (int cx = x; cx <= end; cx++) {
                        if (!joins(has, used, tops, cellColors, cx + (zEnd + 1) * CELLS, h, color)) {
                            whole = false;
                            break;
                        }
                    }
                    if (!whole) {
                        break;
                    }
                    zEnd++;
                }
                for (int rz = z; rz <= zEnd; rz++) {
                    for (int cx = x; cx <= end; cx++) {
                        used[cx + rz * CELLS] = true;
                    }
                }
                float x0 = x * spacing;
                float x1 = (end + 1) * spacing;
                float z0 = z * spacing;
                float z1 = (zEnd + 1) * spacing;
                // Every face is wound counter-clockwise seen from outside, like the ground grid (up for tops).
                out.quad(x0, h, z0, x0, h, z1, x1, h, z1, x1, h, z0, color);
                // The walls at the east and west sides: the neighbours differ from row to row, and rows whose neighbour is as low as the
                // one before share a wall.
                int eastColor = scale(color, WALL_EAST_WEST);
                int rz = z;
                while (rz <= zEnd) {
                    float east = eastHeight(tops, has, end, rz, h, skirt);
                    int last = rz;
                    while (last + 1 <= zEnd && eastHeight(tops, has, end, last + 1, h, skirt) == east) {
                        last++;
                    }
                    if (east < h) {
                        out.quad(x1, east, (last + 1) * spacing, x1, east, rz * spacing, x1, h, rz * spacing, x1, h, (last + 1) * spacing, eastColor);
                    }
                    rz = last + 1;
                }
                rz = z;
                while (rz <= zEnd) {
                    float west = westHeight(tops, has, x, rz, h, skirt);
                    int last = rz;
                    while (last + 1 <= zEnd && westHeight(tops, has, x, last + 1, h, skirt) == west) {
                        last++;
                    }
                    if (west < h) {
                        out.quad(x0, west, rz * spacing, x0, west, (last + 1) * spacing, x0, h, (last + 1) * spacing, x0, h, rz * spacing, eastColor);
                    }
                    rz = last + 1;
                }
                // The walls along the rectangle's far and near sides: the neighbours on those sides differ from cell to cell, and cells whose
                // neighbour is as low as the one before share a wall.
                int cx = x;
                while (cx <= end) {
                    float south = southHeight(tops, has, zEnd, cx, h, skirt);
                    int last2 = cx;
                    while (last2 + 1 <= end && southHeight(tops, has, zEnd, last2 + 1, h, skirt) == south) {
                        last2++;
                    }
                    if (south < h) {
                        float wx0 = cx * spacing;
                        float wx1 = (last2 + 1) * spacing;
                        out.quad(wx0, south, z1, wx1, south, z1, wx1, h, z1, wx0, h, z1, scale(color, WALL_NORTH_SOUTH));
                    }
                    cx = last2 + 1;
                }
                cx = x;
                while (cx <= end) {
                    float north = northHeight(tops, has, z, cx, h, skirt);
                    int last2 = cx;
                    while (last2 + 1 <= end && northHeight(tops, has, z, last2 + 1, h, skirt) == north) {
                        last2++;
                    }
                    if (north < h) {
                        float wx0 = cx * spacing;
                        float wx1 = (last2 + 1) * spacing;
                        out.quad(wx1, north, z0, wx0, north, z0, wx0, h, z0, wx1, h, z0, scale(color, WALL_NORTH_SOUTH));
                    }
                    cx = last2 + 1;
                }
                x = end + 1;
            }
        }
        return out;
    }

    /** Whether a cell joins a rectangle of cells of height {@code h} and about the color {@code color}. */
    private static boolean joins(boolean[] has, boolean[] used, float[] tops, int[] cellColors, int c, float h, int color) {
        return has[c] && !used[c] && tops[c] == h && close(cellColors[c], color);
    }

    /** The height east of the rectangle's last column {@code lastX}, in row {@code z}. */
    private static float eastHeight(float[] tops, boolean[] has, int lastX, int z, float h, float skirt) {
        int c = lastX + z * CELLS;
        return lastX + 1 < CELLS ? (has[c + 1] ? tops[c + 1] : h) : h - skirt;
    }

    /** The height west of the rectangle's first column {@code firstX}, in row {@code z}. */
    private static float westHeight(float[] tops, boolean[] has, int firstX, int z, float h, float skirt) {
        int c = firstX + z * CELLS;
        return firstX > 0 ? (has[c - 1] ? tops[c - 1] : h) : h - skirt;
    }

    /** The arrays {@link #steps} works in, one set per thread: they are cleared where it matters when it starts. */
    private static final class StepScratch {
        final float[] tops = new float[CELLS * CELLS];
        final int[] colors = new int[CELLS * CELLS];
        final boolean[] has = new boolean[CELLS * CELLS];
        final boolean[] used = new boolean[CELLS * CELLS];
    }

    private static final ThreadLocal<StepScratch> STEP_SCRATCH = ThreadLocal.withInitial(StepScratch::new);

    private static float southHeight(float[] tops, boolean[] has, int z, int cx, float h, float skirt) {
        int c = cx + z * CELLS;
        return z + 1 < CELLS ? (has[c + CELLS] ? tops[c + CELLS] : h) : h - skirt;
    }

    private static float northHeight(float[] tops, boolean[] has, int z, int cx, float h, float skirt) {
        int c = cx + z * CELLS;
        return z > 0 ? (has[c - CELLS] ? tops[c - CELLS] : h) : h - skirt;
    }

    /** Whether two colors are close enough to be one top. */
    private static boolean close(int a, int b) {
        return Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)) <= 12
            && Math.abs((a >>> 8 & 255) - (b >>> 8 & 255)) <= 12
            && Math.abs((a & 255) - (b & 255)) <= 12;
    }

    private static int scale(int argb, float f) {
        int r = Math.round((argb >>> 16 & 255) * f);
        int g = Math.round((argb >>> 8 & 255) * f);
        int b = Math.round((argb & 255) * f);
        return 0xFF000000 | r << 16 | g << 8 | b;
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
