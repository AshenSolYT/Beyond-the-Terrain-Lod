package com.anonymous.beyondthehorizon.client.render;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.BeyondTheHorizonDebug;
import com.anonymous.beyondthehorizon.client.config.ClientConfig;
import com.anonymous.beyondthehorizon.client.lod.LodManager;
import com.anonymous.beyondthehorizon.client.lod.MeshBuilder;
import com.anonymous.beyondthehorizon.client.lod.PerfStats;
import com.anonymous.beyondthehorizon.client.lod.Tile;
import com.anonymous.beyondthehorizon.dimension.DimensionKind;
import com.anonymous.beyondthehorizon.client.compat.IrisCompat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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

    /** Vanilla's own POSITION_COLOR instance (same attributes): Iris matches pipelines to its programs by identity, a copy gets no color. */
    public static final VertexFormat FORMAT = DefaultVertexFormat.POSITION_COLOR;
    /** Brightness noise of the pixelated filter, as a share: 10 %. */
    private static final float PIXEL_NOISE = 0.035F;
    /**
     * Under a shader pack the terrain is drawn this many blocks lower: the pack's program does the vertex math its own way (and
     * jitters it for its anti-aliasing), so where the terrain lies on the same surface as vanilla's chunks the two fight over the
     * depth and flicker. Lowered, vanilla's chunks always win there; the step is too small to see.
     */
    private static final float PACK_LOWER = 0.4F;
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

    /** The depth test of the pipelines for shader packs that draw with the depth the usual way round (the far plane at 1). */
    private static final CompareOp FORWARD = CompareOp.LESS_THAN_OR_EQUAL;

    /** The ground blends the colors between vertices; the pixelated one reads them on a finer grid, with noise. */
    public static final RenderPipeline GROUND = ground("lod_ground");
    public static final RenderPipeline WATER = water("lod_water");
    /** The ground drawn into the depth only (transparent, with the blend keeping the color), for what vanilla draws after the terrain: the clouds. */
    public static final RenderPipeline GROUND_DEPTH = RenderPipelines.registerOptional(
        base("lod_depth", "DEPTH_ONLY").withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withDepthStencilState(DepthStencilState.DEFAULT).build()
    );
    /** The stepped columns into the depth only: their walls are seen from both sides. */
    public static final RenderPipeline STEPS_DEPTH = RenderPipelines.registerOptional(
        base("lod_depth_steps", "DEPTH_ONLY").withCull(true).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withDepthStencilState(DepthStencilState.DEFAULT).build()
    );
    public static final RenderPipeline GROUND_PIXEL = ground("lod_ground_pixel", "PIXEL_COLOR");
    /** The stepped columns: flat colors with the faces wound outward (see MeshBuilder.steps), so what faces away is culled. */
    public static final RenderPipeline STEPS = ground("lod_steps");
    /** The approximated trees: flat shapes seen from both sides. */
    public static final RenderPipeline TREES = RenderPipelines.registerOptional(
        base("lod_trees", "SPRITES").withCull(false).withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(DepthStencilState.DEFAULT).build()
    );
    // The same for a shader pack (Iris) whose depth is not reversed: Iris draws them with the pack's own program, see IrisCompat.
    public static final RenderPipeline GROUND_FORWARD = RenderPipelines.registerOptional(
        base("lod_ground_forward").withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(new DepthStencilState(FORWARD, true)).build()
    );
    public static final RenderPipeline STEPS_FORWARD = RenderPipelines.registerOptional(
        base("lod_steps_forward").withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(new DepthStencilState(FORWARD, true)).build()
    );
    public static final RenderPipeline TREES_FORWARD = RenderPipelines.registerOptional(
        base("lod_trees_forward", "SPRITES").withCull(false).withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(new DepthStencilState(FORWARD, true)).build()
    );
    public static final RenderPipeline WATER_FORWARD = RenderPipelines.registerOptional(
        base("lod_water_forward").withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withDepthStencilState(new DepthStencilState(FORWARD, false)).build()
    );

    /** Cameras higher than this are taken as possibly above the clouds (which are at 192 in the overworld), where the depth is not cut down. */
    private static final double DEPTH_CLOUD_CAMERA_MAX = 180.0;
    /** The height of the clouds, and how far they are drawn at most, for the depth pass's cull by slope. */
    private static final double CLOUD_LAYER_Y = 192.0;
    private static final double CLOUD_MAX_DIST = 2048.0;
    /** Yaw bins of the horizon culling. */
    private static final int HORIZON_BINS = 128;
    private static final double BINS_PER_RADIAN = HORIZON_BINS / (2.0 * Math.PI);
    /** A tile's trees are not drawn when one cell of its mesh covers fewer pixels than this. */
    private static final double TREE_MIN_CELL_PIXELS = 1.25;
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
    /** Per visible tile, which of the tree transforms is its (only tiles with trees have one), or -1. */
    private int[] treeSlot = new int[0];
    private static final GpuBufferSlice[] NO_SLICES = new GpuBufferSlice[0];
    private int[] smoothList = new int[0];
    /** Transform arrays by length, made once: the writer wants the exact length and the tile count changes by a few. */
    private final DynamicGpuData.Transform[][] transformPool = new DynamicGpuData.Transform[1024][];
    private final List<Vector4f> treeMods = new ArrayList<>();
    private GpuBufferSlice frameProjection;
    private GpuBufferSlice frameFog;
    private int frameCount;
    private Tile[] visible = new Tile[256];
    private double[] distance = new double[256];
    /** The horizontal distance to each visible tile's nearest point, in step with {@link #visible}. */
    private double[] horizontal = new double[256];
    private final double[] horizon = new double[HORIZON_BINS];

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
        // With a shader pack (Iris) the pipelines must be given to Iris: only a pipeline it overrides is drawn into the pack's gbuffers
        // (ExtendedShader binds the pack's framebuffer), anything else goes to vanilla's main target, which the pack never shows.
        // They get the pack's basic program (BASIC_COLOR, vertex color only), so the terrain's own filters and fog do not run.
        boolean shaderPack = IrisCompat.shadersActive();
        boolean forwardZ = shaderPack && !IrisCompat.reverseZ();
        if (shaderPack) {
            for (RenderPipeline pipeline : new RenderPipeline[] {GROUND, STEPS, TREES, WATER, GROUND_FORWARD, STEPS_FORWARD, TREES_FORWARD, WATER_FORWARD}) {
                IrisCompat.assignBasic(pipeline);
            }
        }
        int pixel = shaderPack ? 0 : cfg.pixelFilter;
        CompiledRenderPipeline smoothGround = this.compiled(forwardZ ? GROUND_FORWARD : GROUND);
        CompiledRenderPipeline pixelGround = pixel > 0 && !BeyondTheHorizonDebug.NO_PIXEL ? this.compiled(GROUND_PIXEL) : null;
        CompiledRenderPipeline ground = pixelGround != null ? pixelGround : smoothGround;
        // The tree sprites are cut out by the terrain shader (alpha codes), which the pack program does not do: no trees under a pack.
        CompiledRenderPipeline trees = cfg.treeLevel > 0 && !shaderPack ? this.compiled(forwardZ ? TREES_FORWARD : TREES) : null;
        CompiledRenderPipeline water = this.compiled(forwardZ ? WATER_FORWARD : WATER);
        CompiledRenderPipeline stepPipeline = this.compiled(forwardZ ? STEPS_FORWARD : STEPS);
        if (ground == null || water == null || target.getDepthTextureView() == null || minecraft.level == null) {
            return;
        }
        double camX = camera.pos.x;
        double camY = camera.pos.y;
        double camZ = camera.pos.z;
        Matrix4f base = camera.projectionMatrix;
        float fovY = 2.0F * (float) Math.atan(1.0 / base.m11());
        float aspect = base.m11() / base.m00();
        // The direction the camera looks in: minus the third row of the view rotation.
        this.manager.setView(fovY, Math.toRadians(minecraft.options.fov().get()), aspect, -camera.viewRotationMatrix.m02(), -camera.viewRotationMatrix.m12(), -camera.viewRotationMatrix.m22());
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        // The depth runs backwards, as vanilla's: the far plane is given as the near one.
        float far = this.manager.activeLodDistance() * 2.0F + (float) Math.abs(camY) + 4096.0F;
        if (forwardZ) {
            this.projection.setPerspective(fovY, aspect, 0.05F, far, zeroToOne);
        } else {
            this.projection.setPerspective(fovY, aspect, far, 0.05F, zeroToOne);
        }
        this.cull.set(this.projection).mul(camera.viewRotationMatrix);
        this.frustum.set(this.cull, false);

        DimensionKind dimension = DimensionKind.of(minecraft.level);
        boolean vision = minecraft.player != null && (minecraft.player.hasEffect(MobEffects.BLINDNESS) || minecraft.player.hasEffect(MobEffects.DARKNESS));
        this.fog.compute(this.manager.config(), dimension, camera.fogType, vision, minecraft.options.getEffectiveRenderDistance(), this.manager.activeLodDistance());

        // Which tiles are seen, nearest first.
        Tile[] list = this.manager.drawList();
        if (this.visible.length < list.length) {
            this.visible = new Tile[list.length];
            this.distance = new double[list.length];
            this.horizontal = new double[list.length];
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
            this.horizontal[count] = horizontal;
            this.distance[count++] = d;
        }
        if (count == 0) {
            return;
        }
        // Front to back by horizontal distance, then the tiles wholly hidden behind nearer ground are dropped, then by distance again.
        sortByDistance(this.visible, this.horizontal, this.distance, count);
        count = this.horizonCull(count, camX, camY, camZ);
        if (count == 0) {
            this.frameCount = 0;
            return;
        }
        sortByDistance(this.visible, this.distance, this.horizontal, count);

        // One transform per tile: where its corner is, seen from the camera.
        DynamicGpuData.Transform[] transforms = this.transformArray(count);
        while (this.offsets.size() < count) {
            this.offsets.add(new Vector3f());
            this.mods.add(new Vector4f());
            this.treeMods.add(new Vector4f());
        }
        for (int i = 0; i < count; i++) {
            Tile tile = this.visible[i];
            Vector3f offset = this.offsets.get(i).set((float) (tile.minX - camX), (float) -camY - (shaderPack ? PACK_LOWER : 0.0F), (float) (tile.minZ - camZ));
            // The color modulator is not used for color by the terrain's own shader: it carries the size of a pixel in blocks and the
            // noise to it. A shader pack's program (Iris) does multiply the vertex color by it, so under a pack it is plain white.
            Vector4f mod = this.mods.get(i);
            if (shaderPack) {
                mod.set(1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                mod.set(0.0F, (float) (tile.size / MeshBuilder.CELLS) / Math.max(1, pixel), PIXEL_NOISE, 1.0F);
            }
            transforms[i] = new DynamicGpuData.Transform(camera.viewRotationMatrix, mod, offset, this.identity);
        }
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        // The same again for the trees, with the distance of vanilla's chunks in the modulator: the shader leaves out what
        // lies inside it, per fragment, so a tile that stands half in it still shows its trees on the far half.
        double vanillaRange = minecraft.options.getEffectiveRenderDistance() * 16.0;
        // Only the tiles that have trees get one: most tiles have none, and each one is a write to the uniform buffer.
        if (this.treeSlot.length < count) {
            this.treeSlot = new int[count];
        }
        int treeCount = 0;
        for (int i = 0; i < count; i++) {
            this.treeSlot[i] = this.visible[i].treeIndexCount == 0 ? -1 : treeCount++;
        }
        GpuBufferSlice[] treeSlices = NO_SLICES;
        if (treeCount > 0) {
            DynamicGpuData.Transform[] treeTransforms = this.transformArray(treeCount);
            for (int i = 0; i < count; i++) {
                if (this.treeSlot[i] >= 0) {
                    Vector4f mod = this.mods.get(i);
                    treeTransforms[this.treeSlot[i]] = new DynamicGpuData.Transform(camera.viewRotationMatrix, this.treeMods.get(i).set((float) vanillaRange, mod.y, mod.z, mod.w), this.offsets.get(i), this.identity);
                }
            }
            treeSlices = RenderSystem.getDynamicUniforms().writeTransforms(treeTransforms);
            Arrays.fill(treeTransforms, null);
        }
        Arrays.fill(transforms, null);
        if (this.projectionBuffer == null) {
            this.projectionBuffer = new ProjectionMatrixBuffer("beyond the terrain");
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
            if (tile.part == null) {
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
        try (UpNormals normals = new UpNormals(shaderPack, camera.viewRotationMatrix);
             RenderPass pass = encoder.createRenderPass(
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
                    if (BeyondTheHorizonDebug.NO_GROUND || tile.part == null || tile.stepIndexCount == 0) {
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
                    if (tile.part == null || tile.treeIndexCount == 0 || this.insideRange(tile, camX, camZ, vanillaRange)
                        || tile.size / MeshBuilder.CELLS * screenPerBlock / Math.max(1.0, this.distance[i]) < TREE_MIN_CELL_PIXELS) {
                        continue;
                    }
                    pass.setUniform("DynamicTransforms", treeSlices[this.treeSlot[i]]);
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
                // Only the thin slab of the water surface is tested: the tile's box is as tall as its mountains.
                float wx = (float) (tile.minX - camX);
                float wz = (float) (tile.minZ - camZ);
                float wsize = (float) tile.size;
                if (!this.frustum.testAab(wx, (float) (tile.waterLevel - 1.0 - camY), wz, wx + wsize, (float) (tile.waterLevel + 1.0 - camY), wz + wsize)) {
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

    /**
     * Under a shader pack, makes the terrain's normal point up. The terrain has no normals, so Iris gives the pack's program the
     * constant (0, 0, 1) in place of one, and turns it by a normal matrix that it takes from RenderSystem's model-view matrix (not
     * from the per-tile transforms, which only place the vertices). Left alone that turns it toward the viewer, so the terrain is
     * lit by where you look. With this matrix, a view rotation turned a quarter around X, (0, 0, 1) comes out as world up, the
     * right normal for the tops of the terrain. Closed after the pass: the matrix is popped again.
     */
    private static final class UpNormals implements AutoCloseable {
        private final boolean on;

        UpNormals(boolean on, Matrix4f viewRotation) {
            this.on = on;
            if (on) {
                RenderSystem.getModelViewStack().pushMatrix();
                RenderSystem.getModelViewStack().set(new Matrix4f(viewRotation).rotateX((float) Math.toRadians(-90.0)));
            }
        }

        @Override
        public void close() {
            if (this.on) {
                RenderSystem.getModelViewStack().popMatrix();
            }
        }
    }

    /** For the F3 screen: what this frame draws. */
    private void gauges(int count, boolean treesOn) {
        int ground = 0;
        int treeMeshes = 0;
        long treeVertices = 0L;
        for (int i = 0; i < count; i++) {
            Tile tile = this.visible[i];
            if (tile == null || tile.part == null) {
                continue;
            }
            ground++;
            if (treesOn && tile.treeIndexCount > 0) {
                treeMeshes++;
                treeVertices += tile.treeVertexCount;
            }
        }
        PerfStats.groundDraws = ground;
        PerfStats.treeMeshes = treeMeshes;
        PerfStats.treeVertices = treeVertices;
    }

    /**
     * Draws the ground of this frame's terrain again into the depth only, over what vanilla has drawn: the terrain is drawn before
     * vanilla's pass, whose depth is its own, so what vanilla draws after the terrain (the clouds) would otherwise be seen in front
     * of distant mountains. The color stays as it is; where vanilla's chunks are nearer they keep their depth.
     */
    private long lastStats;
    private int lastLoggedTerrain = -1;

    /** With the debug flag: every five seconds, what is drawn, so that the cost of the layers can be told apart. */
    private void logStats(int count, ClientConfig cfg) {
        long nowNs = System.nanoTime();
        if (nowNs - this.lastStats < 5_000_000_000L) {
            return;
        }
        this.lastStats = nowNs;
        int terrain = 0;
        for (int i = 0; i < count; i++) {
            if (this.visible[i] != null) {
                terrain++;
            }
        }
        if (terrain == this.lastLoggedTerrain) {
            return;
        }
        this.lastLoggedTerrain = terrain;
        LOGGER.info("[BTH render] visible: rough terrain {} tiles", terrain);
    }

    public void renderDepth(RenderTarget target, CameraRenderState camera) {
        // The depth-only pass uses the terrain's own shader, which a shader pack replaces; the pack has its own order for the clouds.
        if (BeyondTheHorizonDebug.NO_DEPTH || this.frameCount == 0 || this.frameSlices == null || this.failed || target.getDepthTextureView() == null || IrisCompat.shadersActive()) {
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
            if (tile != null && tile.part != null && tile.maxY > needAbove && !this.belowClouds(tile, i, camY)) {
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
                if (tile.maxY <= needAbove || this.belowClouds(tile, i, camY)) {
                    continue;
                }
                if (tile.stepIndexCount > 0) {
                    // The stepped tiles put no depth in here: it would cost as much again to draw them twice. They are drawn below.
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
                    if (tile == null || tile.part == null || tile.stepIndexCount == 0 || tile.maxY <= needAbove || this.belowClouds(tile, i, camY)) {
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

    /** Insertion sort by {@code distance}, nearest first; {@code other} moves with it. */
    private static void sortByDistance(Tile[] tiles, double[] distance, double[] other, int count) {
        for (int i = 1; i < count; i++) {
            Tile tile = tiles[i];
            double d = distance[i];
            double o = other[i];
            int j = i - 1;
            while (j >= 0 && distance[j] > d) {
                tiles[j + 1] = tiles[j];
                distance[j + 1] = distance[j];
                other[j + 1] = other[j];
                j--;
            }
            tiles[j + 1] = tile;
            distance[j + 1] = d;
            other[j + 1] = o;
        }
    }

    /**
     * Whether a tile cannot hide any cloud: with the camera under the cloud layer, a ray that rises to the clouds within their range
     * must pass over the top of the tile to be stopped by it, which takes a top steeper than the line to the far end of the layer.
     */
    private boolean belowClouds(Tile tile, int index, double camY) {
        if (camY >= CLOUD_LAYER_Y) {
            return false;
        }
        double rise = tile.maxY - camY;
        if (rise <= 0.0) {
            return true;
        }
        double slope = rise / Math.max(this.horizontal[index], 1.0);
        return slope < (CLOUD_LAYER_Y - camY) / CLOUD_MAX_DIST;
    }

    /**
     * Drops the tiles that lie wholly behind nearer ground, per direction: the sorted tiles (nearest first by horizontal distance) raise
     * a horizon in 128 yaw bins, as the lowest slope their ground is seen at, {@code (minY - camY) / distance}; a tile whose highest
     * point has a lower slope in every bin it covers is hidden. Returns the new count; {@link #visible}, {@link #distance} and
     * {@link #horizontal} are compacted in step.
     */
    private int horizonCull(int count, double camX, double camY, double camZ) {
        double[] horizon = this.horizon;
        Arrays.fill(horizon, Double.NEGATIVE_INFINITY);
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Tile tile = this.visible[i];
            double minH = this.horizontal[i];
            double x0 = tile.minX - camX;
            double z0 = tile.minZ - camZ;
            double x1 = x0 + tile.size;
            double z1 = z0 + tile.size;
            boolean keep = true;
            if (minH > 1.0E-6) {
                double maxH = Math.sqrt(Math.max(x0 * x0, x1 * x1) + Math.max(z0 * z0, z1 * z1));
                // The angular span of the tile's corners seen from the camera (it is convex and the camera is outside, so under 180 degrees).
                double ref = Math.atan2(x0 + tile.size * 0.5, z0 + tile.size * 0.5);
                double lowDelta = 0.0;
                double highDelta = 0.0;
                for (int c = 0; c < 4; c++) {
                    double delta = Math.atan2((c & 1) == 0 ? x0 : x1, (c & 2) == 0 ? z0 : z1) - ref;
                    if (delta > Math.PI) {
                        delta -= 2.0 * Math.PI;
                    } else if (delta < -Math.PI) {
                        delta += 2.0 * Math.PI;
                    }
                    lowDelta = Math.min(lowDelta, delta);
                    highDelta = Math.max(highDelta, delta);
                }
                double u0 = (ref + lowDelta) * BINS_PER_RADIAN;
                double u1 = (ref + highDelta) * BINS_PER_RADIAN;
                int touchFrom = (int) Math.floor(u0);
                int touchTo = (int) Math.floor(u1);
                double rise = tile.maxY - camY;
                double top = rise < 0.0 ? rise / maxH : rise / minH;
                boolean hidden = true;
                for (int b = touchFrom; b <= touchTo; b++) {
                    if (!(top < horizon[Math.floorMod(b, HORIZON_BINS)])) {
                        hidden = false;
                        break;
                    }
                }
                if (hidden) {
                    keep = false;
                } else if (tile.part != null) {
                    double floor = tile.surfaceMinY - camY;
                    double low = floor > 0.0 ? floor / maxH : floor / minH;
                    // Only the bins the tile covers wholly: a ray in one of them certainly runs through it.
                    for (int b = (int) Math.ceil(u0); b < touchTo; b++) {
                        int bin = Math.floorMod(b, HORIZON_BINS);
                        if (low > horizon[bin]) {
                            horizon[bin] = low;
                        }
                    }
                }
            }
            if (keep) {
                this.visible[kept] = tile;
                this.horizontal[kept] = minH;
                this.distance[kept++] = this.distance[i];
            }
        }
        for (int i = kept; i < count; i++) {
            this.visible[i] = null;
        }
        return kept;
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