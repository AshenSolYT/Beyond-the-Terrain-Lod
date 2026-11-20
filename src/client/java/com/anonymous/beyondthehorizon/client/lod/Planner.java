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
        /**
         * How much the view is zoomed in (1 = not): the tiles in the direction the camera looks (within {@link #coneHalf} and a margin)
         * split that much earlier, the ones outside the view keep {@link #splitFactor}.
         */
        public double zoom = 1.0;
        /** The direction the camera looks in (a unit vector) and the half angle of its view, the diagonal of the screen, in radians. */
        public double lookX;
        public double lookY;
        public double lookZ = 1.0;
        public double coneHalf = Math.PI;
        /** Added to {@link #coneHalf} for the zoom detail, in radians. */
        public double zoomMargin = 0.2;
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
        // The extra detail of zooming goes only to what is in the view: the rest of the circle around the camera is not seen.
        if (p.zoom > 1.0 && this.inView(minX + size * 0.5 - p.camX, REFERENCE_HEIGHT - p.camY, minZ + size * 0.5 - p.camZ, size)) {
            factor *= p.zoom;
        }
        boolean split = level > p.minLevel && distance < size * factor;
        if (split) {
            this.splitNow.add(key);
        }
        if (!split) {
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

    /** Whether a tile, given by the vector from the camera to its middle and its size, lies in the direction the camera looks in. */
    private boolean inView(double dx, double dy, double dz, long size) {
        Params p = this.p;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= size) {
            return true;
        }
        double cos = Math.max(-1.0, Math.min(1.0, (dx * p.lookX + dy * p.lookY + dz * p.lookZ) / length));
        // The tile is as wide as its size over the distance seen from the camera; the ground under it can also be higher or lower than
        // the height the middle is taken at, which the margin covers.
        return Math.acos(cos) <= p.coneHalf + p.zoomMargin + Math.atan2(size * 0.75, length);
    }

    /** Draws the finer tiles that are loaded under a tile; whether they cover it completely. */
    private boolean descend(int level, int x, int z, int depth) {
        if (depth == 0 || level <= this.p.minLevel) {
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
