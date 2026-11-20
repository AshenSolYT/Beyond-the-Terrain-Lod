package com.anonymous.beyondthehorizon.client.render;

import com.anonymous.beyondthehorizon.client.config.ClientConfig;
import com.anonymous.beyondthehorizon.dimension.DimensionKind;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The fog of the LOD terrain, apart from vanilla's, going on from it. Its color and the fog of water, lava, weather and effects are
 * vanilla's as they are written for the frame. The fog at the end of the view is this mod's own (see
 * the distance of the distant terrain), or the one another mod has set, which then goes on over the LOD terrain as it is; which
 * of the two is decided in FogRendererMixin ({@link #followVanilla}).
 * <p>
 * Written into a buffer laid out like vanilla's fog, which the LOD shaders take instead of vanilla's; its last value, the end of the
 * clouds, is unused by them and tells the shape of the fog instead: 1 round, 0 cylindrical like vanilla's.
 */
public final class LodFog {
    /** Far enough that a fog never starts, while staying a normal float for every GPU. */
    public static final float NONE = 1.0E9F;

    // Vanilla's fog as it was last written for its terrain, after every mod that changes it; see FogRendererMixin.
    private static final Vector4f vanillaColor = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
    private static float vanillaEnvironmentalStart = Float.MAX_VALUE;
    private static float vanillaEnvironmentalEnd = Float.MAX_VALUE;
    private static float vanillaRenderDistanceStart = Float.MAX_VALUE;
    private static float vanillaRenderDistanceEnd = Float.MAX_VALUE;
    private static float vanillaSkyEnd = Float.MAX_VALUE;
    /** Whether the fog of this frame is another mod's (or vanilla's, when chosen), which the LOD terrain takes as it is. */
    public static boolean followVanilla;
    /** Whether this mod's own fog is out of water this frame; the fog color follows it on the next, see AtmosphericFogEnvironmentMixin. */
    public static boolean ownFog;

    // Vanilla's fog of the atmosphere for this frame, before other mods change it (Sodium Extra sets its own fog distance there).
    private static boolean atmosphereSet;
    private static float atmosphereStart;
    private static float atmosphereEnd;
    private static float atmosphereSkyEnd;

    /** A new frame's fog is being set up; the fog of the atmosphere is only there when the camera is in the open air. */
    public static void startFrame() {
        atmosphereSet = false;
    }

    /** Vanilla's fog of the atmosphere, right as vanilla has set it. */
    public static void captureAtmosphere(float environmentalStart, float environmentalEnd, float skyEnd) {
        atmosphereSet = true;
        atmosphereStart = environmentalStart;
        atmosphereEnd = environmentalEnd;
        atmosphereSkyEnd = skyEnd;
    }

    /** Whether another mod has changed vanilla's fog of the atmosphere in {@code fog}. */
    public static boolean atmosphereChanged(FogData fog) {
        return atmosphereSet && (fog.environmentalStart != atmosphereStart || fog.environmentalEnd != atmosphereEnd);
    }

    /** Puts vanilla's own fog of the atmosphere back into {@code fog}, over what other mods have set. */
    public static void restoreAtmosphere(FogData fog) {
        if (atmosphereSet) {
            fog.environmentalStart = atmosphereStart;
            fog.environmentalEnd = atmosphereEnd;
            fog.skyEnd = atmosphereSkyEnd;
        }
    }

    /** Vanilla's fog for this frame, as it goes into vanilla's fog buffer. */
    public static void captureVanilla(Vector4f color, float environmentalStart, float environmentalEnd, float renderDistanceStart, float renderDistanceEnd, float skyEnd) {
        vanillaColor.set(color);
        vanillaEnvironmentalStart = environmentalStart;
        vanillaEnvironmentalEnd = environmentalEnd;
        vanillaRenderDistanceStart = renderDistanceStart;
        vanillaRenderDistanceEnd = renderDistanceEnd;
        vanillaSkyEnd = skyEnd;
    }

    public float environmentalStart;
    public float environmentalEnd;
    public float renderDistanceStart;
    public float renderDistanceEnd;
    /** Whether the fog at the end of the view is measured round instead of cylindrical. */
    public boolean round;
    private @Nullable MappableRingBuffer buffer;

    /**
     * The fog of the LOD terrain for this frame. {@code renderDistance} is vanilla's in chunks; vanilla's own fog at the edge of it
     * is told from fog a mod has set elsewhere. {@code vision}: blindness or darkness, whose fog always stays.
     */
    public void compute(ClientConfig settings, DimensionKind dimension, FogType fogType, boolean vision, int renderDistance, float lodDistance) {
        float vanillaRange = renderDistance * 16.0F;
        float end = lodDistance;
        float start = settings.fogStart(end);
        this.round = true;
        boolean inFluid = fogType != FogType.NONE;
        this.environmentalStart = vanillaEnvironmentalStart;
        this.environmentalEnd = vanillaEnvironmentalEnd;
        this.renderDistanceStart = vanillaRenderDistanceStart;
        this.renderDistanceEnd = vanillaRenderDistanceEnd;
        // In a fluid, with blindness or darkness, or with another mod's fog, vanilla's fog goes on over the LOD terrain as it is.
        if (!inFluid && !vision && !followVanilla) {
            // Fog close around the camera (rain) is vanilla's and goes on over the LOD terrain; the far one of the atmosphere and that
            // of the End would turn the LOD terrain into fog color long before its end, so they are left out: this mod's fog takes
            // their place (it is always measured round, and would hide the shape of this mod's fog).
            if (vanillaEnvironmentalEnd >= vanillaRange || dimension == DimensionKind.END) {
                this.environmentalStart = NONE;
                this.environmentalEnd = NONE;
            }
            this.renderDistanceStart = start;
            this.renderDistanceEnd = end;
        }
        // No fog out of water, or none under water (then a wall ends the view there, the terrain is not drawn); the fog of
        // blindness and darkness always stays.
        if (!vision && (fogType == FogType.NONE && settings.fogMode == ClientConfig.FOG_OFF || fogType == FogType.WATER && !settings.waterFog)) {
            this.environmentalStart = NONE;
            this.environmentalEnd = NONE;
            this.renderDistanceStart = NONE;
            this.renderDistanceEnd = NONE;
        }
    }

    /**
     * Where vanilla's fog, as other mods (Sodium Extra, Simple Fog Control) have left it, becomes solid; {@link #NONE} if it never does
     * or none is known yet. Beyond it nothing of the LOD terrain can be seen.
     */
    public static float vanillaFogEnd() {
        float end = NONE;
        if (vanillaRenderDistanceEnd > 0.0F && vanillaRenderDistanceEnd < NONE) {
            end = vanillaRenderDistanceEnd;
        }
        if (vanillaEnvironmentalEnd > 0.0F && vanillaEnvironmentalEnd < end) {
            end = vanillaEnvironmentalEnd;
        }
        return end;
    }

    /** Where the fog ends for a tile whose nearest point lies at these distances: beyond it the tile is all fog color. */
    public boolean hides(double distance, double horizontal, double vertical) {
        // Radial fog: the 3D distance to the nearest point of the tile, whatever the shape flag says.
        return distance > this.environmentalEnd || distance > this.renderDistanceEnd;
    }

    /** Writes this frame's fog for the LOD shaders; must happen before the pass that uses it is opened. */
    public GpuBufferSlice write() {
        if (this.buffer == null) {
            this.buffer = new MappableRingBuffer(() -> "FarPlane LOD fog", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, FogRenderer.FOG_UBO_SIZE);
        } else {
            this.buffer.rotate();
        }
        try (GpuBufferSlice.MappedView view = this.buffer.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                .putVec4(vanillaColor)
                .putFloat(this.environmentalStart)
                .putFloat(this.environmentalEnd)
                .putFloat(this.renderDistanceStart)
                .putFloat(this.renderDistanceEnd)
                .putFloat(vanillaSkyEnd)
                .putFloat(this.round ? 1.0F : 0.0F);
        }
        return this.buffer.currentBuffer().slice(0L, FogRenderer.FOG_UBO_SIZE);
    }

    public void close() {
        if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
        }
    }
}
