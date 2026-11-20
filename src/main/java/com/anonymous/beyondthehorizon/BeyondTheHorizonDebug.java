package com.anonymous.beyondthehorizon;

/**
 * Whether this is the debug build, which writes detailed performance and settings logs to latest.log. The debug jar carries a marker
 * file (see build.gradle, {@code -Pdebug_build}); the property {@code -Dbtt.debug=true} turns it on in any build.
 */
public final class BeyondTheHorizonDebug {
    public static final boolean ENABLED = Boolean.getBoolean("btt.debug")
        || BeyondTheHorizonDebug.class.getResource("/" + BeyondTheHorizonConstants.MOD_ID + "-debug.marker") != null;

    /** Switches for finding what costs frames, set with {@code -Dbth.nodepth=true} and the like; all off by default. */
    public static final boolean NO_DEPTH = Boolean.getBoolean("bth.nodepth");
    /** The ground (smooth and stepped), the water and the pixel filter's shader, one by one, for finding what the graphics card spends its time on. */
    public static final boolean NO_GROUND = Boolean.getBoolean("bth.noground");
    public static final boolean NO_WATER = Boolean.getBoolean("bth.nowater");
    public static final boolean NO_PIXEL = Boolean.getBoolean("bth.nopixel");
    /** Keeps the pixel filter on tiles whose pixels are smaller than a screen pixel, which are drawn smooth otherwise, see LodRenderer. */
    /** No terrain pass and no depth pass at all, while the manager, the server and the tile loading go on: tells passes from background work. */
    public static final boolean NO_RENDER = Boolean.getBoolean("bth.norender");
    /** The terrain pass runs but vanilla's depth is not cleared after it (the picture breaks): tells what that clear costs. */
    public static final boolean NO_CLEAR = Boolean.getBoolean("bth.noclear");
    /** Every draw binds a slice of its own again instead of one buffer with a base vertex: tells whether base vertices work as they should. */
    public static final boolean NO_BASE_VERTEX = Boolean.getBoolean("bth.nobasevertex");
    public static final boolean PIXEL_ALL = Boolean.getBoolean("bth.pixelall");

    private BeyondTheHorizonDebug() {
    }
}
