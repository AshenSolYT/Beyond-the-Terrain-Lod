package com.anonymous.beyondthehorizon.server;

import com.anonymous.beyondthehorizon.config.ServerConfig;
import com.anonymous.beyondthehorizon.net.BeyondTheHorizonPayloads;
import com.anonymous.beyondthehorizon.server.virtual.VirtualTerrain;
import com.anonymous.beyondthehorizon.tile.StructureData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server side of the distant structure layer, see {@link StructureData}. It is separate from {@link TileServer}: its own request,
 * its own thread at the lowest priority, its own small cache in memory. The boxes come from the structure starts that the world
 * generator works out without generating a chunk; only the ones above ground are kept.
 */
public final class StructureServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Terrain");
    private static final int REGION_CHUNKS = StructureData.REGION / 16;
    /** Regions kept per dimension, so that a player walking around asks the generator once. */
    private static final int CACHE_REGIONS = 2048;
    /** Boxes one region may have; a village has a few dozen. */
    private static final int MAX_BOXES_PER_REGION = 4096;
    /** Answers waiting for the thread; further requests are dropped and asked again by the client later. */
    private static final int MAX_QUEUED = 32;
    /** Structures smaller than this on both sides (ruined portals, wells and the like) are not worth seeing from afar. */
    private static final int MIN_STRUCTURE_SPAN = 14;

    private record RawBox(Identifier structure, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    }

    private final MinecraftServer server;
    private final ServerConfig config;
    private final ThreadPoolExecutor executor;
    /** The next three are touched by the thread of the executor only. */
    private final Map<Identifier, VirtualTerrain> terrains = new HashMap<>();
    private final Set<Identifier> unsupported = new HashSet<>();
    private final Map<Identifier, Map<Long, List<RawBox>>> cache = new HashMap<>();

    public StructureServer(MinecraftServer server, ServerConfig config) {
        this.server = server;
        this.config = config;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_QUEUED), runnable -> {
            Thread thread = new Thread(runnable, "Beyond the Terrain structures");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /** A player asks for regions, pairs of region x and z. Runs on the server thread. */
    public void onRequest(ServerPlayer player, int[] regions) {
        if (!this.config.enabled || !this.config.structuresEnabled || regions.length < 2) {
            return;
        }
        ServerLevel level = player.level();
        if (!TileGenerator.supports(level)) {
            return;
        }
        double reach = this.config.structureDistance + 2.0 * StructureData.REGION;
        double px = player.getX();
        double pz = player.getZ();
        int count = Math.min(regions.length / 2, StructureData.MAX_REGIONS);
        int[] kept = new int[count * 2];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int rx = regions[i * 2];
            int rz = regions[i * 2 + 1];
            double cx = (rx + 0.5) * StructureData.REGION;
            double cz = (rz + 0.5) * StructureData.REGION;
            if (Math.abs(cx - px) > reach || Math.abs(cz - pz) > reach) {
                continue;
            }
            kept[n++] = rx;
            kept[n++] = rz;
        }
        if (n == 0) {
            return;
        }
        int[] asked = java.util.Arrays.copyOf(kept, n);
        UUID playerId = player.getUUID();
        try {
            this.executor.execute(() -> this.answer(playerId, level, asked));
        } catch (RejectedExecutionException e) {
            // Dropped: the client asks again when it has had no answer for a while.
        }
    }

    private void answer(UUID playerId, ServerLevel level, int[] regions) {
        try {
            Identifier dimension = level.dimension().identifier();
            List<Identifier> palette = new ArrayList<>();
            Map<Identifier, Integer> index = new HashMap<>();
            List<StructureData.Region> out = new ArrayList<>();
            for (int i = 0; i + 1 < regions.length; i += 2) {
                int rx = regions[i];
                int rz = regions[i + 1];
                List<StructureData.Box> boxes = new ArrayList<>();
                for (RawBox raw : this.region(level, dimension, rx, rz)) {
                    Integer type = index.get(raw.structure());
                    if (type == null) {
                        type = palette.size();
                        palette.add(raw.structure());
                        index.put(raw.structure(), type);
                    }
                    boxes.add(new StructureData.Box(type, raw.minX(), raw.minY(), raw.minZ(), raw.sizeX(), raw.sizeY(), raw.sizeZ()));
                }
                out.add(new StructureData.Region(rx, rz, boxes));
            }
            byte[] data = new StructureData(palette, out).encode();
            this.server.execute(() -> {
                ServerPlayer player = this.server.getPlayerList().getPlayer(playerId);
                if (player != null && ServerPlayNetworking.canSend(player, BeyondTheHorizonPayloads.StructureDataPayload.TYPE)) {
                    ServerPlayNetworking.send(player, new BeyondTheHorizonPayloads.StructureDataPayload(dimension, data));
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("Could not make the distant structures", e);
        }
    }

    /** The boxes of the structures starting in one region, from the cache or worked out. Only called from the executor's thread. */
    private List<RawBox> region(ServerLevel level, Identifier dimension, int rx, int rz) {
        Map<Long, List<RawBox>> perDimension = this.cache.computeIfAbsent(dimension, d -> new LinkedHashMap<Long, List<RawBox>>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, List<RawBox>> eldest) {
                return this.size() > CACHE_REGIONS;
            }
        });
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
        List<RawBox> cached = perDimension.get(key);
        if (cached != null) {
            return cached;
        }
        List<RawBox> boxes = this.compute(level, dimension, rx, rz);
        perDimension.put(key, boxes);
        return boxes;
    }

    private List<RawBox> compute(ServerLevel level, Identifier dimension, int rx, int rz) {
        if (this.unsupported.contains(dimension)) {
            return List.of();
        }
        VirtualTerrain terrain = this.terrains.get(dimension);
        if (terrain == null) {
            terrain = VirtualTerrain.create(level);
            if (terrain == null) {
                this.unsupported.add(dimension);
                return List.of();
            }
            this.terrains.put(dimension, terrain);
        }
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<RawBox> out = new ArrayList<>();
        for (StructureStart start : terrain.startsIn(rx * REGION_CHUNKS, rz * REGION_CHUNKS, REGION_CHUNKS, REGION_CHUNKS)) {
            Structure structure = start.getStructure();
            // Only what stands above ground: mineshafts, strongholds, ancient cities and the like are not seen from afar.
            if (structure.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) {
                continue;
            }
            Identifier id = registry.getKey(structure);
            if (id == null || id.getPath().contains("ruined_portal")) {
                continue;
            }
            BoundingBox whole = start.getBoundingBox();
            if (whole.getXSpan() < MIN_STRUCTURE_SPAN && whole.getZSpan() < MIN_STRUCTURE_SPAN) {
                continue;
            }
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox box = piece.getBoundingBox();
                int sizeX = Math.clamp(box.maxX() - box.minX() + 1, 1, Short.MAX_VALUE);
                int sizeY = Math.clamp(box.maxY() - box.minY() + 1, 1, Short.MAX_VALUE);
                int sizeZ = Math.clamp(box.maxZ() - box.minZ() + 1, 1, Short.MAX_VALUE);
                out.add(new RawBox(id, box.minX(), Math.clamp(box.minY(), Short.MIN_VALUE, Short.MAX_VALUE), box.minZ(), sizeX, sizeY, sizeZ));
                if (out.size() >= MAX_BOXES_PER_REGION) {
                    return out;
                }
            }
        }
        return out.isEmpty() ? List.of() : out;
    }

    public void close() {
        this.executor.shutdownNow();
    }
}
