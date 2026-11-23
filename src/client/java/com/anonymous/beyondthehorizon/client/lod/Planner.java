package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.tile.TilePos;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Decides which tiles make up the picture of the distant terrain, and which ones are still to be loaded. Pure arithmetic, no game
 * classes: a quadtree walk from the biggest tiles down, a tile splitting in four while the camera is close to it compared to its size.
 * <p>
 * The walk gives a picture without holes or overlaps whatever is loaded: a tile whose parts are not all there yet is drawn as one
 * bigger tile if that is loaded, or from the finer tiles that are, and what is missing is asked for, biggest and nearest first.
 * Tiles lying completely inside the square vanilla has loaded around the camera are left out: vanilla is drawn over the terrain
 * there anyway.
 */
public final class Planner {
    /** Where the altitude of the camera counts from, for the distance to tiles seen from high up. */
    private static final double REFERENCE_HEIGHT = 64.0;
    /** How many levels below a tile that is not loaded may stand in for it. */
    private static final int DESCENT = 3;
    /** A tile that was split stays split until the camera is this much farther than where it split, so the border does not flicker. */
    private static final double HYSTERESIS = 1.25;
    /** Within the Block Heightmap distance every tile is split down to this level (spacing 2: the finest the columns are built on). */
    private static final int BLOCK_ZONE_LEVEL = 1;
    /** Within the Block Heightmap's fine distance tiles go down to the server's finest level (spacing 1: a column per block), whatever Sharpness and Start level say. */
    private static final int BLOCK_FINE_LEVEL = 0;
    /** Within the tree distance every tile is split down to this level (spacing 4), fine enough for trees as boxes. */
    private static final int TREE_ZONE_LEVEL = 2;
    /** A zone's border moves out by this much for tiles that are split already, so it does not flicker. */
    private static final double ZONE_HYSTERESIS = 1.1;
    /** While the camera moves fast the tiles are asked for in rings of this width around it, the nearest ring first. */
    private static final double NEAR_BAND = 384.0;

    public static final class Params {
        public double camX;
        public double camY;
        public double camZ;
        /** How far tiles are drawn, in blocks. */
        public double reach = 4096.0;
        /** A tile splits while the camera is nearer than its size times this. */
        public double splitFactor = 3.0;
        /** How far from the camera tiles are kept fine for the Block Heightmap, in blocks; 0 when it is off. */
        public double blockDistance;
        /** How far from the camera tiles are kept fine for the box trees, in blocks; 0 when trees are off. */
        public double treeDistance;
        /** How far from the camera tiles go down to {@link #hardMin} for the Block Heightmap, in blocks; 0 when it is off. */
        public double blockFineDistance;
        /** The finest level the server has (Start level does not apply to the Block Heightmap's fine zone). */
        public int hardMin = 0;
        public int minLevel = 1;
        public int maxLevel = BeyondTheHorizonConstants.MAX_LEVEL;
        /** The square vanilla draws, in blocks: [ownX0, ownX1) by [ownZ0, ownZ1); empty when the end is not past the start. */
        public double ownX0;
        public double ownZ0;
        public double ownX1;
        public double ownZ1;
        /**
         * The camera moves fast: the tiles close to it are asked for before the coarse ones far away, as the coarse ones that the camera
         * outruns are no use. Otherwise the biggest tiles go first so that the whole picture is there early.
         */
        public boolean nearFirst;
        /** The most tiles the plan may have (0: no limit): the zones of the Block Heightmap and the trees shrink to stay under it, see {@link Planner#zoneScale}. */
        public int maxLeaves;

        /** A copy for the planner's own thread: the manager goes on changing its Params while a plan is made. */
        public Params copy() {
            Params c = new Params();
            c.camX = this.camX;
            c.camY = this.camY;
            c.camZ = this.camZ;
            c.reach = this.reach;
            c.splitFactor = this.splitFactor;
            c.blockDistance = this.blockDistance;
            c.treeDistance = this.treeDistance;
            c.blockFineDistance = this.blockFineDistance;
            c.hardMin = this.hardMin;
            c.minLevel = this.minLevel;
            c.maxLevel = this.maxLevel;
            c.ownX0 = this.ownX0;
            c.ownZ0 = this.ownZ0;
            c.ownX1 = this.ownX1;
            c.ownZ1 = this.ownZ1;
            c.nearFirst = this.nearFirst;
            c.maxLeaves = this.maxLeaves;
            return c;
        }
    }

    /** Whether a tile is ready to be drawn. */
    public interface Loaded {
        boolean has(long key);
    }

    /** Keys of the tiles to draw. */
    public long[] draw = new long[256];
    public int drawCount;
    /** Keys of the tiles to ask for, with how urgent each is (smaller first); see {@link #sortWanted}. */
    public long[] want = new long[256];
    public double[] wantScore = new double[256];
    public int wantCount;

    /**
     * Factor on the three zone distances (Block Heightmap, its fine zone, trees), 1 when the plan fits {@link Params#maxLeaves}. A plan
     * above the limit makes the client drop the farthest tiles, which are then asked for again: the horizon shrinks and the tiles are
     * loaded over and over. So the zones shrink instead (the tile count goes with their square) and grow back when there is room.
     */
    public double zoneScale = 1.0;
    /** Tiles the last plan has (the ones drawn or asked for, not split). */
    public int leafCount;
    private double blockZone;
    private double fineZone;
    private double treeZone;
    private Params p;
    private Loaded loaded;
    /** The tiles that were split in the last plan, and in this one. */
    private HashSet<Long> splitPrev = new HashSet<>();
    private HashSet<Long> splitNow = new HashSet<>();

    public void plan(Params params, Loaded isLoaded) {
        this.p = params;
        this.loaded = isLoaded;
        this.drawCount = 0;
        this.wantCount = 0;
        this.leafCount = 0;
        this.blockZone = params.blockDistance * this.zoneScale;
        this.fineZone = params.blockFineDistance * this.zoneScale;
        this.treeZone = params.treeDistance * this.zoneScale;
        HashSet<Long> swap = this.splitPrev;
        this.splitPrev = this.splitNow;
        this.splitNow = swap;
        this.splitNow.clear();
        int level = params.maxLevel;
        long size = BeyondTheHorizonConstants.tileSize(level);
        int first = (int) Math.floorDiv(-(long) BeyondTheHorizonConstants.WORLD_LIMIT, size);
        int last = (int) Math.floorDiv((long) BeyondTheHorizonConstants.WORLD_LIMIT - 1, size);
        for (int z = first; z <= last; z++) {
            for (int x = first; x <= last; x++) {
                this.cover(level, x, z);
            }
        }
        if (params.maxLeaves > 0) {
            if (this.leafCount > params.maxLeaves) {
                this.zoneScale = Math.max(0.05, this.zoneScale * Math.min(0.95, Math.max(0.5, Math.sqrt((double) params.maxLeaves / this.leafCount))));
            } else if (this.leafCount < params.maxLeaves * 0.75 && this.zoneScale < 1.0) {
                this.zoneScale = Math.min(1.0, this.zoneScale * 1.03);
            }
        }
    }

    /** Orders {@link #want} by urgency, most urgent first: a heap sort of the two arrays in place, with no boxing. */
    public void sortWanted() {
        int n = this.wantCount;
        long[] keys = this.want;
        double[] scores = this.wantScore;
        for (int i = n / 2 - 1; i >= 0; i--) {
            siftDown(keys, scores, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            swap(keys, scores, 0, end);
            siftDown(keys, scores, 0, end);
        }
    }

    /** Max-heap on the score, so that the sort ends smallest first. */
    private static void siftDown(long[] keys, double[] scores, int root, int n) {
        while (true) {
            int child = 2 * root + 1;
            if (child >= n) {
                return;
            }
            if (child + 1 < n && scores[child + 1] > scores[child]) {
                child++;
            }
            if (scores[child] <= scores[root]) {
                return;
            }
            swap(keys, scores, root, child);
            root = child;
        }
    }

    private static void swap(long[] keys, double[] scores, int a, int b) {
        long k = keys[a];
        keys[a] = keys[b];
        keys[b] = k;
        double s = scores[a];
        scores[a] = scores[b];
        scores[b] = s;
    }

    /** Whether the area of the tile is completely drawn, or needs nothing. */
    private boolean cover(int level, int x, int z) {
        long size = BeyondTheHorizonConstants.tileSize(level);
        long minX = x * size;
        long minZ = z * size;
        long limit = BeyondTheHorizonConstants.WORLD_LIMIT;
        if (minX >= limit || minX + size <= -limit || minZ >= limit || minZ + size <= -limit) {
            return true;
        }
        Params p = this.p;
        double dx = Math.max(0.0, Math.max(minX - p.camX, p.camX - (minX + size)));
        double dz = Math.max(0.0, Math.max(minZ - p.camZ, p.camZ - (minZ + size)));
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > p.reach) {
            return true;
        }
        if (p.ownX1 > p.ownX0 && p.ownZ1 > p.ownZ0 && minX >= p.ownX0 && minX + size <= p.ownX1 && minZ >= p.ownZ0 && minZ + size <= p.ownZ1) {
            return true;
        }
        double altitude = Math.max(0.0, p.camY - REFERENCE_HEIGHT);
        double distance = Math.sqrt(horizontal * horizontal + altitude * altitude);
        long key = TilePos.pack(level, x, z);
        double factor = p.splitFactor * (this.splitPrev.contains(key) ? HYSTERESIS : 1.0);
        boolean zone = (this.blockZone > 0.0 && level > BLOCK_ZONE_LEVEL && distance < this.blockZone * (this.splitPrev.contains(key) ? ZONE_HYSTERESIS : 1.0))
            || (this.treeZone > 0.0 && level > TREE_ZONE_LEVEL && distance < this.treeZone * (this.splitPrev.contains(key) ? ZONE_HYSTERESIS : 1.0));
        boolean fine = this.fineZone > 0.0 && level > BLOCK_FINE_LEVEL && level > p.hardMin
            && distance < this.fineZone * (this.splitPrev.contains(key) ? ZONE_HYSTERESIS : 1.0);
        boolean split = (level > p.minLevel && (zone || distance < size * factor)) || fine;
        if (split) {
            this.splitNow.add(key);
        }
        if (!split) {
            this.leafCount++;
            if (this.loaded.has(key)) {
                this.addDraw(key);
                return true;
            }
            this.addWant(key, level, distance);
            // Finer tiles that are there stand in while it loads, for a tile that merged.
            this.descend(level, x, z, DESCENT);
            return false;
        }
        int mark = this.drawCount;
        boolean all = true;
        for (int c = 0; c < 4; c++) {
            all &= this.cover(level - 1, x * 2 + (c & 1), z * 2 + (c >> 1));
        }
        if (all) {
            return true;
        }
        if (this.loaded.has(key)) {
            this.drawCount = mark;
            this.addDraw(key);
            return true;
        }
        // Not there either: it is asked for as the stand-in for what is missing, the finer tiles that are there stay.
        this.addWant(key, level, distance);
        return false;
    }

    /** Draws the finer tiles that are loaded under a tile; whether they cover it completely. */
    private boolean descend(int level, int x, int z, int depth) {
        if (depth == 0 || level <= Math.min(this.p.minLevel, Math.max(this.p.hardMin, 0))) {
            return false;
        }
        boolean all = true;
        for (int c = 0; c < 4; c++) {
            int cx = x * 2 + (c & 1);
            int cz = z * 2 + (c >> 1);
            long key = TilePos.pack(level - 1, cx, cz);
            if (this.loaded.has(key)) {
                this.addDraw(key);
            } else {
                all &= this.descend(level - 1, cx, cz, depth - 1);
            }
        }
        return all;
    }

    private void addDraw(long key) {
        if (this.drawCount == this.draw.length) {
            this.draw = Arrays.copyOf(this.draw, this.drawCount * 2);
        }
        this.draw[this.drawCount++] = key;
    }

    /** The biggest tiles first, so the whole picture is there early and only gets finer; the nearer ones first among the same size. */
    private void addWant(long key, int level, double distance) {
        if (this.wantCount == this.want.length) {
            this.want = Arrays.copyOf(this.want, this.wantCount * 2);
            this.wantScore = Arrays.copyOf(this.wantScore, this.wantCount * 2);
        }
        this.want[this.wantCount] = key;
        if (this.p.nearFirst) {
            // The nearest ring first, the biggest tiles first within a ring.
            this.wantScore[this.wantCount++] = Math.floor(distance / NEAR_BAND) * 1.0e9 - level * 1.0e6 + distance;
        } else {
            this.wantScore[this.wantCount++] = -level * 1.0e9 + distance;
        }
    }
}
