package com.anonymous.beyondthehorizon.client.color;

import com.anonymous.beyondthehorizon.tile.BlockLook;
import com.anonymous.beyondthehorizon.tile.DetailData;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Learns what the ground really looks like from the real chunks the client has loaded, so the rough distant terrain can be painted
 * with it. The rough terrain only knows the biome, the height and the slope and has to guess the surface block, which is
 * wrong wherever a world generator (Terralith, Tectonic) puts stone and snow where vanilla biomes have grass.
 * <p>
 * For every biome, every band of altitude over the sea level and every class of slope the mean color of the top blocks seen is kept, so
 * the grass of the flat ground and the stone of the cliffs of one biome are not mixed into one muddy color. Trees, water and plants on
 * the ground are left out: the ground under them is what counts. The totals are saved in the cache folder, so they are there on the
 * next start, also when block detail is off.
 * <p>
 * Slope is measured over the same {@link #SLOPE_BASELINE} blocks here and on the rough tiles (see {@link #slope}), which keeps the
 * classes comparable between columns 1 block apart and samples tens of blocks apart.
 */
public final class SurfaceLearner {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Horizon");
    /** Altitude bands over the sea level, each {@link #BAND_HEIGHT} blocks; the last one takes everything higher, the first everything lower. */
    private static final int BANDS = 16;
    private static final int BAND_HEIGHT = 24;
    /** Classes of slope, flattest first: {@link #SLOPE_LIMITS} are the slopes (blocks up per block across) where one ends. */
    private static final int SLOPES = 4;
    private static final float[] SLOPE_LIMITS = {0.3F, 0.8F, 1.6F};
    /** Distance in blocks between the two neighbours a slope is measured from. */
    private static final int SLOPE_BASELINE = 8;
    /** Per band and slope class: the number of samples and the sums of red, green and blue. */
    private static final int FIELDS = 4;
    private static final int PER_BIOME = BANDS * SLOPES * FIELDS;
    /** A band without samples of its own takes the same slope class from the bands this far above and below, nearest first. */
    private static final int BAND_REACH = 2;
    /** Marks a grid position with no ground height, see {@link #slope}. */
    public static final int UNKNOWN = Integer.MIN_VALUE;
    /** A band is only trusted once it has this many samples. */
    private static final int MIN_SAMPLES = 32;
    /** Every second cell of a tile from the server in each direction is looked at, which is plenty and costs a quarter. */
    private static final int STRIDE = 2;
    private static final Identifier AIR = BuiltInRegistries.BLOCK.getKey(net.minecraft.world.level.block.Blocks.AIR);
    /** Every fourth column of a chunk in each direction is looked at: 16 per chunk, plenty for a mean over thousands of chunks. */
    private static final int CHUNK_STRIDE = 4;
    /** Chunks learned from in this session, so a chunk that unloads and loads again is not counted twice; main thread only. */
    private static final java.util.Set<Long> SEEN = new java.util.HashSet<>();
    private static final int MAX_SEEN = 1 << 16;
    private static final java.util.concurrent.Executor SAVER = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Beyond the Horizon color saver");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** Samples after which a band stops counting, far more than a mean needs. */
    private static final long MAX_SAMPLES = 1L << 22;
    private static final int FILE_VERSION = 2;
    /** Tiles learned from between two saves. */
    private static final int SAVE_EVERY = 64;
    private static final Identifier FALLBACK_BIOME = Identifier.withDefaultNamespace("plains");
    
    private static final Map<Identifier, AtomicLongArray> BIOMES = new ConcurrentHashMap<>();
    /** Biomes by holder, so the hot path does not look at keys. */
    private static final Map<Holder<Biome>, Identifier> IDS = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOADED = new AtomicBoolean();
    private static volatile boolean dirty;
    private static volatile int sinceSave;

    private SurfaceLearner() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("beyond-the-horizon-cache").resolve("surface-colors.bin");
    }

    private static int band(int y, int seaLevel) {
        return Math.max(0, Math.min(BANDS - 1, Math.floorDiv(y - seaLevel, BAND_HEIGHT)));
    }

    private static Identifier idOf(Holder<Biome> holder) {
        return IDS.computeIfAbsent(holder, h -> h.unwrapKey().map(key -> key.identifier()).orElse(FALLBACK_BIOME));
    }

    private static int slopeClass(float slope) {
        int c = 0;
        while (c < SLOPE_LIMITS.length && slope > SLOPE_LIMITS[c]) {
            c++;
        }
        return c;
    }

    /**
     * Slope of the ground at a sample from the heights of its neighbours on both sides, as blocks up per block across, over about
     * {@link #SLOPE_BASELINE} blocks whatever the spacing. Rough tiles far apart see less of the roughness of a mountain than detailed
     * cells do, so their slope is raised a little with the spacing to land in the class the same ground has in the detailed tiles.
     *
     * @param heights ground heights on a grid, {@link #UNKNOWN} where there is none
     */
    public static float slope(int[] heights, int size, int x, int z, int spacing) {
        int step = Math.max(1, SLOPE_BASELINE / (2 * spacing));
        float dx = gradient(heights, size, x, z, 1, 0, step, spacing);
        float dz = gradient(heights, size, x, z, 0, 1, step, spacing);
        float slope = Math.max(Math.abs(dx), Math.abs(dz));
        if (spacing > SLOPE_BASELINE) {
            slope *= Math.min(2.0F, 1.0F + 0.2F * (31 - Integer.numberOfLeadingZeros(spacing / SLOPE_BASELINE)));
        }
        return slope;
    }

    private static float gradient(int[] heights, int size, int x, int z, int dx, int dz, int step, int spacing) {
        int ax = x - dx * step;
        int az = z - dz * step;
        int bx = x + dx * step;
        int bz = z + dz * step;
        boolean a = ax >= 0 && az >= 0 && heights[ax + az * size] != UNKNOWN;
        boolean b = bx < size && bz < size && heights[bx + bz * size] != UNKNOWN;
        if (a && b) {
            return (heights[bx + bz * size] - heights[ax + az * size]) / (2.0F * step * spacing);
        }
        int here = heights[x + z * size];
        if (here == UNKNOWN) {
            return 0.0F;
        }
        if (b) {
            return (heights[bx + bz * size] - here) / (float) (step * spacing);
        }
        if (a) {
            return (here - heights[ax + az * size]) / (float) (step * spacing);
        }
        return 0.0F;
    }

    /**
     * The mean color of the ground seen so far in this biome at this height and slope, or 0 while too little of it has been seen: then
     * the caller guesses the surface. Ground of the same slope at a different height is the next best thing to ground of the same
     * height at a different slope: stone cliffs look alike at any height, grass over stone does not.
     *
     * @param slope see {@link #slope}
     */
    public static int mean(@Nullable Holder<Biome> biome, int y, int seaLevel, float slope) {
        if (biome == null || BIOMES.isEmpty()) {
            return 0;
        }
        AtomicLongArray totals = BIOMES.get(idOf(biome));
        if (totals == null) {
            return 0;
        }
        int band = band(y, seaLevel);
        int cls = slopeClass(slope);
        for (int reach = 0; reach <= BAND_REACH; reach++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                if (reach == 0 && sign == 1) {
                    continue;
                }
                int other = band + sign * reach;
                if (other < 0 || other >= BANDS) {
                    continue;
                }
                int base = (other * SLOPES + cls) * FIELDS;
                long count = totals.get(base);
                if (count >= MIN_SAMPLES) {
                    return ARGB.color(255, (int) (totals.get(base + 1) / count), (int) (totals.get(base + 2) / count), (int) (totals.get(base + 3) / count));
                }
            }
        }
        return 0;
    }

    /** Whether trees, mushrooms and the like cover the ground of a cell: the cell then says nothing about the ground. */
    private static boolean covering(String path) {
        return path.endsWith("leaves") || path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("hyphae")
            || path.endsWith("mushroom_block") || path.endsWith("_wart_block");
    }

    /**
     * Takes the colors of the ground of a tile the server describes block by block, on a worker thread.
     *
     * @param cell size of a cell in blocks
     */
    public static void learn(DetailData data, int baseX, int baseZ, int cell, List<@Nullable Holder<Biome>> biomes, int seaLevel) {
        load();
        Identifier[] blocks = data.blockIds();
        int cells = DetailData.CELLS;
        // The ground of every cell first, so slopes can look at the neighbours.
        Identifier[] ground = new Identifier[cells * cells];
        int[] tops = new int[cells * cells];
        for (int index = 0; index < tops.length; index++) {
            tops[index] = UNKNOWN;
            if (data.segCounts[index] > 0 && !data.isMissing(index)) {
                int[] top = {data.stackTops[index]};
                ground[index] = groundOf(data, index, blocks, top);
                if (ground[index] != null) {
                    tops[index] = top[0];
                }
            }
        }
        boolean any = false;
        for (int cz = 0; cz < cells; cz += STRIDE) {
            for (int cx = 0; cx < cells; cx += STRIDE) {
                int index = cx + cz * cells;
                if (ground[index] == null) {
                    continue;
                }
                int biomeIndex = data.biomes[index] & 0xFF;
                Holder<Biome> biome = biomeIndex < biomes.size() ? biomes.get(biomeIndex) : null;
                Identifier biomeId = data.palette.get(biomeIndex);
                BlockLooks.Look looks = BlockLooks.get(ground[index]);
                int y = tops[index] - 1;
                int color = looks.top().color(looks.state(), biome, baseX + cx * cell, y, baseZ + cz * cell);
                AtomicLongArray totals = BIOMES.computeIfAbsent(biomeId, key -> new AtomicLongArray(PER_BIOME));
                int base = (band(y, seaLevel) * SLOPES + slopeClass(slope(tops, cells, cx, cz, cell))) * FIELDS;
                if (totals.get(base) >= MAX_SAMPLES) {
                    continue;
                }
                totals.incrementAndGet(base);
                totals.addAndGet(base + 1, color >>> 16 & 255);
                totals.addAndGet(base + 2, color >>> 8 & 255);
                totals.addAndGet(base + 3, color & 255);
                any = true;
            }
        }
        if (any) {
            dirty = true;
            if (++sinceSave >= SAVE_EVERY) {
                save();
            }
        }
    }

    /**
     * The block that is the ground of a cell: the first one from the top that is not air or a plant, or null when something covers
     * it (water, leaves, a tree) or it is not a block that can be drawn.
     *
     * @param top in: the top of the column; out: the top of the ground block
     */
    private static @Nullable Identifier groundOf(DetailData data, int index, Identifier[] blocks, int[] top) {
        int offset = data.segOffsets[index];
        for (int s = 0; s < data.segCounts[index]; s++) {
            int block = Short.toUnsignedInt(data.segBlocks[offset + s]);
            Identifier id = blocks[block];
            if (id.equals(AIR)) {
                top[0] -= data.segHeights[offset + s];
                continue;
            }
            Block found = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (found == null) {
                return null;
            }
            BlockState state = found.defaultBlockState();
            if (state.isAir()) {
                top[0] -= data.segHeights[offset + s];
                continue;
            }
            BlockLook.Look look = BlockLook.look(state);
            if (look.block() < 0) {
                // A plant or something not drawn: the ground is under it.
                top[0] -= data.segHeights[offset + s];
                continue;
            }
            return look.liquid() || data.blockWet[block] || covering(id.getPath()) ? null : id;
        }
        return null;
    }

    /**
     * Takes the colors of the ground of a chunk the client has just loaded, so the distant terrain can be painted with what the ground
     * really looks like in every biome the player has been to. Runs on the main thread, where chunks may be read, and only looks at a
     * sparse grid of columns: a few blocks per chunk is plenty for a mean. Chunks seen before in this session are left alone.
     */
    public static void learnChunk(ClientLevel level, LevelChunk chunk) {
        load();
        ChunkPos chunkPos = chunk.getPos();
        if (SEEN.size() >= MAX_SEEN) {
            SEEN.clear();
        }
        if (!SEEN.add(ChunkPos.pack(chunkPos.x(), chunkPos.z()))) {
            return;
        }
        int seaLevel = level.getSeaLevel();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();
        // Ground heights of the whole chunk first, for the slopes; columns with water or trunks on top have none.
        int[] tops = new int[16 * 16];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState[] ground = new BlockState[16 * 16];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = x + z * 16;
                tops[index] = UNKNOWN;
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                pos.set(minX + x, y, minZ + z);
                BlockState state = chunk.getBlockState(pos);
                if (usable(state)) {
                    ground[index] = state;
                    tops[index] = y + 1;
                }
            }
        }
        boolean any = false;
        for (int z = 0; z < 16; z += CHUNK_STRIDE) {
            for (int x = 0; x < 16; x += CHUNK_STRIDE) {
                int index = x + z * 16;
                BlockState state = ground[index];
                if (state == null) {
                    continue;
                }
                int y = tops[index] - 1;
                pos.set(minX + x, y, minZ + z);
                Holder<Biome> biome = level.getBiome(pos);
                BlockLooks.Look looks = BlockLooks.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                int color = looks.top().color(looks.state(), biome, minX + x, y, minZ + z);
                AtomicLongArray totals = BIOMES.computeIfAbsent(idOf(biome), key -> new AtomicLongArray(PER_BIOME));
                int base = (band(y, seaLevel) * SLOPES + slopeClass(slope(tops, 16, x, z, 1))) * FIELDS;
                if (totals.get(base) >= MAX_SAMPLES) {
                    continue;
                }
                totals.incrementAndGet(base);
                totals.addAndGet(base + 1, color >>> 16 & 255);
                totals.addAndGet(base + 2, color >>> 8 & 255);
                totals.addAndGet(base + 3, color & 255);
                any = true;
            }
        }
        if (any) {
            dirty = true;
            if (++sinceSave >= SAVE_EVERY) {
                sinceSave = 0;
                // The file is small but not free: written off the main thread.
                SAVER.execute(SurfaceLearner::save);
            }
        }
    }

    /**
     * Whether the top block of a column says what the ground looks like: not air, not under water, not a tree or a mushroom, and a
     * block that is drawn at all (plants and the like are not: the ground is under them).
     */
    private static boolean usable(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        BlockLook.Look look = BlockLook.look(state);
        if (look.block() < 0 || look.liquid() || look.wet()) {
            return false;
        }
        return !covering(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
    }

    /** Whether the ground of a biome has been seen often enough to tell its colors; a biome that has not is worth asking the server about. */
    public static boolean knows(Identifier biome) {
        load();
        AtomicLongArray totals = BIOMES.get(biome);
        if (totals == null) {
            return false;
        }
        long count = 0L;
        for (int base = 0; base < PER_BIOME; base += FIELDS) {
            count += totals.get(base);
        }
        return count >= MIN_SAMPLES * 2L;
    }

    /** Reads what was learned in earlier sessions, once. */
    public static void load() {
        if (!LOADED.compareAndSet(false, true)) {
            return;
        }
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FILE_VERSION) {
                return;
            }
            int biomes = in.readInt();
            if (biomes < 0 || biomes > 4096) {
                return;
            }
            for (int i = 0; i < biomes; i++) {
                Identifier id = Identifier.tryParse(in.readUTF());
                long[] values = new long[PER_BIOME];
                for (int k = 0; k < values.length; k++) {
                    values[k] = in.readLong();
                }
                if (id == null) {
                    continue;
                }
                AtomicLongArray totals = BIOMES.computeIfAbsent(id, key -> new AtomicLongArray(PER_BIOME));
                for (int k = 0; k < values.length; k++) {
                    totals.addAndGet(k, Math.max(0L, values[k]));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not read the learned surface colors", e);
        }
    }

    /** Writes what was learned, if anything changed since the last time. */
    public static synchronized void save() {
        sinceSave = 0;
        if (!dirty) {
            return;
        }
        dirty = false;
        Path file = file();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(FILE_VERSION);
                out.writeInt(BIOMES.size());
                for (Map.Entry<Identifier, AtomicLongArray> entry : BIOMES.entrySet()) {
                    out.writeUTF(entry.getKey().toString());
                    AtomicLongArray totals = entry.getValue();
                    for (int k = 0; k < PER_BIOME; k++) {
                        out.writeLong(totals.get(k));
                    }
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not save the learned surface colors", e);
        }
    }

    /** For the performance log: how many biomes and altitude bands have enough samples to be used. */
    public static String summary() {
        int usable = 0;
        for (AtomicLongArray totals : BIOMES.values()) {
            for (int slot = 0; slot < BANDS * SLOPES; slot++) {
                if (totals.get(slot * FIELDS) >= MIN_SAMPLES) {
                    usable++;
                }
            }
        }
        return "learned ground colors: " + BIOMES.size() + " biomes, " + usable + " band/slope classes in use";
    }
}
