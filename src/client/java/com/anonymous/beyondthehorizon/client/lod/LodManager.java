package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.client.config.ClientConfig;
import com.anonymous.beyondthehorizon.client.render.VertexArena;
import com.anonymous.beyondthehorizon.dimension.DimensionKind;
import com.anonymous.beyondthehorizon.net.BeyondTheHorizonPayloads;
import com.anonymous.beyondthehorizon.tile.TileData;
import com.anonymous.beyondthehorizon.tile.TilePos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The distant terrain on the client: which tiles the picture is made of, asking the server for the ones that are missing, building
 * their triangles on worker threads and keeping them on the GPU. Everything but the workers runs on the game's main thread.
 */
public final class LodManager {
    /** Blocks per second above which the tiles nearest to the camera are loaded first, see {@link Planner.Params#nearFirst}. */
    private static final double FAST_MOVEMENT = 12.0;
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Terrain");
    private static final int ARENA_BLOCK_BYTES = 8 * 1024 * 1024;
    /**
     * Tiles asked for and not yet here (a tile counts until its mesh is uploaded). 24 left the server's threads idle while the cheap coarse
     * tiles were loaded (log of 2026-11-22: 11-42 % busy, 1000 tiles not asked yet); the server still works nearest-first from its own queue.
     */
    private static final int MAX_PENDING = 64;
    /** Chunks at the edge of what vanilla has loaded that are not counted as vanilla's, see {@link #tick}. */
    private static final int OWNED_MARGIN = 2;
    /** The Block Heightmap's finest tiles (one column per block) reach this far from the camera, or its distance if that is less. */
    private static final double BLOCK_FINE_BLOCKS = 128.0;
    /** Tiles being read from disk and built at once. */
    private static final int MAX_LOCAL_PENDING = 192;
    /** Long, since a tile can wait behind others while the server generates; asking again early only fills its queue with copies. */
    private static final int REQUEST_TIMEOUT_MS = 120_000;
    private static final long UPLOAD_NS = 2_500_000L;
    /** Upload budget per frame while many built tiles wait: at ~9 fps 2.5 ms let only ~30 tiles/s through (cache load log 2026-11-22: 1028 tiles in 34.6 s, server needed 37.6 s for the same). */
    private static final long UPLOAD_BACKLOG_NS = 8_000_000L;
    private static final int UPLOAD_BACKLOG = 48;
    private static final int RING_CHECK_TICKS = 10;
    /** Ticks a changed setting of the mesh (Block Heightmap, trees) has to stay unchanged before the tiles are built again: a slider moves through many values. */
    private static final int REMESH_DELAY_TICKS = 10;
    /** How many checks (of {@link #RING_CHECK_TICKS} ticks) vanilla's loaded chunks have to hold before they are taken as drawn: 10 seconds. */
    private static final int GROW_SAMPLES = 20;
    /** How many levels of bigger tiles over a drawn tile are kept with it. */
    private static final int KEEP_ANCESTORS = 8;

    private record Built(int epoch, long key, TilePos pos, MeshBuilder.Mesh mesh) {
    }

    private final Minecraft minecraft;
    private final ClientConfig config;
    /** The planner runs on a thread of its own (its tree walk and sorting were work for the main thread every tick); it is touched only by that thread. */
    private final Planner planner = new Planner();
    private final ExecutorService planWorker;
    private final AtomicBoolean planBusy = new AtomicBoolean();
    private final AtomicReference<PlanResult> planDone = new AtomicReference<>();
    /** The plan the main thread works from: the newest one the planner thread has finished. Empty until the first. */
    private PlanResult plan = new PlanResult(0, new long[0], 0, new long[0], 0, 0, 1.0);

    /** What the planner made: the tiles to draw, and the tiles to ask for, most urgent first. Never changed after it is made. */
    private record PlanResult(int epoch, long[] draw, int drawCount, long[] want, int wantCount, int leafCount, double zoneScale) {
    }
    private final Planner.Params params = new Planner.Params();
    private final Map<Long, Tile> tiles = new HashMap<>();
    private final Map<Long, Long> pending = new HashMap<>();
    private long horizonStartedAt = System.currentTimeMillis();
    private boolean horizonReported;
    private int tilesFromServer;
    private int tilesFromDisk;
    /** Debug counters for the horizon line: tiles meshed, ns reading+decoding, ns meshing, tiles uploaded, ns uploading. */
    private final AtomicLong statMeshed = new AtomicLong(), statReadNs = new AtomicLong(), statMeshNs = new AtomicLong(), statUploaded = new AtomicLong(), statUploadNs = new AtomicLong();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers;
    private final VertexArena arena = new VertexArena("terrain", ARENA_BLOCK_BYTES, GpuBuffer.USAGE_INDEX);
    private volatile int epoch;
    private BeyondTheHorizonPayloads.Hello hello;
    private ClientLevel level;
    private long tick;
    private double lastCamX;
    private double lastCamZ;
    /** The settings the tiles' meshes were built with; when they change the loaded tiles are built again, see {@link #checkMeshSettings}. */
    private int meshSignature;
    private long meshSignatureChangedTick;
    private boolean meshSettingsDirty;
    /** Raised at every rebuild of the loaded tiles, so that the tasks of an older one end. */
    private volatile int remeshGeneration;
    /** How fast the camera moves, in blocks per second, smoothed over about a second. */
    private double camSpeed;
    private boolean hasLastCam;
    /** How many checks in a row found nothing wanted and nothing on its way, see {@link #checkHorizon}. */
    private int horizonQuiet;
    private long waitingForClearUntil;
    private int ownChunkX = Integer.MIN_VALUE;
    private int ownChunkZ;
    private int ownRadius;
    private final int[] radiusSamples = new int[GROW_SAMPLES];
    private int radiusSampleIndex;
    private int radiusSampleCount;
    private float reach;
    private Tile[] drawList = new Tile[0];
    /** The list tickTimed collects the tiles to draw in, kept so that it is not made again every tick. */
    private final List<Tile> drawScratch = new ArrayList<>();
    /** The tiles of this world and dimension kept on disk, made when first needed; see {@link #cache}. */
    private TileCache cache;
    private Path worldCacheDirectory;
    /** Tiles the disk cache could not give; they are asked for from the server. */
    private final Set<Long> cacheMiss = new HashSet<>();
    /** The pending tiles that are read from disk, not asked for from the server. */
    private final Set<Long> localKeys = new HashSet<>();
    private final ConcurrentLinkedQueue<Long> cacheFailed = new ConcurrentLinkedQueue<>();

    public LodManager(Minecraft minecraft, ClientConfig config) {
        this.minecraft = minecraft;
        this.config = config;
        this.meshSignature = meshSignature(config);
        int threads = Mth.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 3);
        this.planWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Beyond the Terrain planner");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        this.workers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "Beyond the Terrain mesher");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    public ClientConfig config() {
        return this.config;
    }

    public VertexArena arena() {
        return this.arena;
    }

    /** The tiles of the picture as of the last tick; drawable ones only are in it. */
    public Tile[] drawList() {
        return this.drawList;
    }

    public boolean hasVisibleTerrain() {
        return this.drawList.length > 0;
    }

    /** How far the terrain is drawn, in blocks. */
    public float activeLodDistance() {
        return this.reach > 0.0F ? this.reach : this.config.distanceBlocks();
    }

    /**
     * The far plane the game's own projection is given under a shader pack, so that the terrain drawn with it and everything the pack
     * works out from the depth (the distance of a pixel, so its fog) agree: the terrain reaches {@link #activeLodDistance()} and is
     * drawn up to a height of some thousand blocks above and below the camera.
     */
    public float packFar() {
        return this.activeLodDistance() * 2.0F + 6144.0F;
    }

    /**
     * How many chunks, from the player's own, vanilla draws all round in a square that the terrain leaves out (less the margin of
     * {@link #OWNED_MARGIN}); under 1 when there is none. The planner leaves out the tiles lying wholly inside it, and so does the
     * renderer, once more, with the camera of the frame, see LodRenderer.
     */
    public int ownHalf() {
        return this.ownRadius - 1 - OWNED_MARGIN;
    }

    public boolean isActive() {
        ClientLevel clientLevel = this.minecraft.level;
        return this.config.enabled && this.hello != null && this.hello.enabled() && this.hello.protocol() == BeyondTheHorizonConstants.PROTOCOL_VERSION
            && clientLevel != null && DimensionKind.of(clientLevel).hasLod();
    }

    // ---- network

    public void onHello(BeyondTheHorizonPayloads.Hello hello) {
        this.hello = hello;
        this.reset();
        if (hello.protocol() != BeyondTheHorizonConstants.PROTOCOL_VERSION) {
            LOGGER.warn("Server uses protocol {}, this client {}: the Heightmap LOD is off", hello.protocol(), BeyondTheHorizonConstants.PROTOCOL_VERSION);
        }
    }

    public void onDisconnect() {
        this.hello = null;
        this.reset();
    }

    public void onCacheCleared(BeyondTheHorizonPayloads.CacheCleared payload) {
        this.waitingForClearUntil = 0L;
        this.clearDiskCache();
        this.reset();
    }

    /** Drops everything here and asks the server to drop its cache too. */
    public void reloadAll() {
        this.clearDiskCache();
        this.reset();
        if (this.hello != null && ClientPlayNetworking.canSend(BeyondTheHorizonPayloads.ClearCache.TYPE)) {
            ClientPlayNetworking.send(BeyondTheHorizonPayloads.ClearCache.INSTANCE);
            this.waitingForClearUntil = System.currentTimeMillis() + 20_000L;
        }
    }

    public void onTileData(BeyondTheHorizonPayloads.TileDataPayload payload) {
        ClientLevel clientLevel = this.minecraft.level;
        if (!this.isActive() || clientLevel == null || !payload.dimension().equals(clientLevel.dimension().identifier())) {
            return;
        }
        TilePos pos = new TilePos(payload.level(), payload.x(), payload.z());
        // Only what was asked for; it stays pending until its mesh is uploaded, so it is not asked for again meanwhile.
        if (!this.pending.containsKey(pos.pack())) {
            return;
        }
        this.tilesFromServer++;
        Registry<Biome> biomes = clientLevel.registryAccess().lookupOrThrow(Registries.BIOME);
        int seaLevel = clientLevel.getSeaLevel();
        int epoch = this.epoch;
        TileCache cache = this.cache();
        byte[] data = payload.data();
        this.workers.execute(() -> this.mesh(pos, data, cache, false, biomes, seaLevel, epoch));
    }

    /** Decodes and builds a tile, on a worker; tiles from the server are written to the disk cache once they are known to be whole. */
    private void mesh(TilePos pos, byte[] data, TileCache toCache, boolean fromCache, Registry<Biome> biomes, int seaLevel, int epoch) {
        long key = pos.pack();
        long decodeStart = System.nanoTime();
        {
            MeshBuilder.Mesh mesh = null;
            try {
                TileData tile = TileData.decode(data);
                if (!fromCache && toCache != null) {
                    toCache.write(key, data);
                }
                long meshStart = System.nanoTime();
                this.statReadNs.addAndGet(meshStart - decodeStart);
                // How far the tile is from the camera now, for the distances of the stepped columns and the trees (see ClientConfig); unknown (-1) before the first tick.
                double distance = -1.0;
                if (this.hasLastCam) {
                    double dx = Math.max(0.0, Math.max(pos.minX() - this.lastCamX, this.lastCamX - (pos.minX() + pos.size())));
                    double dz = Math.max(0.0, Math.max(pos.minZ() - this.lastCamZ, this.lastCamZ - (pos.minZ() + pos.size())));
                    distance = Math.sqrt(dx * dx + dz * dz);
                }
                mesh = TileMesher.mesh(tile, pos, biomes, seaLevel, this.config().trees, this.config().treeDensity, this.config().blockHeightmap,
                    distance, this.config().treeDistance, this.config().blockHeightmapDistance);
                PerfStats.add(PerfStats.Timer.MESH, System.nanoTime() - meshStart);
                this.statMeshNs.addAndGet(System.nanoTime() - meshStart);
                this.statMeshed.incrementAndGet();
            } catch (IOException | RuntimeException e) {
                if (fromCache) {
                    // A bad file: the server is asked instead.
                    this.cacheFailed.add(key);
                    return;
                }
                LOGGER.warn("Could not build tile {}", pos, e);
            }
            this.built.add(new Built(epoch, key, pos, mesh));
        }
    }

    /** What the mesh of a tile depends on in the settings: when it changes, the tiles already built have to be built again. */
    private static int meshSignature(ClientConfig c) {
        int h = c.blockHeightmap ? 1 : 0;
        h = h * 31 + c.blockHeightmapDistance;
        h = h * 31 + (c.trees ? 1 : 0);
        h = h * 31 + c.treeDensity;
        h = h * 31 + c.treeDistance;
        return h;
    }

    /**
     * The Block Heightmap and the trees are changed while playing: once the settings have stopped changing for a moment (a slider passes
     * through many values) every tile that is loaded is read from the disk cache and built again, the nearest first, and replaces its
     * old mesh when it is ready, so the picture never has a gap.
     */
    private void checkMeshSettings() {
        int signature = meshSignature(this.config());
        if (signature != this.meshSignature) {
            this.meshSignature = signature;
            this.meshSignatureChangedTick = this.tick;
            this.meshSettingsDirty = true;
        }
        if (this.meshSettingsDirty && this.tick - this.meshSignatureChangedTick >= REMESH_DELAY_TICKS) {
            this.meshSettingsDirty = false;
            this.remeshAll();
        }
    }

    private void remeshAll() {
        TileCache cache = this.cache();
        ClientLevel clientLevel = this.minecraft.level;
        if (cache == null || clientLevel == null || this.tiles.isEmpty()) {
            return;
        }
        Registry<Biome> biomes = clientLevel.registryAccess().lookupOrThrow(Registries.BIOME);
        int seaLevel = clientLevel.getSeaLevel();
        int epoch = this.epoch;
        int generation = ++this.remeshGeneration;
        double camX = this.lastCamX;
        double camZ = this.lastCamZ;
        List<Tile> held = new ArrayList<>(this.tiles.values());
        held.sort(Comparator.comparingDouble((Tile t) -> {
            double dx = t.minX + t.size * 0.5 - camX;
            double dz = t.minZ + t.size * 0.5 - camZ;
            return dx * dx + dz * dz;
        }));
        for (Tile tile : held) {
            TilePos pos = tile.pos;
            long key = tile.key;
            this.workers.execute(() -> {
                if (generation != this.remeshGeneration || epoch != this.epoch) {
                    return;
                }
                byte[] data = cache.read(key);
                if (data != null) {
                    this.mesh(pos, data, cache, true, biomes, seaLevel, epoch);
                }
            });
        }
    }

    /** The disk cache of this world and dimension; null before the world is joined. */
    private TileCache cache() {
        ClientLevel clientLevel = this.minecraft.level;
        if (this.cache == null && clientLevel != null) {
            Path root = BeyondTheHorizonConstants.cacheRoot(FabricLoader.getInstance().getGameDir())
                .resolve("protocol-" + BeyondTheHorizonConstants.PROTOCOL_VERSION);
            this.worldCacheDirectory = root.resolve(this.worldName());
            String dimension = clientLevel.dimension().identifier().toString().replace(':', '_').replace('/', '_');
            this.cache = new TileCache(this.worldCacheDirectory.resolve(dimension));
        }
        return this.cache;
    }

    /** The server's address, or the folder of a single-player world with the time it was made: a new world of the same name is another. */
    private String worldName() {
        ServerData server = this.minecraft.getCurrentServer();
        String name = "unknown";
        if (server != null) {
            name = server.ip;
        } else {
            IntegratedServer single = this.minecraft.getSingleplayerServer();
            if (single != null) {
                Path folder = single.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                name = String.valueOf(folder.getFileName());
                try {
                    name += "-" + Files.readAttributes(folder, java.nio.file.attribute.BasicFileAttributes.class).creationTime().toMillis();
                } catch (IOException | RuntimeException ignored) {
                    // Without the time, the name alone is used.
                }
            }
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void clearDiskCache() {
        this.cache();
        if (this.worldCacheDirectory != null) {
            Path directory = this.worldCacheDirectory;
            this.workers.execute(() -> TileCache.deleteTree(directory));
        }
        this.cacheMiss.clear();
    }

    // ---- per frame, before drawing

    /** Puts finished meshes on the GPU, a few milliseconds of it at most. */
    /** For the F3 screen. */
    public int tileCount() {
        return this.tiles.size();
    }

    /** For the F3 screen: the tiles asked for whose mesh is not on the card yet. */
    public int pendingCount() {
        return this.pending.size();
    }

    public void uploadBuilt() {
        long uploadStart = System.nanoTime();
        try {
            this.uploadBuiltTimed();
        } finally {
            PerfStats.add(PerfStats.Timer.UPLOAD, System.nanoTime() - uploadStart);
            this.statUploadNs.addAndGet(System.nanoTime() - uploadStart);
        }
    }

    private void uploadBuiltTimed() {
        long deadline = System.nanoTime() + (this.built.size() > UPLOAD_BACKLOG ? UPLOAD_BACKLOG_NS : UPLOAD_NS);
        Built b;
        while ((b = this.built.poll()) != null) {
            if (b.epoch != this.epoch) {
                if (b.mesh != null) {
                    MeshBuilder.release(b.mesh.data());
                }
                continue;
            }
            Tile old = this.tiles.remove(b.key);
            if (old != null) {
                old.close();
            }
            Tile tile = new Tile(b.pos);
            tile.lastUsed = this.tick;
            MeshBuilder.Mesh mesh = b.mesh;
            if (mesh != null) {
                ByteBuffer data = mesh.data();
                int size = data.remaining();
                VertexArena.Part part = this.arena.allocate(size);
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(part.slice(0, size), data);
                MeshBuilder.release(data);
                tile.part = part;
                tile.groundIndexCount = mesh.groundIndexCount();
                tile.ownGroundIndexOffset = mesh.ownGroundIndexOffset();
                tile.waterVertexOffset = mesh.waterVertexOffset();
                tile.waterIndexOffset = mesh.waterIndexOffset();
                tile.waterIndexCount = mesh.waterIndexCount();
                tile.treeVertexOffset = mesh.treeVertexOffset();
                tile.treeVertexCount = mesh.treeVertexCount();
                tile.treeIndexOffset = mesh.treeIndexOffset();
                tile.treeIndexCount = mesh.treeIndexCount();
                tile.stepVertexOffset = mesh.stepVertexOffset();
                tile.stepVertexCount = mesh.stepVertexCount();
                tile.stepIndexOffset = mesh.stepIndexOffset();
                tile.stepIndexCount = mesh.stepIndexCount();
                tile.minY = mesh.minY();
                tile.surfaceMinY = mesh.surfaceMinY();
                tile.maxY = mesh.maxY();
                tile.waterLevel = mesh.waterLevel();
            }
            this.statUploaded.incrementAndGet();
            this.tiles.put(b.key, tile);
            this.pending.remove(b.key);
            this.localKeys.remove(b.key);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
    }

    // ---- per tick

    public void tick() {
        long tickStart = System.nanoTime();
        try {
            this.tickTimed();
        } finally {
            PerfStats.add(PerfStats.Timer.TICK, System.nanoTime() - tickStart);
        }
    }

    private void tickTimed() {
        this.tick++;
        this.checkMeshSettings();
        ClientLevel clientLevel = this.minecraft.level;
        if (clientLevel != this.level) {
            this.level = clientLevel;
            this.reset();
        }
        if (!this.isActive() || this.minecraft.player == null) {
            if (this.drawList.length > 0) {
                this.drawList = new Tile[0];
            }
            return;
        }
        double camX = this.minecraft.player.getX();
        double camY = this.minecraft.player.getEyeY();
        double camZ = this.minecraft.player.getZ();
        if (this.tick % RING_CHECK_TICKS == 1 || this.ownChunkX == Integer.MIN_VALUE) {
            this.updateOwned(camX, camZ);
        }
        if (this.hasLastCam) {
            double moved = Math.sqrt((camX - this.lastCamX) * (camX - this.lastCamX) + (camZ - this.lastCamZ) * (camZ - this.lastCamZ));
            this.camSpeed += (moved * 20.0 - this.camSpeed) * 0.1;
        }
        this.lastCamX = camX;
        this.lastCamZ = camZ;
        this.hasLastCam = true;
        Planner.Params p = this.params;
        p.nearFirst = this.camSpeed > FAST_MOVEMENT;
        p.camX = camX;
        p.camY = camY;
        p.camZ = camZ;
        float reach = Math.min(this.config.distanceBlocks(), this.hello.maxDistance());
        this.reach = reach;
        p.reach = reach;
        p.splitFactor = this.config.sharpness / 10.0;
        // The Block Heightmap and the box trees keep the tiles near the camera fine, as far as their distances say.
        p.blockDistance = this.config.blockHeightmap ? this.config.blockHeightmapDistance : 0.0;
        p.treeDistance = this.config.trees ? this.config.treeDistance : 0.0;
        // The Block Heightmap's own finest tiles (spacing 1) close to the camera, whatever Start level says; beyond them it is spacing 2.
        p.hardMin = Math.max(this.hello.minLevel(), 0);
        // The plan stays under the memory limit (a little less: stand-ins and parents are held too), see Planner.zoneScale.
        p.maxLeaves = (int) (this.config.maxTiles * 0.8);
        p.blockFineDistance = this.config.blockHeightmap ? Math.min(this.config.blockHeightmapDistance, BLOCK_FINE_BLOCKS) : 0.0;
        p.minLevel = Math.max(Math.max(this.config.startLevel, this.hello.minLevel()), 0);
        // What vanilla draws: the square of chunks around the player that are all loaded, less two chunks all round, so that the
        // terrain at its edge is ready when chunks are unloaded or not built yet.
        int half = this.ownRadius - 1 - OWNED_MARGIN;
        if (half >= 1) {
            p.ownX0 = (this.ownChunkX - half) * 16.0;
            p.ownX1 = (this.ownChunkX + half + 1) * 16.0;
            p.ownZ0 = (this.ownChunkZ - half) * 16.0;
            p.ownZ1 = (this.ownChunkZ + half + 1) * 16.0;
        } else {
            p.ownX0 = p.ownX1 = p.ownZ0 = p.ownZ1 = 0.0;
        }
        // The plan of the last round is taken, and the next is started from what is here now (the tiles loaded, the camera). The plan is
        // one or two ticks old when it is used: tiles that came since are checked again where it is used.
        PlanResult done = this.planDone.getAndSet(null);
        if (done != null && done.epoch() == this.epoch) {
            this.plan = done;
        }
        if (this.planBusy.compareAndSet(false, true)) {
            Planner.Params snapshot = p.copy();
            LongOpenHashSet loadedNow = new LongOpenHashSet(this.tiles.size() * 2 + 16);
            for (Long loadedKey : this.tiles.keySet()) {
                loadedNow.add(loadedKey.longValue());
            }
            int planEpoch = this.epoch;
            this.planWorker.execute(() -> {
                try {
                    this.planner.plan(snapshot, loadedNow::contains);
                    this.planner.sortWanted();
                    Planner made = this.planner;
                    this.planDone.set(new PlanResult(planEpoch, java.util.Arrays.copyOf(made.draw, made.drawCount), made.drawCount,
                        java.util.Arrays.copyOf(made.want, made.wantCount), made.wantCount, made.leafCount, made.zoneScale));
                } catch (RuntimeException e) {
                    LOGGER.warn("The planner failed", e);
                } finally {
                    this.planBusy.set(false);
                }
            });
        }
        PlanResult plan = this.plan;
        List<Tile> draw = this.drawScratch;
        draw.clear();
        for (int i = 0; i < plan.drawCount(); i++) {
            Tile tile = this.tiles.get(plan.draw()[i]);
            if (tile != null) {
                tile.lastUsed = this.tick;
                if (tile.drawable()) {
                    draw.add(tile);
                }
                // The bigger tiles over it are what stands in for it when the camera comes closer or goes away; they stay.
                TilePos pos = tile.pos;
                int level = pos.level();
                int x = pos.x();
                int z = pos.z();
                for (int up = 0; up < KEEP_ANCESTORS && level < BeyondTheHorizonConstants.MAX_LEVEL; up++) {
                    level++;
                    x >>= 1;
                    z >>= 1;
                    Tile ancestor = this.tiles.get(TilePos.pack(level, x, z));
                    if (ancestor != null) {
                        ancestor.lastUsed = this.tick;
                    }
                }
            }
        }
        // Far under the ground vanilla's own walls close the view all round: nothing of the terrain would be seen.
        int surface = clientLevel.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(camX), Mth.floor(camZ));
        boolean underground = camY < surface - 48.0;
        this.drawList = underground ? new Tile[0] : draw.toArray(new Tile[0]);
        // The list is kept for the next tick, without references to the tiles.
        draw.clear();
        this.request();
        this.checkHorizon();
        this.evict();
        this.arena.growAhead();
    }

    /** The biggest square of chunks around the player's that vanilla has loaded completely. */
    private void updateOwned(double x, double z) {
        ClientLevel clientLevel = this.minecraft.level;
        int cx = Mth.floor(x) >> 4;
        int cz = Mth.floor(z) >> 4;
        int max = this.minecraft.options.getEffectiveRenderDistance();
        int radius = 0;
        search:
        for (int r = 0; r <= max; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    if (clientLevel.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null) {
                        break search;
                    }
                }
            }
            radius = r + 1;
        }
        // Loaded is not built: vanilla's chunks need a while to be meshed, longer on few cores, and the terrain must not be taken away
        // before they are drawn. So a radius counts once it has held for the whole window (GROW_SAMPLES checks), and it shrinks a
        // chunk at a time, so that a burst of unloaded chunks does not open holes all round at once.
        if (this.ownChunkX == Integer.MIN_VALUE) {
            this.radiusSampleCount = 0;
            this.ownRadius = 0;
        }
        this.radiusSamples[this.radiusSampleIndex] = radius;
        this.radiusSampleIndex = (this.radiusSampleIndex + 1) % GROW_SAMPLES;
        this.radiusSampleCount = Math.min(this.radiusSampleCount + 1, GROW_SAMPLES);
        int held = 0;
        if (this.radiusSampleCount >= GROW_SAMPLES) {
            held = Integer.MAX_VALUE;
            for (int sample : this.radiusSamples) {
                held = Math.min(held, sample);
            }
        }
        this.ownRadius = held >= this.ownRadius ? held : Math.max(held, this.ownRadius - 1);
        this.ownChunkX = cx;
        this.ownChunkZ = cz;
    }

    /**
     * Tells once after joining (or changing dimension) how long the horizon took: from the start until every tile the planner wants
     * is there. It is the number that tells whether the server is too slow (tiles from the server) or the client (tiles from the disk
     * cache), so it is told in the chat and in the log.
     */
    private void checkHorizon() {
        if (this.horizonReported || this.tick % 20 != 0) {
            return;
        }
        // The planner's list holds only the tiles that are wanted and not there yet. So the horizon is complete when nothing is wanted
        // and no request is on its way. A finer level can still turn up once a coarser one is there, so it has to hold for two checks.
        boolean quiet = this.plan.wantCount() == 0 && this.pending.isEmpty() && this.tilesFromServer + this.tilesFromDisk > 0;
        // The debug log says every 5 seconds what is still missing, so that a horizon that does not complete can be understood.
        if (com.anonymous.beyondthehorizon.BeyondTheHorizonDebug.ENABLED && this.tick % 100 == 0) {
            int missingAsked = 0;
            int missingNotAsked = 0;
            StringBuilder sample = new StringBuilder();
            for (int i = 0; i < this.plan.wantCount(); i++) {
                long key = this.plan.want()[i];
                if (this.pending.containsKey(key)) {
                    missingAsked++;
                } else {
                    missingNotAsked++;
                }
                if (sample.length() < 120) {
                    TilePos missing = TilePos.unpack(key);
                    sample.append(" L").append(missing.level()).append('(').append(missing.x()).append(',').append(missing.z()).append(')');
                }
            }
            LOGGER.info(String.format(java.util.Locale.ROOT,
                "[FarPlane perf] client horizon: %d tiles still wanted (%d asked and not answered, %d not asked yet), %d held (memory limit %d), %d from server, %d from disk | meshed %d (read+decode avg %.1f ms, mesh avg %.1f ms), uploaded %d (%.0f ms total), built waiting %d, plan %d tiles, zone scale %.2f;%s",
                this.plan.wantCount(), missingAsked, missingNotAsked, this.tiles.size(), this.config.maxTiles,
                this.tilesFromServer, this.tilesFromDisk,
                this.statMeshed.get(), this.statReadNs.get() / 1.0e6 / Math.max(1L, this.statMeshed.get()), this.statMeshNs.get() / 1.0e6 / Math.max(1L, this.statMeshed.get()),
                this.statUploaded.get(), this.statUploadNs.get() / 1.0e6, this.built.size(), this.plan.leafCount(), this.plan.zoneScale(), sample));
        }
        this.horizonQuiet = quiet ? this.horizonQuiet + 1 : 0;
        if (this.horizonQuiet < 2) {
            return;
        }
        this.horizonReported = true;
        String text = String.format(java.util.Locale.ROOT, "Horizon complete in %.1f s: %d tiles, %d came from the server, %d from the disk cache",
            (System.currentTimeMillis() - this.horizonStartedAt) / 1000.0, this.tiles.size(), this.tilesFromServer, this.tilesFromDisk);
        LOGGER.info(text);
        if (this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Beyond the Terrain] " + text));
        }
    }

    private void request() {
        long now = System.currentTimeMillis();
        this.pending.values().removeIf(sentAt -> now - sentAt > REQUEST_TIMEOUT_MS);
        this.localKeys.retainAll(this.pending.keySet());
        Long failed;
        while ((failed = this.cacheFailed.poll()) != null) {
            this.pending.remove(failed);
            this.localKeys.remove(failed);
            this.cacheMiss.add(failed);
        }
        boolean canAsk = now >= this.waitingForClearUntil && ClientPlayNetworking.canSend(BeyondTheHorizonPayloads.TileRequest.TYPE);
        PlanResult plan = this.plan;
        TileCache cache = this.cache();
        // Tiles read from disk and tiles asked for from the server have limits of their own: reading is quick, generating is not.
        int serverRoom = Math.min(MAX_PENDING - (this.pending.size() - this.localKeys.size()), BeyondTheHorizonPayloads.MAX_REQUESTS_PER_PACKET);
        int localRoom = MAX_LOCAL_PENDING - this.localKeys.size();
        int[] out = new int[Math.max(0, serverRoom) * 3];
        int n = 0;
        for (int i = 0; i < plan.wantCount(); i++) {
            long key = plan.want()[i];
            if (this.pending.containsKey(key) || this.tiles.containsKey(key)) {
                continue;
            }
            TilePos pos = TilePos.unpack(key);
            if (cache != null && !this.cacheMiss.contains(key)) {
                if (!cache.has(key)) {
                    // Not on disk: remembered, so that it is not looked for again every tick.
                    this.cacheMiss.add(key);
                } else if (localRoom > 0) {
                    localRoom--;
                    this.readFromDisk(cache, pos, key, now);
                    continue;
                } else {
                    continue;
                }
            }
            if (canAsk && n + 3 <= out.length) {
                out[n++] = pos.level();
                out[n++] = pos.x();
                out[n++] = pos.z();
                this.pending.put(key, now);
            }
        }
        if (n > 0) {
            ClientPlayNetworking.send(new BeyondTheHorizonPayloads.TileRequest(java.util.Arrays.copyOf(out, n)));
        }
    }

    /** Reads a tile that was seen before from the disk cache and builds it, on a worker. */
    private void readFromDisk(TileCache cache, TilePos pos, long key, long now) {
        this.tilesFromDisk++;
        this.pending.put(key, now);
        this.localKeys.add(key);
        ClientLevel clientLevel = this.minecraft.level;
        Registry<Biome> biomes = clientLevel.registryAccess().lookupOrThrow(Registries.BIOME);
        int seaLevel = clientLevel.getSeaLevel();
        int epoch = this.epoch;
        this.workers.execute(() -> {
            byte[] data = cache.read(key);
            if (data == null) {
                this.cacheFailed.add(key);
            } else {
                this.mesh(pos, data, cache, true, biomes, seaLevel, epoch);
            }
        });
    }

    /**
     * Keeps the tiles within the limit. Tiles in the picture, and the bigger ones standing behind them, are never dropped. Of the rest
     * the farthest from the camera go first: the tiles lying under vanilla's chunks or just outside the picture are the ones the
     * picture needs again as soon as vanilla unloads chunks behind the player, and loading them again is slow, while far ones are
     * cheap to get back. Nothing else is dropped.
     */
    private void evict() {
        int excess = this.tiles.size() - this.config.maxTiles;
        if (excess <= 0) {
            return;
        }
        List<Tile> candidates = new ArrayList<>();
        for (Tile tile : this.tiles.values()) {
            if (tile.lastUsed != this.tick) {
                candidates.add(tile);
            }
        }
        double camX = this.lastCamX;
        double camZ = this.lastCamZ;
        candidates.sort(Comparator.comparingDouble((Tile t) -> {
            double dx = t.minX + t.size * 0.5 - camX;
            double dz = t.minZ + t.size * 0.5 - camZ;
            return -(dx * dx + dz * dz);
        }).thenComparingLong(t -> t.lastUsed));
        for (Tile tile : candidates) {
            if (excess-- <= 0) {
                break;
            }
            this.tiles.remove(tile.key);
            tile.close();
        }
    }

    /** Forgets every tile, and makes the meshes still being built useless. */
    public void reset() {
        this.epoch++;
        this.plan = new PlanResult(this.epoch, new long[0], 0, new long[0], 0, 0, 1.0);
        for (Tile tile : this.tiles.values()) {
            tile.close();
        }
        this.tiles.clear();
        this.pending.clear();
        this.built.clear();
        this.cacheFailed.clear();
        this.cacheMiss.clear();
        this.localKeys.clear();
        this.cache = null;
        this.drawList = new Tile[0];
        this.ownChunkX = Integer.MIN_VALUE;
        this.radiusSampleCount = 0;
        this.horizonStartedAt = System.currentTimeMillis();
        this.horizonReported = false;
        this.horizonQuiet = 0;
        this.hasLastCam = false;
        this.camSpeed = 0.0;
        this.tilesFromDisk = 0;
        this.tilesFromServer = 0;
    }

    public void close() {
        this.workers.shutdownNow();
        this.planWorker.shutdownNow();
        this.reset();
        this.arena.close();
    }
}
