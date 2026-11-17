package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.BeyondTheHorizonDebug;
import com.anonymous.beyondthehorizon.client.color.SurfaceLearner;
import com.anonymous.beyondthehorizon.net.BeyondTheHorizonPayloads;
import com.anonymous.beyondthehorizon.tile.DetailData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Teaches the distant terrain the ground of biomes the player has not been near. The rough tiles report the biomes they hold whose
 * ground has not been seen (see {@link SurfaceLearner}); one at a time, the survey asks the server for a single small tile of block
 * data where such a biome has its steepest ground, and hands what comes back to the learner, which only keeps colors. Nothing of the
 * tile is drawn. A biome is tried twice at most in a session, and only while the server has block-level data on.
 */
public final class ColorSurvey {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Horizon");
    private static final int LEVEL = BeyondTheHorizonConstants.DETAIL_MIN_LEVEL;
    private static final int TILE_SIZE = BeyondTheHorizonConstants.tileSize(LEVEL);
    private static final int CHECK_TICKS = 40;
    private static final long TIMEOUT_MS = 30_000L;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_CANDIDATES = 512;
    private static final int URGENCY = 10;

    /** Where a biome has its steepest ground among the tiles seen so far. */
    private record Spot(int x, int z, float slope) {
    }

    private static final Map<Identifier, Spot> CANDIDATES = new ConcurrentHashMap<>();

    private final Minecraft minecraft;
    private final Map<Identifier, Integer> attempts = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Beyond the Horizon color survey");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** For the F3 screen: what the survey is doing, or why it does nothing. */
    public static volatile String state = "starting";
    private int ticks;
    private int tileX;
    private int tileZ;
    private @Nullable Identifier asked;
    private long askedAt;

    public ColorSurvey(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    /** For the F3 screen: the biomes noted whose ground has not been seen. */
    public static int candidates() {
        return CANDIDATES.size();
    }

    /** From the worker threads that mesh tiles: a biome whose ground has not been seen, and a place in it. */
    public static void note(Identifier biome, int x, int z, float slope) {
        if (SurfaceLearner.knows(biome)) {
            CANDIDATES.remove(biome);
            return;
        }
        Spot old = CANDIDATES.get(biome);
        if (old == null && CANDIDATES.size() >= MAX_CANDIDATES) {
            return;
        }
        if (old == null || slope > old.slope()) {
            CANDIDATES.put(biome, new Spot(x, z, slope));
        }
    }

    /** Every tick: now and then, if nothing is asked for, asks for the tile of the closest biome that is not known. */
    public void update(double camX, double camZ, BeyondTheHorizonPayloads.@Nullable Hello hello) {
        if (BeyondTheHorizonDebug.NO_SURVEY) {
            state = "off by -Dbth.nosurvey";
            return;
        }
        if (hello == null) {
            state = "no hello from the server yet";
            return;
        }
        if (!hello.detailEnabled()) {
            state = "the server has block detail data off (rejoin after turning it on)";
            return;
        }
        if (this.minecraft.level == null || ++this.ticks % CHECK_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.asked != null && now - this.askedAt < TIMEOUT_MS) {
            state = "waiting for the answer for " + this.asked;
            return;
        }
        this.asked = null;
        if (!ClientPlayNetworking.canSend(BeyondTheHorizonPayloads.DetailRequest.TYPE)) {
            state = "the server has no channel for detail requests";
            return;
        }
        Identifier best = null;
        Spot bestSpot = null;
        double bestDistance = Double.MAX_VALUE;
        List<Identifier> done = new ArrayList<>();
        for (Map.Entry<Identifier, Spot> entry : CANDIDATES.entrySet()) {
            if (SurfaceLearner.knows(entry.getKey())) {
                done.add(entry.getKey());
                continue;
            }
            if (this.attempts.getOrDefault(entry.getKey(), 0) >= MAX_ATTEMPTS) {
                continue;
            }
            double dx = entry.getValue().x() - camX;
            double dz = entry.getValue().z() - camZ;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
                bestSpot = entry.getValue();
            }
        }
        done.forEach(CANDIDATES::remove);
        if (best == null || bestSpot == null) {
            state = "idle, nothing left to ask for";
            return;
        }
        state = "asked for " + best;
        this.asked = best;
        this.askedAt = now;
        this.tileX = Math.floorDiv(bestSpot.x(), TILE_SIZE);
        this.tileZ = Math.floorDiv(bestSpot.z(), TILE_SIZE);
        this.attempts.merge(best, 1, Integer::sum);
        // Chunks nobody has been to are made in memory by the server; how deep it looks is left to the server's own setting.
        PerfStats.count(PerfStats.Count.SURVEY_ASKED);
        ClientPlayNetworking.send(new BeyondTheHorizonPayloads.DetailRequest(new int[] {LEVEL, this.tileX, this.tileZ}, true, URGENCY, 0, false));
    }

    /** The answer: only the colors of the ground are taken from it. */
    public void onData(BeyondTheHorizonPayloads.DetailDataPayload payload) {
        ClientLevel level = this.minecraft.level;
        if (level == null || payload.level() != LEVEL || !payload.dimension().equals(level.dimension().identifier())) {
            return;
        }
        PerfStats.count(PerfStats.Count.SURVEY_ANSWERED);
        int tx = payload.x();
        int tz = payload.z();
        if (this.asked != null && tx == this.tileX && tz == this.tileZ) {
            this.asked = null;
        }
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        int seaLevel = level.getSeaLevel();
        byte[] bytes = payload.data();
        this.executor.execute(() -> {
            try {
                DetailData data = DetailData.decode(bytes);
                List<@Nullable Holder<Biome>> biomes = new ArrayList<>(data.palette.size());
                for (Identifier id : data.palette) {
                    biomes.add(registry.get(ResourceKey.create(Registries.BIOME, id)).orElse(null));
                }
                SurfaceLearner.learn(data, tx * TILE_SIZE, tz * TILE_SIZE, TILE_SIZE / DetailData.CELLS, biomes, seaLevel);
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Could not read a color survey tile", e);
            }
        });
    }

    public void reset() {
        this.asked = null;
        this.attempts.clear();
        CANDIDATES.clear();
    }

    public void close() {
        this.executor.shutdownNow();
    }
}
