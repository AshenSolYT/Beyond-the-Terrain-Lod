package com.anonymous.beyondthehorizon.client.render;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.BeyondTheHorizonDebug;
import com.anonymous.beyondthehorizon.client.config.ClientConfig;
import com.anonymous.beyondthehorizon.client.lod.LodManager;
import com.anonymous.beyondthehorizon.client.lod.MeshBuilder;
import com.anonymous.beyondthehorizon.client.lod.PerfStats;
import com.anonymous.beyondthehorizon.client.lod.Tile;
import com.anonymous.beyondthehorizon.dimension.DimensionKind;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the distant terrain in a pass of its own before vanilla's, into the frame vanilla draws over: vanilla's terrain, entities
 * and the rest come on top of it, so the terrain needs no cutting out where vanilla draws, and no tile is ever split into parts.
 * One draw per tile for the ground, one more for the water of tiles that have any, from one big vertex buffer and one index buffer.
 * Nothing here needs more than OpenGL 3.3: no instancing, storage buffers, compute or indirect draws.
 */
public final class LodRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Terrain");

    public static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .build();
    /** Brightness noise of the pixelated filter, as a share: 10 %. */
    private static final float PIXEL_NOISE = 0.035F;
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(BeyondTheHorizonConstants.MOD_ID, "core/lod_terrain");

    private static RenderPipeline.Builder base(String name, String... defines) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(BeyondTheHorizonConstants.MOD_ID, "pipeline/" + name))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withVertexBinding(0, FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(true);
        for (String define : defines) {
            builder = builder.withShaderDefine(define);
        }
        return builder;
    }

    private static RenderPipeline ground(String name, String... defines) {
        return RenderPipelines.registerOptional(
            base(name, defines).withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(DepthStencilState.DEFAULT).build()
        );
    }

    private static RenderPipeline water(String name, String... defines) {
        return RenderPipelines.registerOptional(
            base(name, defines)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .build()
        );
    }

    /** The ground blends the colors between vertices; the pixelated one reads them on a finer grid, with noise. */
    public static final RenderPipeline GROUND = ground("lod_ground");
    public static final RenderPipeline WATER = water("lod_water");
    /** The ground drawn into the depth only (transparent, with the blend keeping the color), for what vanilla draws after the terrain: the clouds. */
    public static final RenderPipeline GROUND_DEPTH = RenderPipelines.registerOptional(
        base("lod_depth", "DEPTH_ONLY").withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withDepthStencilState(DepthStencilState.DEFAULT).build()
    );
    /** The stepped columns into the depth only: their walls are seen from both sides. */
    public static final RenderPipeline STEPS_DEPTH = RenderPipelines.registerOptional(
        base("lod_depth_steps", "DEPTH_ONLY").withCull(false).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withDepthStencilState(DepthStencilState.DEFAULT).build()
    );
    public static final RenderPipeline GROUND_PIXEL = ground("lod_ground_pixel", "PIXEL_COLOR");
    /** The stepped columns: flat colors with the faces wound outward (see MeshBuilder.steps), so what faces away is culled. */
    public static final RenderPipeline STEPS = ground("lod_steps");
    /** The approximated trees: flat shapes seen from both sides. */
    public static final RenderPipeline TREES = RenderPipelines.registerOptional(
        base("lod_trees", "SPRITES").withCull(false).withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(DepthStencilState.DEFAULT).build()
    );

    /** Cameras higher than this are taken as possibly above the clouds (which are at 192 in the overworld), where the depth is not cut down. */
    private static final double DEPTH_CLOUD_CAMERA_MAX = 180.0;
    private final LodManager manager;
    private final LodFog fog = new LodFog();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f cull = new Matrix4f();
    private final Matrix4f identity = new Matrix4f();
    private final List<Vector4f> mods = new ArrayList<>();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final List<Vector3f> offsets = new ArrayList<>();
    private @Nullable ProjectionMatrixBuffer projectionBuffer;
    private @Nullable GpuBuffer sharedIndices;
    private boolean failed;
    // What this frame's terrain pass drew, for the depth pass that follows it, see renderDepth.
    private GpuBufferSlice[] frameSlices;
    /** What is bound on the pass now, so that a buffer is bound once for the tiles that lie in it, see drawTile. */
    private @Nullable GpuBuffer boundVertex;
    private @Nullable GpuBuffer boundIndex;
    /** The tiles of this frame that take the pixelated ground and the smooth one, as indices into the sorted tiles. */
    private int[] pixelList = new int[0];
    private int[] smoothList = new int[0];
    /** Transform arrays by length, made once: the writer wants the exact length and the tile count changes by a few. */
    private final DynamicGpuData.Transform[][] transformPool = new DynamicGpuData.Transform[1024][];
    private final List<Vector4f> treeMods = new ArrayList<>();
    private GpuBufferSlice frameProjection;
    private GpuBufferSlice frameFog;
    private int frameCount;
    private Tile[] visible = new Tile[256];
    private double[] distance = new double[256];

    public LodRenderer(LodManager manager) {
        this.manager = manager;
    }

    public boolean shouldRender(CameraRenderState camera) {
        return !this.failed
            && !BeyondTheHorizonDebug.NO_RENDER
            && this.manager.isActive()
            && this.manager.drawList().length > 0
            && (camera.fogType == FogType.NONE || camera.fogType == FogType.WATER)
            && !camera.isPanoramicMode
            && camera.projectionMatrix.m23() != 0.0F;
    }

    /** Draws the terrain; {@code vanillaFog} is not used, the terrain has fog of its own. */
    public void render(RenderTarget target, CameraRenderState camera, GpuBufferSlice vanillaFog) {
        long prepStart = System.nanoTime();
        this.frameCount = 0;
        Minecraft minecraft = Minecraft.getInstance();
        ClientConfig cfg = this.manager.config();
        int pixel = cfg.pixelFilter;
        CompiledRenderPipeline smoothGround = this.compiled(GROUND);
        CompiledRenderPipeline pixelGround = pixel > 0 && !BeyondTheHorizonDebug.NO_PIXEL ? this.compiled(GROUND_PIXEL) : null;
        CompiledRenderPipeline ground = pixelGround != null ? pixelGround : smoothGround;
        CompiledRenderPipeline trees = cfg.treeLevel > 0 ? this.compiled(TREES) : null;
        // The overlay pass: the distant structures, which are meshes apart from the terrain's.
        CompiledRenderPipeline structures = cfg.structureRange > 0 ? this.compiled(TREES) : null;
        CompiledRenderPipeline water = this.compiled(WATER);
        CompiledRenderPipeline stepPipeline = this.compiled(STEPS);
        if (ground == null || water == null || target.getDepthTextureView() == null || minecraft.level == null) {
            return;
        }
        double camX = camera.pos.x;
        double camY = camera.pos.y;
        double camZ = camera.pos.z;
        Matrix4f base = camera.projectionMatrix;
        float fovY = 2.0F * (float) Math.atan(1.0 / base.m11());
        float aspect = base.m11() / base.m00();
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        // The depth runs backwards, as vanilla's: the far plane is given as the near one.
        float far = this.manager.activeLodDistance() * 2.0F + (float) Math.abs(camY) + 4096.0F;
        this.projection.setPerspective(fovY, aspect, far, 0.05F, zeroToOne);
        this.cull.set(this.projection).mul(camera.viewRotationMatrix);
        this.frustum.set(this.cull, false);

        DimensionKind dimension = DimensionKind.of(minecraft.level);
        boolean vision = minecraft.player != null && (minecraft.player.hasEffect(MobEffects.BLINDNESS) || minecraft.player.hasEffect(MobEffects.DARKNESS));
        this.fog.compute(this.manager.config(), dimension, camera.fogType, vision, minecraft.options.getEffectiveRenderDistance(), this.manager.activeLodDistance());

        // Which tiles are seen, nearest first.
        Tile[] list = this.manager.drawList();
        // The meshes of the distant structure layer are culled, sorted and moved like the terrain's tiles, and drawn by a pass of their own.
        if (structures != null) {
            Tile[] overlay = cfg.structureRange > 0 ? this.manager.structureTiles() : new Tile[0];
            if (overlay.length > 0) {
                Tile[] merged = new Tile[list.length + overlay.length];
                System.arraycopy(list, 0, merged, 0, list.length);
                System.arraycopy(overlay, 0, merged, list.length, overlay.length);
                list = merged;
            }
        }
        if (this.visible.length < list.length) {
            this.visible = new Tile[list.length];
            this.distance = new double[list.length];
        }
        int count = 0;
        for (Tile tile : list) {
            float x0 = (float) (tile.minX - camX);
            float z0 = (float) (tile.minZ - camZ);
            float y0 = (float) (tile.minY - camY);
            float size = (float) tile.size;
            if (!this.frustum.testAab(x0, y0, z0, x0 + size, (float) (tile.maxY - camY), z0 + size)) {
                continue;
            }
            double dx = Math.max(0.0, Math.max(x0, -(x0 + size)));
            double dz = Math.max(0.0, Math.max(z0, -(z0 + size)));
            double dy = Math.max(0.0, Math.max(y0, -(tile.maxY - camY)));
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double d = Math.sqrt(horizontal * horizontal + dy * dy);
            if (this.fog.hides(d, horizontal, dy)) {
                continue;
            }
            this.visible[count] = tile;
            this.distance[count++] = d;
        }
        if (count == 0) {
            return;
        }
        sortByDistance(this.visible, this.distance, count);

        // One transform per tile: where its corner is, seen from the camera.
        DynamicGpuData.Transform[] transforms = this.transformArray(count);
        while (this.offsets.size() < count) {
            this.offsets.add(new Vector3f());
            this.mods.add(new Vector4f());
            this.treeMods.add(new Vector4f());
        }
        for (int i = 0; i < count; i++) {
            Tile tile = this.visible[i];
            Vector3f offset = this.offsets.get(i).set((float) (tile.minX - camX), (float) -camY, (float) (tile.minZ - camZ));
            // The color modulator is not used for color: it carries the size of a pixel in blocks and the noise to the shader.
            Vector4f mod = this.mods.get(i).set(0.0F, (float) (tile.size / MeshBuilder.CELLS) / Math.max(1, pixel), PIXEL_NOISE, 1.0F);
            transforms[i] = new DynamicGpuData.Transform(camera.viewRotationMatrix, mod, offset, this.identity);
        }
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        // The same again for the trees and the structures, with the distance of vanilla's chunks in the modulator: the shader leaves out what
        // lies inside it, per fragment, so a tile that stands half in it still shows its trees on the far half.
        double vanillaRange = minecraft.options.getEffectiveRenderDistance() * 16.0;
        for (int i = 0; i < count; i++) {
            Vector4f mod = this.mods.get(i);
            transforms[i] = new DynamicGpuData.Transform(camera.viewRotationMatrix, this.treeMods.get(i).set((float) vanillaRange, mod.y, mod.z, mod.w), this.offsets.get(i), this.identity);
        }
        GpuBufferSlice[] treeSlices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        Arrays.fill(transforms, null);
        if (this.projectionBuffer == null) {
            this.projectionBuffer = new ProjectionMatrixBuffer("beyond the horizon");
        }
        GpuBufferSlice projectionSlice = this.projectionBuffer.getBuffer(this.projection);
        GpuBufferSlice fogSlice = this.fog.write();
        GpuBuffer shared = this.sharedIndices();

        this.frameSlices = slices;
        this.frameProjection = projectionSlice;
        this.frameFog = fogSlice;
        this.frameCount = count;
        this.gauges(count, trees != null);
        if (BeyondTheHorizonDebug.ENABLED) {
            this.logStats(count, cfg);
        }
        // The ground of the tiles by pipeline, before the pass, so that each pipeline is bound once: the pixelated filter shows nothing where
        // its pixels are smaller than the ones of the screen (the noise would only be grain), so the far tiles are drawn smooth. Both lists
        // keep the order of the sorted tiles, nearest first.
        float screenPerBlock = minecraft.getWindow().getHeight() * base.m11() * 0.5F;
        boolean splitting = pixelGround != null && smoothGround != null && !BeyondTheHorizonDebug.PIXEL_ALL;
        if (this.pixelList.length < count) {
            this.pixelList = new int[count];
            this.smoothList = new int[count];
        }
        int pixelCount = 0;
        int smoothCount = 0;
        boolean anySteps = false;
        for (int i = 0; i < count; i++) {
            Tile tile = this.visible[i];
            if (tile.part == null || tile.structure) {
                continue;
            }
            if (tile.stepIndexCount > 0) {
                anySteps = true;
                continue;
            }
            if (BeyondTheHorizonDebug.NO_GROUND) {
                continue;
            }
            boolean pixelTile = pixelGround != null;
            if (splitting) {
                double pixelBlocks = tile.size / (double) MeshBuilder.CELLS / pixel;
                pixelTile = pixelBlocks * screenPerBlock / Math.max(1.0, this.distance[i]) >= 1.0;
            }
            if (pixelTile) {
                this.pixelList[pixelCount++] = i;
            } else {
                this.smoothList[smoothCount++] = i;
            }
        }
        long passStart = System.nanoTime();
        PerfStats.add(PerfStats.Timer.PREP, passStart - prepStart);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
            () -> "Beyond the Terrain terrain", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.of(0.0)
        )) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projectionSlice);
            pass.setUniform("Fog", fogSlice);
            CompiledRenderPipeline current = pixelCount > 0 ? pixelGround : smoothGround;
            if (current == null) {
                current = ground;
            }
            pass.setPipeline(current);
            // The lightmap once for the whole pass: every pipeline reads it the same way.
            pass.setUniform("Sampler2", minecraft.gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            this.resetBindings();
            this.drawGround(pass, this.pixelList, pixelCount, slices, shared);
            if (smoothCount > 0) {
                if (current != smoothGround) {
                    pass.setPipeline(smoothGround);
                    this.resetBindings();
                }
                this.drawGround(pass, this.smoothList, smoothCount, slices, shared);
            }
            // The ground of the tiles that have stepped columns: flat colors, their faces wound outward, so culled like the ground's.
            if (anySteps && stepPipeline != null) {
                pass.setPipeline(stepPipeline);
                this.resetBindings();
                for (int i = 0; i < count; i++) {
                    Tile tile = this.visible[i];
                    if (BeyondTheHorizonDebug.NO_GROUND || tile.part == null || tile.structure || tile.stepIndexCount == 0) {
                        continue;
                    }
                    pass.setUniform("DynamicTransforms", slices[i]);
                    this.drawTile(pass, tile, tile.stepVertexOffset, tile.stepVertexCount * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                        (tile.part.offset + tile.stepIndexOffset) / 2, tile.stepIndexCount);
                }
            }
            // The trees of the tiles that have any. Inside the distance of vanilla's chunks the trees are the real ones: the approximated
            // ones would stand in the air over them, and close to the camera while the chunks load they would fill the view. The shader
            // leaves them out there, fragment by fragment (see treeSlices); here only the tiles that lie wholly inside are not drawn.
            if (trees != null) {
                pass.setPipeline(trees);
                this.resetBindings();
                for (int i = 0; i < count; i++) {
                    Tile tile = this.visible[i];
                    if (tile.part == null || tile.structure || tile.treeIndexCount == 0 || this.insideRange(tile, camX, camZ, vanillaRange)) {
                        continue;
                    }
                    pass.setUniform("DynamicTransforms", treeSlices[i]);
                    this.drawTile(pass, tile, tile.treeVertexOffset, tile.treeVertexCount * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                        (tile.part.offset + tile.treeIndexOffset) / 2, tile.treeIndexCount);
                }
            }
            // The distant structures: rough boxes of villages and the like, flat colors, drawn with the trees' pipeline. Inside the distance
            // of vanilla's chunks the real buildings are drawn instead.
            if (structures != null) {
                pass.setPipeline(structures);
                this.resetBindings();
                for (int i = 0; i < count; i++) {
                    Tile tile = this.visible[i];
                    if (tile.part == null || !tile.structure || tile.treeIndexCount == 0 || this.insideRange(tile, camX, camZ, vanillaRange)) {
                        continue;
                    }
                    pass.setUniform("DynamicTransforms", treeSlices[i]);
                    this.drawTile(pass, tile, tile.treeVertexOffset, tile.treeVertexCount * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                        (tile.part.offset + tile.treeIndexOffset) / 2, tile.treeIndexCount);
                }
            }
            // The water, farthest first, over the ground and not into the depth.
            pass.setPipeline(water);
            this.resetBindings();
            for (int i = count - 1; i >= 0; i--) {
                Tile tile = this.visible[i];
                if (BeyondTheHorizonDebug.NO_WATER || tile.part == null || tile.waterIndexCount == 0) {
                    continue;
                }
                pass.setUniform("DynamicTransforms", slices[i]);
                this.drawTile(pass, tile, tile.waterVertexOffset, MeshBuilder.GRID * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                    (tile.part.offset + tile.waterIndexOffset) / 2, tile.waterIndexCount);
            }
        }
        // Vanilla draws over the terrain with a depth buffer of its own.
        if (!BeyondTheHorizonDebug.NO_CLEAR) {
            encoder.clearDepthTexture(target.getDepthTexture(), 0.0);
        }
        PerfStats.add(PerfStats.Timer.TERRAIN, System.nanoTime() - passStart);
    }

    /** For the F3 screen: what this frame draws. */
    private void gauges(int count, boolean treesOn) {
        int ground = 0;
        int treeMeshes = 0;
        int structureMeshes = 0;
        long treeVertices = 0L;
        long structureVertices = 0L;
        for (int i = 0; i < count; i++) {
            Tile tile = this.visible[i];
            if (tile == null || tile.part == null) {
                continue;
            }
            if (tile.structure) {
                structureMeshes++;
                structureVertices += tile.treeVertexCount;
            } else {
                ground++;
                if (treesOn && tile.treeIndexCount > 0) {
                    treeMeshes++;
                    treeVertices += tile.treeVertexCount;
                }
            }
        }
        PerfStats.groundDraws = ground;
        PerfStats.treeMeshes = treeMeshes;
        PerfStats.treeVertices = treeVertices;
        PerfStats.structureMeshes = structureMeshes;
        PerfStats.structureVertices = structureVertices;
    }

    /**
     * Draws the ground of this frame's terrain again into the depth only, over what vanilla has drawn: the terrain is drawn before
     * vanilla's pass, whose depth is its own, so what vanilla draws after the terrain (the clouds) would otherwise be seen in front
     * of distant mountains. The color stays as it is; where vanilla's chunks are nearer they keep their depth.
     */
    private long lastStats;

    /** With the debug flag: every five seconds, what is drawn, so that the cost of the layers can be told apart. */
    private void logStats(int count, ClientConfig cfg) {
        long nowNs = System.nanoTime();
        if (nowNs - this.lastStats < 5_000_000_000L) {
            return;
        }
        this.lastStats = nowNs;
        int terrain = 0;
        int structures = 0;
        long structureVertices = 0L;
        for (int i = 0; i < count; i++) {
            Tile tile = this.visible[i];
            if (tile.structure) {
                structures++;
                structureVertices += tile.treeVertexCount;
            } else {
                terrain++;
            }
        }
        LOGGER.info(
            "[BTH render] visible: rough terrain {} tiles, structures {} meshes ({} vertices)",
            terrain, structures, structureVertices
        );
    }

    public void renderDepth(RenderTarget target, CameraRenderState camera) {
        if (BeyondTheHorizonDebug.NO_DEPTH || this.frameCount == 0 || this.frameSlices == null || this.failed || target.getDepthTextureView() == null) {
            return;
        }
        CompiledRenderPipeline depth = this.compiled(GROUND_DEPTH);
        Minecraft minecraft = Minecraft.getInstance();
        if (depth == null || minecraft.level == null) {
            return;
        }
        long depthStart = System.nanoTime();
        // Only what the depth is for: the clouds and the like are behind terrain only where the view runs upward through it, so terrain that
        // lies wholly under the camera (and the camera under the clouds) cannot hide any. With nothing left the pass is not made at all.
        double camY = camera.pos.y;
        double needAbove = camY <= DEPTH_CLOUD_CAMERA_MAX ? camY : Double.NEGATIVE_INFINITY;
        int needed = 0;
        for (int i = 0; i < this.frameCount; i++) {
            Tile tile = this.visible[i];
            if (tile != null && tile.part != null && !tile.structure && tile.maxY > needAbove) {
                needed++;
            }
        }
        if (needed == 0) {
            PerfStats.add(PerfStats.Timer.DEPTH, System.nanoTime() - depthStart);
            return;
        }
        GpuBuffer shared = this.sharedIndices();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(
            () -> "Beyond the Terrain depth", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty()
        )) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", this.frameProjection);
            pass.setUniform("Fog", this.frameFog);
            pass.setPipeline(depth);
            pass.setUniform("Sampler2", minecraft.gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            this.resetBindings();
            for (int i = 0; i < this.frameCount; i++) {
                Tile tile = this.visible[i];
                if (tile == null || tile.part == null) {
                    continue;
                }
                if (tile.maxY <= needAbove) {
                    continue;
                }
                if (tile.structure || tile.stepIndexCount > 0) {
                    // The overlay meshes (structures, block-level tiles) put no depth in: it would cost as much again to draw them twice.
                    // The stepped tiles are drawn below.
                    continue;
                }
                pass.setUniform("DynamicTransforms", this.frameSlices[i]);
                if (tile.ownGroundIndexOffset < 0) {
                    this.drawTile(pass, tile, 0, MeshBuilder.GROUND_VERTICES * MeshBuilder.VERTEX_SIZE, shared, 0, MeshBuilder.SHARED_INDEX_COUNT);
                } else {
                    this.drawTile(pass, tile, 0, MeshBuilder.GROUND_VERTICES * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                        (tile.part.offset + tile.ownGroundIndexOffset) / 2, tile.groundIndexCount);
                }
            }
            CompiledRenderPipeline stepsDepth = this.compiled(STEPS_DEPTH);
            if (stepsDepth != null) {
                pass.setPipeline(stepsDepth);
                this.resetBindings();
                for (int i = 0; i < this.frameCount; i++) {
                    Tile tile = this.visible[i];
                    if (tile == null || tile.part == null || tile.structure || tile.stepIndexCount == 0 || tile.maxY <= needAbove) {
                        continue;
                    }
                    pass.setUniform("DynamicTransforms", this.frameSlices[i]);
                    this.drawTile(pass, tile, tile.stepVertexOffset, tile.stepVertexCount * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                        (tile.part.offset + tile.stepIndexOffset) / 2, tile.stepIndexCount);
                }
            }
        }
        PerfStats.add(PerfStats.Timer.DEPTH, System.nanoTime() - depthStart);
    }

    /** Forgets what is bound: after a new pipeline the buffers are bound again, a few calls a frame. */
    private void resetBindings() {
        this.boundVertex = null;
        this.boundIndex = null;
    }

    /** Draws the ground of the tiles of a list, see {@link #drawTile}. */
    private void drawGround(RenderPass pass, int[] list, int n, GpuBufferSlice[] slices, GpuBuffer shared) {
        for (int k = 0; k < n; k++) {
            int i = list[k];
            Tile tile = this.visible[i];
            pass.setUniform("DynamicTransforms", slices[i]);
            if (tile.ownGroundIndexOffset < 0) {
                this.drawTile(pass, tile, 0, MeshBuilder.GROUND_VERTICES * MeshBuilder.VERTEX_SIZE, shared, 0, MeshBuilder.SHARED_INDEX_COUNT);
            } else {
                this.drawTile(pass, tile, 0, MeshBuilder.GROUND_VERTICES * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                    (tile.part.offset + tile.ownGroundIndexOffset) / 2, tile.groundIndexCount);
            }
        }
    }

    /**
     * Draws one range of a tile's mesh. The buffer of the arena block the tile lies in is bound whole, once for as long as the tiles
     * come from it, and the range is told by the base vertex that is added to the indices; the vertex offsets of a mesh are multiples
     * of the vertex size for this (see MeshBuilder.build). With -Dbth.nobasevertex=true every tile binds a slice of its own, as before.
     */
    private void drawTile(RenderPass pass, Tile tile, int vertexOffset, int vertexBytes, GpuBuffer indexBuffer, int firstIndex, int indexCount) {
        VertexArena.Part part = tile.part;
        int baseVertex = 0;
        if (BeyondTheHorizonDebug.NO_BASE_VERTEX) {
            pass.setVertexBuffer(0, part.slice(vertexOffset, vertexBytes));
            this.boundVertex = null;
        } else {
            if (this.boundVertex != part.buffer) {
                pass.setVertexBuffer(0, part.wholeBuffer());
                this.boundVertex = part.buffer;
            }
            baseVertex = (part.offset + vertexOffset) / MeshBuilder.VERTEX_SIZE;
        }
        if (this.boundIndex != indexBuffer) {
            pass.setIndexBuffer(indexBuffer, IndexType.SHORT);
            this.boundIndex = indexBuffer;
        }
        pass.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0);
    }

    private DynamicGpuData.Transform[] transformArray(int count) {
        if (count >= this.transformPool.length) {
            return new DynamicGpuData.Transform[count];
        }
        DynamicGpuData.Transform[] array = this.transformPool[count];
        if (array == null) {
            array = new DynamicGpuData.Transform[count];
            this.transformPool[count] = array;
        }
        return array;
    }

    private GpuBuffer sharedIndices() {
        if (this.sharedIndices == null || this.sharedIndices.isClosed()) {
            short[] indices = MeshBuilder.sharedIndices();
            ByteBuffer data = ByteBuffer.allocateDirect(indices.length * 2).order(ByteOrder.nativeOrder());
            for (short index : indices) {
                data.putShort(index);
            }
            data.flip();
            this.sharedIndices = RenderSystem.getDevice().createBuffer(() -> "Beyond the Terrain indices", GpuBuffer.USAGE_INDEX, data);
        }
        return this.sharedIndices;
    }

    private @Nullable CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        if (this.failed) {
            return null;
        }
        CompiledRenderPipeline compiled = null;
        try {
            compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        } catch (RuntimeException e) {
            LOGGER.error("Pipeline {} failed to load", pipeline.getLocation(), e);
        }
        if (compiled == null) {
            LOGGER.error("The shaders of the distant terrain are not supported by this graphics driver, it is turned off");
            this.failed = true;
        }
        return compiled;
    }

    /** Insertion sort by distance, nearest first: the order hardly changes from one frame to the next. */
    /** Whether a tile lies wholly inside the circle of the given radius around the camera, seen from above. */
    private boolean insideRange(Tile tile, double camX, double camZ, double range) {
        double dx = Math.max(Math.abs(tile.minX - camX), Math.abs(tile.minX + tile.size - camX));
        double dz = Math.max(Math.abs(tile.minZ - camZ), Math.abs(tile.minZ + tile.size - camZ));
        return dx * dx + dz * dz < range * range;
    }

    private static void sortByDistance(Tile[] tiles, double[] distance, int count) {
        for (int i = 1; i < count; i++) {
            Tile tile = tiles[i];
            double d = distance[i];
            int j = i - 1;
            while (j >= 0 && distance[j] > d) {
                tiles[j + 1] = tiles[j];
                distance[j + 1] = distance[j];
                j--;
            }
            tiles[j + 1] = tile;
            distance[j + 1] = d;
        }
    }

    public void close() {
        this.fog.close();
        if (this.projectionBuffer != null) {
            this.projectionBuffer.close();
            this.projectionBuffer = null;
        }
        if (this.sharedIndices != null) {
            this.sharedIndices.close();
            this.sharedIndices = null;
        }
    }
}
