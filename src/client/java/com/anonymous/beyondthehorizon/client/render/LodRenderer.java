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
     * Under a shader pack: how much of its brightness the terrain keeps, at the edge of vanilla's chunks and at the far end of the LOD
     * distance. A pack lights the terrain as full lightmap with no shadows, so next to vanilla's real terrain (shaded, with shadows) it
     * is too bright, most of all at the border where the two meet. The pack multiplies the vertex color by ColorModulator, so this goes
     * there. 1 = off; for trying values: {@code -Dbth.packexposure=0.7} (edge) and {@code -Dbth.packexposurefar=0.8} (far end).
     */
    private static final float PACK_NEAR_EXPOSURE = exposureProperty("bth.packexposure", 0.62F);
    private static final float PACK_FAR_EXPOSURE = exposureProperty("bth.packexposurefar", 0.78F);

    private static float exposureProperty(String name, float fallback) {
        try {
            return Math.max(0.2F, Math.min(1.0F, Float.parseFloat(System.getProperty(name, Float.toString(fallback)))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
    /**
     * Under a shader pack the terrain is drawn this many blocks lower: the pack's program does the vertex math its own way (and
     * jitters it for its anti-aliasing), so where the terrain lies on the same surface as vanilla's chunks the two fight over the
     * depth and flicker. Lowered, vanilla's chunks always win there; the step is too small to see.
     */
    private static final float PACK_LOWER = biasProperty("bth.packlower", 1.0F, 0.0F, 4.0F);
    /**
     * Under a shader pack the terrain is also drawn this much further out along every ray from the camera: the view matrix is scaled
     * about the camera, which leaves the picture as it is (a perspective view does not change when what it shows is scaled about its eye)
     * and puts the depth of everything a little further, in proportion to its distance, about 1.5 blocks at 100. Where the terrain
     * lies under vanilla's chunks (the margin around their edge, and the part of a tile that reaches into them) vanilla's chunks are
     * then in front of it, even where the coarser terrain is higher than the real ground. A pack's program cannot cut it out there,
     * and a depth bias of the pipeline is not relied on: this works with any pack. (A pack that works out the position of a pixel in
     * the world from the depth or from this matrix finds it that much further out.)
     */
    // Without a pack the bias is in lod_terrain.vsh (LOD_DEPTH_SCALE, 1.015); under a pack it is larger, as the coarser terrain can lie several blocks
    // over the real ground out where vanilla's chunks end and a pack leaves no other way to cut it out. For trying values: -Dbth.packdepthscale=1.04 and
    // -Dbth.packlower=1.5 (blocks).
    private static final float PACK_DEPTH_SCALE = biasProperty("bth.packdepthscale", 1.03F, 1.0F, 1.2F);

    private static float biasProperty(String name, float fallback, float min, float max) {
        try {
            return Math.max(min, Math.min(max, Float.parseFloat(System.getProperty(name, Float.toString(fallback)))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
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
    public static final RenderPipeline GROUND = ground("lod_ground", "NOISE");
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
    public static final RenderPipeline STEPS = ground("lod_steps", "STEPPED", "NOISE");
    /** The approximated trees: flat shapes seen from both sides. */
    public static final RenderPipeline TREES = RenderPipelines.registerOptional(
        base("lod_trees", "SPRITES").withCull(false).withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(DepthStencilState.DEFAULT).build()
    );
    // The same for a shader pack (Iris) whose depth is not reversed: Iris draws them with the pack's own program, see IrisCompat.
    public static final RenderPipeline GROUND_FORWARD = RenderPipelines.registerOptional(
        base("lod_ground_forward").withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(new DepthStencilState(FORWARD, true)).build()
    );
    public static final RenderPipeline STEPS_FORWARD = RenderPipelines.registerOptional(
        base("lod_steps_forward", "STEPPED").withColorTargetState(ColorTargetState.DEFAULT).withDepthStencilState(new DepthStencilState(FORWARD, true)).build()
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
    /** The view rotation of the terrain under a shader pack: scaled about the camera, see {@link #PACK_DEPTH_SCALE}. */
    private final Matrix4f packView = new Matrix4f();
    /** Without a pack, in the place of the texture matrix: the square of vanilla's chunks, seen from the camera, for the shader to cut out. */
    private final Matrix4f ownBounds = new Matrix4f();
    /**
     * Under a shader pack the terrain's own shader (which leaves out what lies inside the square of vanilla's chunks, fragment by
     * fragment) is replaced by the pack's, so the cells that lie wholly inside the square are left out of the draw calls instead, for the
     * tiles that stand partly in it. The square, in blocks, as of this frame; {@link #cutActive} when there is one to cut by.
     */
    private boolean cutActive;
    private double cutX0;
    private double cutX1;
    private double cutZ0;
    private double cutZ1;
    private final Matrix4f cull = new Matrix4f();
    private final Matrix4f identity = new Matrix4f();
    private final List<Vector4f> mods = new ArrayList<>();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final List<Vector3f> offsets = new ArrayList<>();
    private @Nullable ProjectionMatrixBuffer projectionBuffer;
    /** The far planes last written to the log (debug), so that it says something only when they change. */
    private float loggedCameraFar = Float.NaN;
    private float loggedFar = Float.NaN;
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

    /** A coordinate modulo 4096 as a float: the noise's grain is fixed to the world with it, without the precision a far coordinate loses. */
    private static float modulo4096(double v) {
        return (float) (v - Math.floor(v / 4096.0) * 4096.0);
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
        CompiledRenderPipeline trees = cfg.trees && !shaderPack ? this.compiled(forwardZ ? TREES_FORWARD : TREES) : null;
        CompiledRenderPipeline water = this.compiled(forwardZ ? WATER_FORWARD : WATER);
        CompiledRenderPipeline stepPipeline = this.compiled(forwardZ ? STEPS_FORWARD : STEPS);
        if (ground == null || water == null || target.getDepthTextureView() == null || minecraft.level == null) {
            return;
        }
        double camX = camera.pos.x;
        double camY = camera.pos.y;
        double camZ = camera.pos.z;
        Matrix4f base = camera.projectionMatrix;
        // Read from the projection of this frame, so the field of view and its effects are in it: nothing is kept from an earlier frame.
        // A projection that is not usable (zero or not finite) falls back to the option's fov and the window's shape.
        boolean baseUsable = Float.isFinite(base.m11()) && Float.isFinite(base.m00()) && base.m11() > 1.0E-4F && base.m00() > 1.0E-4F;
        float fovY = baseUsable ? 2.0F * (float) Math.atan(1.0 / base.m11()) : (float) Math.toRadians(minecraft.options.fov().get());
        float aspect = baseUsable ? base.m11() / base.m00() : (float) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        // The depth runs backwards, as vanilla's: the far plane is given as the near one.
        float far = this.manager.activeLodDistance() * 2.0F + (float) Math.abs(camY) + 4096.0F;
        // The far plane of the camera of this frame (the game's own projection, which a shader pack reads its depth with).
        // The far plane is read from the projection itself (the camera's depthFar field may not follow the projection's far plane).
        float cameraFar = Math.max(camera.depthFar, baseUsable ? projectionFar(base, zeroToOne, forwardZ) : 0.0F);
        if (BeyondTheHorizonDebug.ENABLED) {
            this.logFarNames(camera);
        }
        boolean cameraFarKnown = cameraFar > 0.0F && Float.isFinite(cameraFar);
        if (shaderPack && cameraFarKnown) {
            // The game's projection has its far plane out at the end of the terrain (ProjectionFarMixin): the same one is used, so
            // the depth written here is the depth the pack reads back.
            far = Math.max(far, cameraFar);
        }
        if (BeyondTheHorizonDebug.ENABLED && shaderPack && (cameraFar != this.loggedCameraFar || far != this.loggedFar)) {
            this.loggedCameraFar = cameraFar;
            this.loggedFar = far;
            LOGGER.info("[BTH pack depth] camera.depthFar={} (used {}; known: {}), terrain far={}, packFar={}, projection from camera: {}", camera.depthFar, cameraFar, cameraFarKnown, far, this.manager.packFar(), !forwardZ && cameraFarKnown && cameraFar >= far);
        }
        if (shaderPack && !forwardZ && cameraFarKnown && cameraFar >= far) {
            // The camera's projection reaches as far as the terrain: it is the projection of the terrain, value for value, so that
            // the depth of a pixel of the terrain is exactly what the pack takes it for (its fog, and no sky where there is terrain).
            this.projection.set(base);
        } else if (forwardZ) {
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
        // The square of vanilla's chunks around the camera of this frame, worked out once: a tile lying wholly inside it is under what
        // vanilla draws and is left out with four comparisons, before anything else is done with it (the planner does the same with the
        // camera of its last tick; the camera has moved on since).
        int ownHalf = this.manager.ownHalf();
        double ownX0 = 0.0;
        double ownX1 = 0.0;
        double ownZ0 = 0.0;
        double ownZ1 = 0.0;
        if (ownHalf >= 1) {
            int chunkX = (int) Math.floor(camX) >> 4;
            int chunkZ = (int) Math.floor(camZ) >> 4;
            ownX0 = (chunkX - ownHalf) * 16.0;
            ownX1 = (chunkX + ownHalf + 1) * 16.0;
            ownZ0 = (chunkZ - ownHalf) * 16.0;
            ownZ1 = (chunkZ + ownHalf + 1) * 16.0;
        }
        this.cutActive = shaderPack && ownX1 > ownX0 && !BeyondTheHorizonDebug.NO_PACK_CUT;
        this.cutX0 = ownX0;
        this.cutX1 = ownX1;
        this.cutZ0 = ownZ0;
        this.cutZ1 = ownZ1;
        int count = 0;
        for (Tile tile : list) {
            if (ownX1 > ownX0 && tile.minX >= ownX0 && tile.minZ >= ownZ0 && tile.minX + tile.size <= ownX1 && tile.minZ + tile.size <= ownZ1) {
                continue;
            }
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

        // The view of the terrain and the matrix in the place of the texture matrix, the same for every tile.
        if (shaderPack) {
            this.packView.set(camera.viewRotationMatrix).scaleLocal(PACK_DEPTH_SCALE, PACK_DEPTH_SCALE, PACK_DEPTH_SCALE);
        } else {
            // The first column: x0, z0, x1, z1 of the square of vanilla's chunks, from the camera; all zero (nothing cut out) when there is none.
            this.ownBounds.zero();
            if (ownX1 > ownX0) {
                this.ownBounds.m00((float) (ownX0 - camX)).m01((float) (ownZ0 - camZ)).m02((float) (ownX1 - camX)).m03((float) (ownZ1 - camZ));
            }
            // The second column: the camera's place modulo 4096, which puts the noise's grain in the world (lod_terrain.vsh: noisePos).
            this.ownBounds.m10(modulo4096(camX)).m11(modulo4096(camY)).m12(modulo4096(camZ));
        }
        Matrix4f terrainView = shaderPack ? this.packView : camera.viewRotationMatrix;
        Matrix4f terrainTexture = shaderPack ? this.identity : this.ownBounds;
        double vanillaRangeBlocks = minecraft.options.getEffectiveRenderDistance() * 16.0;
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
                // The pack multiplies its vertex color by this: the terrain is dimmed to PACK_NEAR_EXPOSURE where it meets vanilla's chunks,
                // fading to PACK_FAR_EXPOSURE at the end of the terrain.
                double span = Math.max(1.0, this.manager.activeLodDistance() - vanillaRangeBlocks);
                double t = Math.max(0.0, Math.min(1.0, (this.distance[i] - vanillaRangeBlocks) / span));
                float exposure = PACK_NEAR_EXPOSURE + (PACK_FAR_EXPOSURE - PACK_NEAR_EXPOSURE) * (float) (t * t * (3.0 - 2.0 * t));
                mod.set(exposure, exposure, exposure, 1.0F);
            } else {
                // y: the size of a cell of the pixel filter and of the noise, z: the noise (off with the pixel filter), w: whether the
                // render-distance fog is round (1) or cylindrical (0), see lod_terrain.fsh.
                mod.set(0.0F, (float) (tile.size / MeshBuilder.CELLS) / Math.max(1, pixel), pixel > 0 ? PIXEL_NOISE : 0.0F, this.fog.round ? 1.0F : 0.0F);
            }
            transforms[i] = new DynamicGpuData.Transform(terrainView, mod, offset, terrainTexture);
        }
        GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        // The same again for the trees, with the distance of vanilla's chunks in the modulator: the shader leaves out what
        // lies inside it, per fragment, so a tile that stands half in it still shows its trees on the far half.
        double vanillaRange = vanillaRangeBlocks;
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
        // Vanilla draws over the terrain with a depth buffer of its own. Under a shader pack the terrain's depth stays: the pack fogs and
        // shades by it (a pixel without depth is the sky to it), and the terrain is drawn a little lower, see PACK_LOWER, so vanilla's chunks win.
        if (!BeyondTheHorizonDebug.NO_CLEAR && (!shaderPack || BeyondTheHorizonDebug.PACK_CLEAR)) {
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
                if (!this.cutActive || !this.drawCut(pass, tile, shared)) {
                    this.drawTile(pass, tile, 0, MeshBuilder.GROUND_VERTICES * MeshBuilder.VERTEX_SIZE, shared, 0, MeshBuilder.SHARED_INDEX_COUNT);
                }
            } else {
                this.drawTile(pass, tile, 0, MeshBuilder.GROUND_VERTICES * MeshBuilder.VERTEX_SIZE, tile.part.buffer,
                    (tile.part.offset + tile.ownGroundIndexOffset) / 2, tile.groundIndexCount);
            }
        }
    }

    private boolean loggedFarNames;

    /**
     * Debug only: once, the names in the game that have to do with the far plane and the projection, so that the hook that raises the far
     * plane (ProjectionFarMixin) can be put where the game really sets it. Looked up by name, so nothing here can fail to compile.
     */
    private void logFarNames(Object cameraState) {
        if (this.loggedFarNames) {
            return;
        }
        this.loggedFarNames = true;
        try {
            for (Class<?> type : new Class<?>[] {Class.forName("net.minecraft.client.renderer.GameRenderer"), cameraState.getClass(), Class.forName("net.minecraft.client.Camera")}) {
                StringBuilder names = new StringBuilder();
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("far") || name.contains("depth") || name.contains("projection") || name.contains("renderdistance")) {
                        names.append(method.getReturnType().getSimpleName()).append(' ').append(method.getName()).append(java.util.Arrays.toString(method.getParameterTypes())).append("; ");
                    }
                }
                StringBuilder fields = new StringBuilder();
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("far") || name.contains("depth") || name.contains("projection") || name.contains("renderdistance")) {
                        fields.append(field.getType().getSimpleName()).append(' ').append(field.getName()).append("; ");
                    }
                }
                LOGGER.info("[BTH names] {}: methods {} fields {}", type.getName(), names, fields);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("[BTH names] could not list: {}", e.toString());
        }
    }

    /**
     * Draws a tile with the shared triangles without the cells that lie wholly inside the square of vanilla's chunks, and without the
     * skirt along an edge cell that is left out: a skirt there would stand over vanilla's ground as a wall. The cells are in rows, so what
     * is left is a few ranges of the shared indices. False when no cell lies inside (the tile is then drawn whole by the caller).
     */
    private boolean drawCut(RenderPass pass, Tile tile, GpuBuffer shared) {
        int cells = MeshBuilder.CELLS;
        double cell = tile.size / cells;
        int x0 = clampCell((int) Math.ceil((this.cutX0 - tile.minX) / cell), cells);
        int x1 = clampCell((int) Math.floor((this.cutX1 - tile.minX) / cell), cells);
        int z0 = clampCell((int) Math.ceil((this.cutZ0 - tile.minZ) / cell), cells);
        int z1 = clampCell((int) Math.floor((this.cutZ1 - tile.minZ) / cell), cells);
        if (x1 <= x0 || z1 <= z0) {
            return false;
        }
        int bytes = MeshBuilder.GROUND_VERTICES * MeshBuilder.VERTEX_SIZE;
        // The rows above the square and the left part of its first row.
        int end = (z0 * cells + x0) * 6;
        if (end > 0) {
            this.drawTile(pass, tile, 0, bytes, shared, 0, end);
        }
        // From the right of the square in one row to its left in the next, one range for each row of the square.
        for (int row = z0; row < z1 - 1; row++) {
            int from = (row * cells + x1) * 6;
            int to = ((row + 1) * cells + x0) * 6;
            if (to > from) {
                this.drawTile(pass, tile, 0, bytes, shared, from, to - from);
            }
        }
        // The right of the square in its last row, and the rows below it.
        int from = ((z1 - 1) * cells + x1) * 6;
        int total = cells * cells * 6;
        if (total > from) {
            this.drawTile(pass, tile, 0, bytes, shared, from, total - from);
        }
        // The skirts: each is along one edge cell; a run of kept ones is one range.
        int skirts = total;
        for (int edge = 0; edge < 4; edge++) {
            int run = -1;
            for (int i = 0; i <= cells; i++) {
                boolean keep = false;
                if (i < cells) {
                    int cx = edge == 2 ? 0 : edge == 3 ? cells - 1 : i;
                    int cz = edge == 0 ? 0 : edge == 1 ? cells - 1 : i;
                    keep = !(cx >= x0 && cx < x1 && cz >= z0 && cz < z1);
                }
                if (keep && run < 0) {
                    run = i;
                } else if (!keep && run >= 0) {
                    this.drawTile(pass, tile, 0, bytes, shared, skirts + (edge * cells + run) * 6, (i - run) * 6);
                    run = -1;
                }
            }
        }
        return true;
    }

    private static int clampCell(int value, int cells) {
        return Math.max(0, Math.min(cells, value));
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
            LOGGER.error("The shaders of the Heightmap LOD are not supported by this graphics driver, it is turned off");
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

    /**
     * The far plane a perspective matrix was made with (near plane 0.05, as vanilla's), worked out from its depth terms; 0 where it is
     * not usable. Reverse depth (far given as near) or forward, zero-to-one or minus-one-to-one: the four forms of JOML's setPerspective.
     */
    private static float projectionFar(Matrix4f m, boolean zeroToOne, boolean forward) {
        float m22 = m.m22();
        float m32 = m.m32();
        float near = 0.05F;
        float far;
        if (forward) {
            far = zeroToOne ? m32 / (m22 + 1.0F) : near * (m22 - 1.0F) / (m22 + 1.0F);
        } else {
            far = zeroToOne ? m32 / m22 : near * (m22 + 1.0F) / (m22 - 1.0F);
        }
        return Float.isFinite(far) && far > 0.0F ? far : 0.0F;
    }
}