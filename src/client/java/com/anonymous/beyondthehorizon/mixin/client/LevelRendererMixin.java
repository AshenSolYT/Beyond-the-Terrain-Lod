package com.anonymous.beyondthehorizon.mixin.client;

import com.anonymous.beyondthehorizon.client.BeyondTheHorizonClient;
import com.anonymous.beyondthehorizon.client.render.LodRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the pass of the distant terrain to the frame, right before vanilla's main pass. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    /** Whether this frame's depth pass is in the frame graph already, so that only one of the places below adds it. */
    @Unique
    private boolean beyondthehorizon$depthPassAdded;

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Z)V"
        )
    )
    private void beyondthehorizon$addTerrainPass(
        GraphicsResourceAllocator resourceAllocator,
        boolean renderOutline,
        CameraRenderState cameraState,
        GpuBufferSlice terrainFog,
        Vector4f fogColor,
        boolean shouldRenderSky,
        boolean consistentDepthRequired,
        CallbackInfo ci,
        @Local FrameGraphBuilder frame
    ) {
        this.beyondthehorizon$depthPassAdded = false;
        LodRenderer renderer = BeyondTheHorizonClient.renderer();
        if (renderer == null) {
            return;
        }
        BeyondTheHorizonClient.manager().uploadBuilt();
        if (!renderer.shouldRender(cameraState)) {
            return;
        }
        FramePass pass = frame.addPass("beyondthehorizon_terrain");
        ResourceHandle<RenderTarget> main = pass.readsAndWrites(this.targets.main);
        this.targets.main = main;
        pass.executes(() -> renderer.render(main.get(), cameraState, terrainFog));
    }

    /**
     * Right after vanilla's main pass: the depth of the distant terrain goes back into the frame, which vanilla's pass has its own depth
     * in, so that what is drawn after it (the clouds, the weather, the translucent layers) is hidden by distant mountains instead of
     * being seen in front of them. In 26.3 the clouds are part of the translucent step after the main pass, so this is the earliest
     * place that is surely before them; mods that draw their own clouds there, like Cirrus, are covered as well.
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void beyondthehorizon$addDepthPassAfterMain(
        GraphicsResourceAllocator resourceAllocator,
        boolean renderOutline,
        CameraRenderState cameraState,
        GpuBufferSlice terrainFog,
        Vector4f fogColor,
        boolean shouldRenderSky,
        boolean consistentDepthRequired,
        CallbackInfo ci,
        @Local FrameGraphBuilder frame
    ) {
        this.beyondthehorizon$addDepthPass(cameraState, frame);
    }

    @Unique
    private void beyondthehorizon$addDepthPass(CameraRenderState cameraState, FrameGraphBuilder frame) {
        LodRenderer renderer = BeyondTheHorizonClient.renderer();
        if (this.beyondthehorizon$depthPassAdded || renderer == null || !renderer.shouldRender(cameraState)) {
            return;
        }
        this.beyondthehorizon$depthPassAdded = true;
        FramePass pass = frame.addPass("beyondthehorizon_depth");
        ResourceHandle<RenderTarget> main = pass.readsAndWrites(this.targets.main);
        this.targets.main = main;
        pass.executes(() -> renderer.renderDepth(main.get(), cameraState));
    }

    /**
     * Older versions of the game had a pass of their own for the clouds; if this one still exists, the depth goes in right before it,
     * unless it was added after the main pass already. If vanilla draws the clouds elsewhere this does nothing (require = 0).
     */
    @Inject(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;addCloudsPass"),
        require = 0
    )
    private void beyondthehorizon$addDepthPassBeforeClouds(
        GraphicsResourceAllocator resourceAllocator,
        boolean renderOutline,
        CameraRenderState cameraState,
        GpuBufferSlice terrainFog,
        Vector4f fogColor,
        boolean shouldRenderSky,
        boolean consistentDepthRequired,
        CallbackInfo ci,
        @Local FrameGraphBuilder frame
    ) {
        this.beyondthehorizon$addDepthPass(cameraState, frame);
    }
}
