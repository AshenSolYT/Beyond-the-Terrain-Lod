package com.anonymous.beyondthehorizon.client.lod;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * What the mod costs, for the F3 screen: CPU time spent in each of its parts, counted from wherever it is spent, and what is drawn.
 * The times are only those of the CPU (the time to give the work to the graphics card), the cost on the card itself is not here.
 * Everything is added up over a window of a second, which {@link #snapshot} closes when the F3 screen asks.
 */
public final class PerfStats {
    /** The parts whose time is taken. */
    public enum Timer {
        /** The terrain pass of a frame, with the trees and the water. */
        TERRAIN,
        /** The pass that draws the ground a second time for the depth only. */
        DEPTH,
        /** Putting finished meshes on the card, on the render thread. */
        UPLOAD,
        /** Making one tile's mesh, on a worker. */
        MESH,
        /** Everything the renderer does in a frame before the passes: which tiles are seen, their transforms and uniforms. */
        PREP,
        /** The manager's tick: planning which tiles are wanted, asking for them, dropping the ones no longer needed. */
        TICK
    }

    /** Things that are only counted. */
    public enum Count {
        ;
    }

    private static final int TIMERS = Timer.values().length;
    private static final int COUNTS = Count.values().length;
    private static final AtomicLongArray NANOS = new AtomicLongArray(TIMERS);
    private static final AtomicLongArray CALLS = new AtomicLongArray(TIMERS);
    private static final AtomicLongArray WINDOW_COUNTS = new AtomicLongArray(COUNTS);
    private static final AtomicLongArray TOTAL_COUNTS = new AtomicLongArray(COUNTS);
    // The last window that was closed.
    private static final double[] MS_PER_CALL = new double[TIMERS];
    private static final double[] MS_PER_SECOND = new double[TIMERS];
    private static final double[] CALLS_PER_SECOND = new double[TIMERS];
    private static final double[] COUNTS_PER_SECOND = new double[COUNTS];
    private static long windowStart;

    // What the renderer drew in its last frame.
    public static volatile int groundDraws;
    public static volatile int treeMeshes;
    public static volatile long treeVertices;

    private PerfStats() {
    }

    public static void add(Timer timer, long nanos) {
        NANOS.addAndGet(timer.ordinal(), nanos);
        CALLS.incrementAndGet(timer.ordinal());
    }

    public static void count(Count count) {
        WINDOW_COUNTS.incrementAndGet(count.ordinal());
        TOTAL_COUNTS.incrementAndGet(count.ordinal());
    }

    /** Closes the window of a second when it has run that long, so the numbers shown are those of the last whole second. */
    public static synchronized void snapshot() {
        long now = System.nanoTime();
        if (windowStart == 0L) {
            windowStart = now;
            return;
        }
        long elapsed = now - windowStart;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        double seconds = elapsed / 1.0e9;
        for (int i = 0; i < TIMERS; i++) {
            long nanos = NANOS.getAndSet(i, 0L);
            long calls = CALLS.getAndSet(i, 0L);
            MS_PER_CALL[i] = calls == 0L ? 0.0 : nanos / 1.0e6 / calls;
            MS_PER_SECOND[i] = nanos / 1.0e6 / seconds;
            CALLS_PER_SECOND[i] = calls / seconds;
        }
        for (int i = 0; i < COUNTS; i++) {
            COUNTS_PER_SECOND[i] = WINDOW_COUNTS.getAndSet(i, 0L) / seconds;
        }
        windowStart = now;
    }

    public static synchronized double msPerCall(Timer timer) {
        return MS_PER_CALL[timer.ordinal()];
    }

    public static synchronized double msPerSecond(Timer timer) {
        return MS_PER_SECOND[timer.ordinal()];
    }

    public static synchronized double callsPerSecond(Timer timer) {
        return CALLS_PER_SECOND[timer.ordinal()];
    }

    public static synchronized double countsPerSecond(Count count) {
        return COUNTS_PER_SECOND[count.ordinal()];
    }

    public static long total(Count count) {
        return TOTAL_COUNTS.get(count.ordinal());
    }
}
