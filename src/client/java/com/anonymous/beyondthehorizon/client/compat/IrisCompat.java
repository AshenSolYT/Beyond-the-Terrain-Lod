package com.anonymous.beyondthehorizon.client.compat;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iris shader packs, through Iris's own API (net.irisshaders.iris.api.v0.IrisApi, by reflection: Iris is not needed to run, and the
 * mod does not link against it). Iris has an integration for Distant Horizons (its events, its own programs "dh_*" in the pack) but none
 * for other level-of-detail mods; what it offers them is {@code assignPipeline(RenderPipeline, IrisProgram)}: a pipeline given there is
 * drawn with the pack's program of that kind instead of its own shaders. The terrain's vertices are a position and a color, which
 * is the format of the pack's "basic" program (gbuffers_basic), so every pipeline of the terrain is given that one.
 * <p>
 * With a pack in use the terrain's own shader features do not run (the pixelated filter, the fog, the cut inside the distance of
 * vanilla's chunks, the depth-only pass for the clouds): the renderer takes that into account, see LodRenderer.
 */
public final class IrisCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Terrain");
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("iris");

    private static boolean initialized;
    private static boolean broken;
    private static Object api;
    private static Method inUse;
    private static Method reverseZ;
    private static Method assign;
    private static Object basic;
    private static final Set<RenderPipeline> ASSIGNED = new HashSet<>();

    private IrisCompat() {
    }

    /** Whether a shader pack is being used by Iris right now. */
    public static boolean shadersActive() {
        if (!PRESENT || !init()) {
            return false;
        }
        try {
            return (boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
            return false;
        }
    }

    /** Whether the depth runs backwards (the far plane at 0) while the pack draws, like vanilla's; true when it cannot be told. */
    public static boolean reverseZ() {
        if (!PRESENT || !init() || reverseZ == null) {
            return true;
        }
        try {
            return (boolean) reverseZ.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
            return true;
        }
    }

    /** Has Iris draw the pipeline with the pack's basic program (once per pipeline). */
    public static void assignBasic(RenderPipeline pipeline) {
        if (!PRESENT || !init() || !ASSIGNED.add(pipeline)) {
            return;
        }
        try {
            assign.invoke(api, pipeline, basic);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    private static boolean init() {
        if (broken) {
            return false;
        }
        if (initialized) {
            return true;
        }
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Class<?> programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            api = apiClass.getMethod("getInstance").invoke(null);
            inUse = apiClass.getMethod("isShaderPackInUse");
            assign = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
            basic = Enum.valueOf(programClass.asSubclass(Enum.class), "BASIC");
            try {
                reverseZ = apiClass.getMethod("isReverseZDuringShaders");
            } catch (NoSuchMethodException e) {
                reverseZ = null;
            }
            initialized = true;
            LOGGER.info("Iris found: shader packs are supported through its pipeline API");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    private static void fail(Throwable e) {
        if (!broken) {
            broken = true;
            LOGGER.warn("Iris is installed but its API could not be used, shader packs are not supported", e);
        }
    }
}
