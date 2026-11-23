package com.anonymous.beyondthehorizon.mixin.client;

import com.anonymous.beyondthehorizon.BeyondTheHorizonDebug;
import com.anonymous.beyondthehorizon.client.BeyondTheHorizonClient;
import com.anonymous.beyondthehorizon.client.compat.IrisCompat;
import com.anonymous.beyondthehorizon.client.lod.LodManager;
import net.minecraft.client.renderer.Projection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The far plane of the game's perspective projections, under a shader pack with this mod's terrain in view. Vanilla's is a few times
 * its render distance, so a shader pack, which works out how far a pixel is from its depth with the game's projection, could not tell
 * the distance of terrain beyond it (and the terrain would be cut off by it). With the far plane out where the terrain ends, the
 * terrain is drawn with the very projection the pack reads its depth with, see LodRenderer, and the pack fogs and shades it by its
 * real distance. Only longer, never shorter. The method and its argument order are the ones Iris itself hooks
 * ({@code UndoReverseZFour}); {@code require = 0}, so a version of the game without them is left alone.
 */
@Mixin(Projection.class)
abstract class ProjectionFarMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Terrain");
    private static float beyondthehorizon$lastLogged;

    /** The {@code zFar} argument (the second float of {@code setupPerspective(zNear, zFar, fov, width, height)}). */
    @ModifyVariable(method = "setupPerspective", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private float beyondthehorizon$farForTerrain(float zFar) {
        LodManager manager = BeyondTheHorizonClient.manager();
        if (manager == null || !manager.hasVisibleTerrain() || !IrisCompat.shadersActive()) {
            return zFar;
        }
        float far = manager.packFar();
        if (!(far > zFar) || !Float.isFinite(far)) {
            return zFar;
        }
        if (BeyondTheHorizonDebug.ENABLED && beyondthehorizon$lastLogged != zFar) {
            beyondthehorizon$lastLogged = zFar;
            LOGGER.info("[BTH far hook] Projection.setupPerspective zFar {} -> {}", zFar, far);
        }
        return far;
    }
}
