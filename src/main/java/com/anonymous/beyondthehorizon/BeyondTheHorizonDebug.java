package com.anonymous.beyondthehorizon;

/**
 * Whether this is the debug build, which writes detailed performance and settings logs to latest.log. The debug jar carries a marker
 * file (see build.gradle, {@code -Pdebug_build}); the property {@code -Dfarplane.debug=true} turns it on in any build.
 */
public final class BeyondTheHorizonDebug {
    public static final boolean ENABLED = Boolean.getBoolean("farplane.debug")
        || BeyondTheHorizonDebug.class.getResource("/" + BeyondTheHorizonConstants.MOD_ID + "-debug.marker") != null;

    /** Switches for finding what costs frames, set with {@code -Dbth.nodepth=true} and the like; all off by default. */
    public static final boolean NO_DEPTH = Boolean.getBoolean("bth.nodepth");
    public static final boolean NO_SURVEY = Boolean.getBoolean("bth.nosurvey");
    public static final boolean NO_LEARN = Boolean.getBoolean("bth.nolearn");

    private BeyondTheHorizonDebug() {
    }
}
