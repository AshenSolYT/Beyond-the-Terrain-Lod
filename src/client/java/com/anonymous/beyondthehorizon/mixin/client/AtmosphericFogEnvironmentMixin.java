package com.anonymous.beyondthehorizon.mixin.client;

import com.anonymous.beyondthehorizon.client.render.LodFog;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's fog of the atmosphere. Its own, before other mods change it at the same point (a lower priority than the default runs
 * first): this mod's fog puts it back over theirs, and tells their fog from vanilla's by it. See FogRendererMixin.
 * <p>
 * The color of the fog is vanilla's own, as it is: the terrain beyond the render distance has the same fog color as vanilla's.
 */
@Mixin(value = AtmosphericFogEnvironment.class, priority = 500)
abstract class AtmosphericFogEnvironmentMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void beyondthehorizon$captureAtmosphere(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
        LodFog.captureAtmosphere(fog.environmentalStart, fog.environmentalEnd, fog.skyEnd);
    }
}
