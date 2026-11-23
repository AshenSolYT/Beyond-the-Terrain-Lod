package com.anonymous.beyondthehorizon;

/**
 * Cancellation of the mod's background work (tile generation, virtual chunks). Background threads are {@link Worker}s that call
 * {@link #checkpoint()} every so often; when the tile server stops, the work on them ends at its next checkpoint with
 * {@link Cancelled}. Work that runs on threads that are no {@link Worker} (the helper threads of virtual chunk generation)
 * remembers the number it started under and ends once it changed, see {@link #checkGeneration}.
 * <p>
 * There is no pausing for the game's frames any more: the client never reported late frames, so it did nothing.
 */
public final class WorkThrottle {
    /** Raised when the tile server stops, see {@link #checkGeneration}. */
    private static volatile int generation;

    private WorkThrottle() {
    }

    /** Called when a tile server closes: all work that started before ends at its next {@link #checkGeneration}. */
    public static void stopAll() {
        generation++;
    }

    public static int generation() {
        return generation;
    }

    /** Ends the current work with {@link Cancelled} if the tile server stopped since {@code started}, or the thread was interrupted. */
    public static void checkGeneration(int started) {
        if (generation != started || Thread.currentThread().isInterrupted()) {
            throw new Cancelled();
        }
    }

    /**
     * A thread of the mod's background work, stopped by {@link #cancel} instead of an interrupt. An interrupt that hits a thread while
     * it reads a file closes the file for every thread: if the file is a mod jar (structure templates of virtual chunks are read from
     * one), Minecraft can no longer read the data packs in it and takes every world for damaged until the game is restarted.
     */
    public static final class Worker extends Thread {
        private volatile boolean cancelled;

        public Worker(Runnable task, String name) {
            super(task, name);
        }

        /** The work running on this thread ends at its next checkpoint, and so does any work given to it later. */
        public void cancel() {
            this.cancelled = true;
        }
    }

    /** Thrown at a checkpoint of a cancelled {@link Worker}. */
    public static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("Background work cancelled", null, false, false);
        }
    }

    /** A checkpoint of background work: the work of a cancelled {@link Worker} ends here with {@link Cancelled}. */
    public static void checkpoint() {
        if (Thread.currentThread() instanceof Worker worker && worker.cancelled) {
            throw new Cancelled();
        }
    }
}
