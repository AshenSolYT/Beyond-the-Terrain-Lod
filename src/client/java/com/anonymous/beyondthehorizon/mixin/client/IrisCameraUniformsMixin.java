package com.anonymous.beyondthehorizon.mixin.client;

import com.anonymous.beyondthehorizon.client.BeyondTheHorizonClient;
import com.anonymous.beyondthehorizon.client.lod.LodManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The render distance a shader pack is told. Iris gives packs the uniform {@code far} as the render distance in blocks, and packs
 * fade the world into fog and sky by it, so with this mod's terrain drawn far beyond that distance the packs fog it out at the
 * edge of vanilla's chunks. While the terrain is visible the distance it reaches is given instead (a longer one only: never less
 * than the game's own). Iris is not needed to run: the class is looked up by its name, and the mixin is skipped without it
 * ({@code @Pseudo}) and, with {@code require = 0}, when this version of Iris has no such method.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms", remap = false)
abstract class IrisCameraUniformsMixin {
    /**
     * {@code -Dbth.irisfar=off} leaves the pack's {@code far} at the game's render distance. For finding out whether a pack's fog goes
     * wrong with the longer one: a pack that marches its volumetric fog in a fixed number of steps along the ray to {@code far} gets
     * steps that many times longer.
     */
    private static final boolean KEEP_GAME_FAR = "off".equalsIgnoreCase(System.getProperty("bth.irisfar", "on"));

    @Inject(method = "getRenderDistanceInBlocks", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void beyondthehorizon$lodDistance(CallbackInfoReturnable<Integer> cir) {
        LodManager manager = BeyondTheHorizonClient.manager();
        if (KEEP_GAME_FAR || manager == null || !manager.hasVisibleTerrain()) {
            return;
        }
        int lod = (int) manager.activeLodDistance();
        if (lod > cir.getReturnValueI()) {
            cir.setReturnValue(lod);
        }
    }
}
