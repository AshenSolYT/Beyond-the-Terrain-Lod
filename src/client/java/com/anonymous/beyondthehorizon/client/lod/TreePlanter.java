package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.tile.TileData;
import com.anonymous.beyondthehorizon.tile.TilePos;
import com.anonymous.beyondthehorizon.client.color.TileColors;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Approximates the trees of a tile with a few boxes snapped to the block grid, like the trees of the game are built: a trunk and a crown of one to three boxes for leafy trees, tiers that narrow upwards for conifers. Farther trees get fewer boxes. The trees are not
 * the real ones: their places come from a hash of the world position, so they are the same every time, and how many there are
 * from the biome. Runs on a worker thread, no game classes besides the biome names.
 */
public final class TreePlanter {
    /** Vertices and triangles of the trees of one tile, in the vertex format of {@link MeshBuilder}. */
    public record Trees(float[] xyz, int[] colors, short[] indices, int vertexCount, int indexCount, float maxY) {
    }

    private record Species(boolean conifer, float chance, float height, int tint) {
    }

    private static final int MAX_TREES = 700;
    /** The indices of a mesh are shorts: a tile's trees stay under this many vertices. */
    private static final int MAX_VERTICES = 32_000;
    /** The most vertices one tree has: a trunk of four sides and three boxes of five faces. */
    private static final int MAX_TREE_VERTICES = 176;
    private static final float SHADE_X = 0.8F;
    private static final float SHADE_Z = 0.65F;
    /** The cells trees are placed in, in blocks: one tree at most in each, at a place hashed from the cell. */
    private static final int BASE_CELL = 5;
    private static final int TRUNK = 0xFF4B3A28;
    private static final int DARK_LEAVES = 0xFF45664A;
    private static final int PINK_LEAVES = 0xFFE9A5C4;
    private static final float MAX_SLOPE = 1.2F;
    /** The leaves as the game paints them are dark from afar, and the sides of the boxes are shaded again. */
    private static final float LEAF_BRIGHTNESS = 1.15F;
    private static final Identifier OAK_LEAVES = Identifier.withDefaultNamespace("oak_leaves");
    private static final Identifier SPRUCE_LEAVES = Identifier.withDefaultNamespace("spruce_leaves");
    private static final Identifier BIRCH_LEAVES = Identifier.withDefaultNamespace("birch_leaves");

    private TreePlanter() {
    }

    /**
     * What grows in the biomes of Terralith, from the trees its biomes list (the files of its biomes): conifer or leafy, how dense,
     * how tall. The biomes it has without trees are in {@link #TERRALITH_NO_TREES}; both are exact, the names below are a guess.
     */
    private static final Map<String, Species> TERRALITH = terralith();
    private static final Set<String> TERRALITH_NO_TREES = Set.of("alpine_highlands", "ancient_sands", "arid_highlands", "basalt_cliffs", "brushland", "caldera", "deep_warm_ocean", "desert_canyon", "desert_oasis", "desert_spires", "emerald_peaks", "glacial_chasm", "gravel_beach", "gravel_desert", "lush_desert", "painted_mountains", "red_oasis", "rocky_mountains", "sandstone_valley", "scarlet_mountains", "shield_clearing", "snowy_badlands", "steppe", "valley_clearing", "volcanic_crater", "volcanic_peaks", "warm_river", "warped_mesa", "white_mesa", "yosemite_cliffs");

    private static void tree(Map<String, Species> m, String id, boolean conifer, float chance, float height, int tint) {
        m.put(id, new Species(conifer, chance, height, tint));
    }

    private static Map<String, Species> terralith() {
        Map<String, Species> m = new HashMap<>();
        tree(m, "alpha_islands", false, 0.35F, 10.0F, 0);
        tree(m, "alpha_islands_winter", false, 0.3F, 10.0F, 0);
        tree(m, "alpine_grove", true, 0.35F, 7.0F, 0);
        tree(m, "amethyst_canyon", false, 0.5F, 16.0F, 0);
        tree(m, "amethyst_rainforest", false, 0.7F, 18.0F, 0);
        tree(m, "ashen_savanna", true, 0.1F, 6.0F, 0);
        tree(m, "birch_taiga", false, 0.5F, 10.0F, 0);
        tree(m, "blooming_plateau", false, 0.08F, 8.0F, 0);
        tree(m, "blooming_valley", false, 0.5F, 9.0F, 0);
        tree(m, "bryce_canyon", true, 0.15F, 12.0F, 0);
        tree(m, "cloud_forest", false, 0.6F, 12.0F, 0);
        tree(m, "cold_shrubland", true, 0.1F, 6.0F, 0);
        tree(m, "forested_highlands", false, 0.6F, 12.0F, 0);
        tree(m, "fractured_savanna", false, 0.12F, 9.0F, 0);
        tree(m, "frozen_cliffs", true, 0.06F, 10.0F, 0);
        tree(m, "granite_cliffs", false, 0.1F, 8.0F, 0);
        tree(m, "haze_mountain", true, 0.45F, 11.0F, 0);
        tree(m, "highlands", false, 0.35F, 10.0F, 0);
        tree(m, "hot_shrubland", true, 0.1F, 6.0F, 0);
        tree(m, "ice_marsh", false, 0.25F, 8.0F, 0);
        tree(m, "jungle_mountains", false, 0.5F, 16.0F, 0);
        tree(m, "lavender_forest", false, 0.55F, 10.0F, 0);
        tree(m, "lavender_valley", false, 0.45F, 10.0F, 0);
        tree(m, "lush_valley", true, 0.5F, 11.0F, 0);
        tree(m, "mirage_isles", false, 0.4F, 10.0F, 0);
        tree(m, "moonlight_grove", true, 0.6F, 14.0F, 0);
        tree(m, "moonlight_valley", true, 0.5F, 14.0F, 0);
        tree(m, "orchid_swamp", false, 0.3F, 9.0F, 0);
        tree(m, "rocky_jungle", false, 0.5F, 16.0F, 0);
        tree(m, "rocky_shrubland", true, 0.1F, 6.0F, 0);
        tree(m, "sakura_grove", false, 0.5F, 9.0F, PINK_LEAVES);
        tree(m, "sakura_valley", false, 0.4F, 9.0F, PINK_LEAVES);
        tree(m, "savanna_badlands", false, 0.05F, 8.0F, 0);
        tree(m, "savanna_slopes", false, 0.08F, 8.0F, 0);
        tree(m, "shield", true, 0.55F, 13.0F, 0);
        tree(m, "shrubland", true, 0.12F, 6.0F, 0);
        tree(m, "siberian_grove", true, 0.5F, 12.0F, 0);
        tree(m, "siberian_taiga", true, 0.55F, 12.0F, 0);
        tree(m, "skylands_autumn", false, 0.4F, 10.0F, 0);
        tree(m, "skylands_spring", false, 0.4F, 10.0F, 0);
        tree(m, "skylands_summer", false, 0.4F, 10.0F, 0);
        tree(m, "skylands_winter", false, 0.4F, 10.0F, 0);
        tree(m, "snowy_cherry_grove", false, 0.4F, 8.0F, PINK_LEAVES);
        tree(m, "snowy_maple_forest", false, 0.6F, 12.0F, 0);
        tree(m, "snowy_shield", true, 0.5F, 12.0F, 0);
        tree(m, "stony_spires", false, 0.1F, 9.0F, 0);
        tree(m, "temperate_highlands", false, 0.5F, 11.0F, 0);
        tree(m, "tropical_jungle", false, 0.6F, 16.0F, 0);
        tree(m, "white_cliffs", false, 0.1F, 8.0F, 0);
        tree(m, "windswept_spires", true, 0.3F, 12.0F, 0);
        tree(m, "wintry_forest", true, 0.6F, 13.0F, 0);
        tree(m, "wintry_lowlands", true, 0.4F, 12.0F, 0);
        tree(m, "yellowstone", true, 0.5F, 13.0F, 0);
        tree(m, "yosemite_lowlands", false, 0.45F, 10.0F, 0);
        return m;
    }

    /** What grows in a biome, null if no trees. The names are matched by words, so biomes of other mods are covered too. */
    private static @Nullable Species species(Identifier id, @Nullable Holder<Biome> biome) {
        if (id.getNamespace().equals("terralith")) {
            Species known = TERRALITH.get(id.getPath());
            if (known != null || TERRALITH_NO_TREES.contains(id.getPath())) {
                return known;
            }
        }
        String n = id.getPath();
        if (n.contains("peaks") || n.contains("ocean") || n.contains("river") || n.contains("beach") || n.contains("desert")
            || n.contains("badlands") || n.contains("mushroom") || n.contains("stony") || n.contains("ice") || n.contains("end_")
            || n.contains("nether") || n.contains("void")) {
            return null;
        }
        if (n.contains("dark_forest")) {
            return new Species(false, 0.75F, 14.0F, 0);
        }
        if (n.contains("jungle")) {
            return new Species(false, 0.75F, 18.0F, 0);
        }
        if (n.contains("cherry")) {
            return new Species(false, 0.4F, 8.0F, PINK_LEAVES);
        }
        if (n.contains("mangrove")) {
            return new Species(false, 0.3F, 9.0F, 0);
        }
        if (n.contains("swamp")) {
            return new Species(false, 0.2F, 9.0F, 0);
        }
        if (n.contains("taiga") || n.contains("pine") || n.contains("spruce") || n.contains("grove")) {
            return new Species(true, n.contains("snowy") ? 0.4F : 0.5F, n.contains("old_growth") ? 18.0F : 12.0F, 0);
        }
        if (n.contains("birch")) {
            return new Species(false, 0.55F, n.contains("old_growth") ? 14.0F : 10.0F, 0);
        }
        if (n.contains("savanna")) {
            return new Species(false, 0.05F, 8.0F, 0);
        }
        if (n.contains("plains")) {
            return new Species(false, 0.008F, 8.0F, 0);
        }
        if (n.contains("meadow")) {
            return new Species(false, 0.02F, 8.0F, 0);
        }
        if (n.contains("flower_forest") || n.contains("windswept_forest")) {
            return new Species(false, 0.3F, 9.0F, 0);
        }
        if (n.contains("moonlight")) {
            return new Species(true, 0.5F, 14.0F, 0);
        }
        if (n.contains("forest") || n.contains("wood")) {
            return new Species(false, 0.55F, 10.0F, 0);
        }
        if (n.contains("valley") || n.contains("highlands")) {
            return new Species(false, 0.3F, 10.0F, 0);
        }
        // Biomes of other mods with other names: the tags of the game tell what grows in them.
        if (biome != null) {
            if (biome.is(BiomeTags.IS_TAIGA)) {
                return new Species(true, 0.5F, 12.0F, 0);
            }
            if (biome.is(BiomeTags.IS_JUNGLE)) {
                return new Species(false, 0.75F, 18.0F, 0);
            }
            if (biome.is(BiomeTags.IS_FOREST)) {
                return new Species(false, 0.55F, 10.0F, 0);
            }
        }
        return null;
    }

    public static @Nullable Trees plant(
        TileData data, TilePos pos, int spacing, float[] ground, boolean[] voids, boolean[] water, int[] colors, int densityPercent,
        List<Holder<Biome>> biomes, boolean billboards
    ) {
        int samples = MeshBuilder.SAMPLES;
        List<Identifier> palette = data.palette;
        Species[] species = new Species[palette.size()];
        boolean any = false;
        for (int i = 0; i < species.length; i++) {
            species[i] = species(palette.get(i), i < biomes.size() ? biomes.get(i) : null);
            any |= species[i] != null;
        }
        if (!any) {
            return null;
        }
        int baseX = (int) pos.minX();
        int baseZ = (int) pos.minZ();
        int extent = MeshBuilder.CELLS * spacing;
        // The leaves of every biome of the tile, as the game paints them there: the foliage color of the biome for oak and the like
        // (the files of the biomes of Terralith set it for most of them), its own fixed colors for spruce and birch.
        int[] leafBase = new int[species.length];
        for (int i = 0; i < leafBase.length; i++) {
            if (species[i] != null) {
                leafBase[i] = leafBase(species[i], palette.get(i), i < biomes.size() ? biomes.get(i) : null, baseX + extent / 2, baseZ + extent / 2);
            }
        }
        // Coarser tiles get bigger cells, with a tree that stands for a clump.
        int cell = spacing <= 4 ? BASE_CELL : BASE_CELL * spacing / 4;
        float clump = (float) Math.sqrt(cell / (double) BASE_CELL);
        double cells = (double) cell * cell / (BASE_CELL * BASE_CELL);
        Out out = OUT.get();
        out.reset();
        int trees = 0;
        float maxY = -Float.MAX_VALUE;
        int gz1 = Math.floorDiv(baseZ + extent - 1, cell);
        int gx1 = Math.floorDiv(baseX + extent - 1, cell);
        for (int gz = Math.floorDiv(baseZ, cell); gz <= gz1 && trees < MAX_TREES && out.v + MAX_TREE_VERTICES <= MAX_VERTICES; gz++) {
            for (int gx = Math.floorDiv(baseX, cell); gx <= gx1 && trees < MAX_TREES && out.v + MAX_TREE_VERTICES <= MAX_VERTICES; gx++) {
                float lx = (float) ((long) gx * cell - baseX) + unit(hash(gx, gz, 1)) * cell;
                float lz = (float) ((long) gz * cell - baseZ) + unit(hash(gx, gz, 2)) * cell;
                if (lx < 0.0F || lz < 0.0F || lx >= extent || lz >= extent) {
                    continue;
                }
                float fx = lx / spacing;
                float fz = lz / spacing;
                int ix = Math.min((int) fx, MeshBuilder.CELLS - 1);
                int iz = Math.min((int) fz, MeshBuilder.CELLS - 1);
                int a = ix + iz * samples;
                int nearest = Math.round(fx) + Math.round(fz) * samples;
                if (voids[a] || voids[a + 1] || voids[a + samples] || voids[a + samples + 1]
                    || water[a] || water[a + 1] || water[a + samples] || water[a + samples + 1]) {
                    continue;
                }
                int flags = data.flags[nearest];
                if ((flags & (TileData.FLAG_LAVA | TileData.FLAG_ICEBERG)) != 0) {
                    continue;
                }
                Species sp = species[data.biomes[nearest] & 0xFF];
                if (sp == null) {
                    continue;
                }
                float chance = Math.min(1.0F, sp.chance * densityPercent / 100.0F);
                if (cells > 1.0) {
                    chance = (float) (1.0 - Math.pow(1.0 - chance, cells));
                }
                if (unit(hash(gx, gz, 3)) >= chance) {
                    continue;
                }
                float g00 = ground[a];
                float g10 = ground[a + 1];
                float g01 = ground[a + samples];
                float g11 = ground[a + samples + 1];
                float slope = Math.max(Math.max(Math.abs(g10 - g00), Math.abs(g11 - g01)), Math.max(Math.abs(g01 - g00), Math.abs(g11 - g10))) / spacing;
                if (slope > MAX_SLOPE) {
                    continue;
                }
                float tx = fx - ix;
                float tz = fz - iz;
                float y = (g00 * (1 - tx) + g10 * tx) * (1 - tz) + (g01 * (1 - tx) + g11 * tx) * tz;
                float height = sp.height * (0.75F + 0.5F * unit(hash(gx, gz, 4))) * clump;
                int wide = crownWidth(sp.conifer, Math.max(4, Math.round(height)));
                int meanWide = crownWidth(sp.conifer, Math.max(4, Math.round(sp.height * clump)));
                if (beaten(gx, gz, cell, chance, wide, meanWide)) {
                    continue;
                }
                float bright = 0.9F + 0.2F * unit(hash(gx, gz, 6));
                int base = leafBase[data.biomes[nearest] & 0xFF];
                int leaf = base != 0 ? shade(base, bright * LEAF_BRIGHTNESS) : leafColor(colors[nearest], sp, bright);
                // Close trees get every box, far ones fewer: they are small on the screen and there are many.
                int detail = spacing <= 2 ? 2 : spacing <= 8 ? 1 : 0;
                // From afar a tree is one billboard sprite: two crossed flat quads with the pixels of a sprite, tinted like the leaves.
                int spriteId = spriteOf(sp.conifer, Math.max(4, Math.round(height)), hash(gx, gz, 8));
                float top = billboards
                    ? spriteTree(out, sp.conifer, lx, y, lz, height, leaf, hash(gx, gz, 8))
                    : detail >= 1 ? profileTree(out, spriteId, lx, y, lz, height, leaf)
                    : voxelTree(out, sp.conifer, detail, lx, y, lz, height, leaf);
                maxY = Math.max(maxY, top);
                trees++;
            }
        }
        return trees == 0 ? null : new Trees(Arrays.copyOf(out.xyz, out.v * 3), Arrays.copyOf(out.col, out.v), Arrays.copyOf(out.idx, out.n), out.v, out.n, maxY);
    }

    /** Most floats the kept arrays of a thread may have: a thread that grew them beyond this starts over for the next tile. */
    private static final int KEEP_FLOATS = 4096 * 3 * 16;
    /** One set of buffers per meshing thread, the trees of a tile are copied out of it at the end. */
    private static final ThreadLocal<Out> OUT = ThreadLocal.withInitial(Out::new);

    /** The vertices and triangles of the trees of one tile, grown as needed. */
    private static final class Out {
        float[] xyz = new float[4096 * 3];
        int[] col = new int[4096];
        short[] idx = new short[6144];
        int v;
        int n;

        void reset() {
            this.v = 0;
            this.n = 0;
            if (this.xyz.length > KEEP_FLOATS) {
                this.xyz = new float[4096 * 3];
                this.col = new int[4096];
                this.idx = new short[6144];
            }
        }

        /** A face of four corners, drawn from both sides (the pass has no culling). */
        void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, int argb) {
            this.quad(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, argb, argb, argb, argb);
        }

        /** The same with a color of its own at every corner. */
        void quad(
            float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
            int argb0, int argb1, int argb2, int argb3
        ) {
            if ((this.v + 4) * 3 > this.xyz.length) {
                this.xyz = Arrays.copyOf(this.xyz, this.xyz.length * 2);
                this.col = Arrays.copyOf(this.col, this.col.length * 2);
            }
            if (this.n + 6 > this.idx.length) {
                this.idx = Arrays.copyOf(this.idx, this.idx.length * 2);
            }
            int base = this.v;
            float[] c = {x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3};
            int[] argb = {argb0, argb1, argb2, argb3};
            for (int i = 0; i < 4; i++) {
                this.xyz[(base + i) * 3] = c[i * 3];
                this.xyz[(base + i) * 3 + 1] = c[i * 3 + 1];
                this.xyz[(base + i) * 3 + 2] = c[i * 3 + 2];
                this.col[base + i] = argb[i];
            }
            this.v += 4;
            this.idx[this.n++] = (short) base;
            this.idx[this.n++] = (short) (base + 1);
            this.idx[this.n++] = (short) (base + 2);
            this.idx[this.n++] = (short) base;
            this.idx[this.n++] = (short) (base + 2);
            this.idx[this.n++] = (short) (base + 3);
        }

        /**
         * A sprite standing on the ground between two points: the corners carry in their alpha where they are on the sprite
         * (see the vertex shader), the color is the tint of the leaves.
         */
        void sprite(float x0, float z0, float x1, float z1, float y0, float y1, int rgb, int sprite) {
            int base = 0x80 | sprite << 2;
            int tint = rgb & 0xFFFFFF;
            this.quad(
                x0, y0, z0, x1, y0, z1, x1, y1, z1, x0, y1, z0,
                base << 24 | tint, (base | 1) << 24 | tint, (base | 3) << 24 | tint, (base | 2) << 24 | tint
            );
        }

        /** A box without its underside, {@code width} blocks wide, snapped to the grid around the block {@code bx} {@code bz}. */
        void box(int bx, int bz, int width, float y0, float y1, int argb, boolean withTop) {
            this.box(bx, bz, width, y0, y1, argb, withTop, false);
        }

        /** The same, with the underside too where it is seen (the tier of a crown wider than what is under it). */
        void box(int bx, int bz, int width, float y0, float y1, int argb, boolean withTop, boolean withBottom) {
            if (y1 <= y0) {
                return;
            }
            float x0 = bx - width / 2;
            float x1 = x0 + width;
            float z0 = bz - width / 2;
            float z1 = z0 + width;
            int sideX = shade(argb, SHADE_X);
            int sideZ = shade(argb, SHADE_Z);
            this.quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, sideX);
            this.quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, sideX);
            this.quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, sideZ);
            this.quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, sideZ);
            if (withTop) {
                this.quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, argb);
            }
            if (withBottom) {
                this.quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, shade(argb, 0.62F));
            }
        }
    }

    /** The width in blocks of the widest box of a tree of this height, always odd so that it is centered on a block. */
    private static int crownWidth(boolean conifer, int height) {
        return Math.max(3, Math.round(height * (conifer ? 0.45F : 0.5F))) | 1;
    }

    /**
     * Whether a tree of a neighbouring cell would overlap this one's crown and wins over it. Every cell has at most one candidate, at a
     * place hashed from the cell, which counts if it passes the same chance; of two candidates whose crowns would meet, the one with the
     * higher hashed priority stays. It is decided from the hashes alone, so the two tiles on either side of a border agree. The
     * neighbours are taken to be of this tree's own kind, and to have the mean height of it.
     */
    private static boolean beaten(int gx, int gz, int cell, float chance, int wide, int meanWide) {
        int reach = (int) Math.ceil((wide + meanWide) / 2.0 / cell) + 1;
        float mine = unit(hash(gx, gz, 7));
        float limit = (wide + meanWide) / 2.0F;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = gx + dx;
                int nz = gz + dz;
                if (unit(hash(nx, nz, 3)) >= chance) {
                    continue;
                }
                // Where the neighbour stands, relative to this tree (small numbers, so no precision is lost far from the origin).
                float rx = dx * cell + (unit(hash(nx, nz, 1)) - unit(hash(gx, gz, 1))) * cell;
                float rz = dz * cell + (unit(hash(nx, nz, 2)) - unit(hash(gx, gz, 2))) * cell;
                if (Math.abs(rx) >= limit || Math.abs(rz) >= limit) {
                    continue;
                }
                float theirs = unit(hash(nx, nz, 7));
                if (theirs > mine || theirs == mine && (nz < gz || nz == gz && nx < gx)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One tree of boxes on the block grid, as the trees of the game look from afar: a trunk of one block (more for the clumps of
     * coarse tiles) under a crown. Returns the top of the tree.
     */
    private static float voxelTree(Out out, boolean conifer, int detail, float x, float ground, float z, float height, int leaf) {
        int h = Math.max(4, Math.round(height));
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        // Sunk a block into the ground, so a slope under the tree shows no gap.
        float y0 = (float) Math.floor(ground) - 1.0F;
        int wide = crownWidth(conifer, h);
        int trunk = Math.max(1, Math.round(h / 12.0F));
        if (conifer) {
            if (detail == 2) {
                out.box(bx, bz, trunk, y0, y0 + Math.round(h * 0.35F), TRUNK, false);
                out.box(bx, bz, wide, y0 + Math.round(h * 0.2F), y0 + Math.round(h * 0.45F), shade(leaf, 0.8F), true);
                out.box(bx, bz, Math.max(1, wide - 2), y0 + Math.round(h * 0.45F), y0 + Math.round(h * 0.7F), shade(leaf, 0.9F), true);
                out.box(bx, bz, Math.max(1, wide - 4), y0 + Math.round(h * 0.7F), y0 + h, leaf, true);
            } else if (detail == 1) {
                out.box(bx, bz, wide, y0 + Math.round(h * 0.1F), y0 + Math.round(h * 0.55F), shade(leaf, 0.85F), true);
                out.box(bx, bz, Math.max(1, wide - 4), y0 + Math.round(h * 0.55F), y0 + h, leaf, true);
            } else {
                out.box(bx, bz, Math.max(1, (wide * 2 / 3) | 1), y0, y0 + h, shade(leaf, 0.9F), true);
            }
        } else if (detail == 2) {
            out.box(bx, bz, trunk, y0, y0 + Math.round(h * 0.6F), TRUNK, false);
            out.box(bx, bz, wide, y0 + Math.round(h * 0.45F), y0 + Math.round(h * 0.75F), shade(leaf, 0.85F), true);
            out.box(bx, bz, Math.max(1, wide - 2), y0 + Math.round(h * 0.75F), y0 + h, leaf, true);
        } else if (detail == 1) {
            out.box(bx, bz, trunk, y0, y0 + Math.round(h * 0.5F), TRUNK, false);
            out.box(bx, bz, wide, y0 + Math.round(h * 0.45F), y0 + h, leaf, true);
        } else {
            out.box(bx, bz, wide, y0 + Math.round(h * 0.15F), y0 + h, leaf, true);
        }
        return y0 + h;
    }

    /**
     * The profile of each sprite, one entry per pixel row from the top: the width in pixels of the leaves, 0 for the trunk, -1 for
     * nothing. The tree of boxes is built from the same rows as the sprite is drawn from, so the two look alike.
     */
    private static final int[][] PROFILES = {
        {-1, 3, 3, 5, 5, 0, 0},
        {-1, 1, 3, 0, 3, 5, 3, 5, 7, 5, 5, 7, 0, 0, 0},
        {1, 3, 0, 5, 3, 7, 5, 7, 0, 0, 0, 0, 0, 0, 0}
    };

    /** Which sprite a tree is: the small one for the small leafy trees, the big ones for conifers and the tall trees of jungles. */
    private static int spriteOf(boolean conifer, int h, int hash) {
        return conifer ? 1 + (hash >>> 4 & 1) : h >= 14 ? 1 : 0;
    }

    /**
     * One tree of boxes built from the rows of its sprite, a layer of the grid for each row (rows of one width are one box): crowns
     * of odd widths around a trunk one block wide. Returns the top of the tree.
     */
    /** How wide a crown is against the sprite it is built from: the boxes of a crown look much fatter than its sprite. */
    private static final float CROWN_WIDTH = 0.68F;

    private static int crownWidth(int pixels, float scale) {
        return 2 * Math.round(pixels * scale * CROWN_WIDTH / 2.0F) + 1;
    }

    private static float profileTree(Out out, int sprite, float x, float ground, float z, float height, int leaf) {
        int[] rows = PROFILES[sprite];
        int n = rows.length;
        int h = Math.max(4, Math.round(height));
        float scale = (float) h / n;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        // Sunk a block into the ground, so a slope under the tree shows no gap.
        float y0 = (float) Math.floor(ground) - 1.0F;
        int trunkWidth = scale >= 2.5F ? 3 : 1;
        // The segments of equal rows, from the top: first row, last row, width in pixels.
        int[][] segments = new int[n][3];
        int count = 0;
        for (int r = 0; r < n; r++) {
            if (rows[r] < 0) {
                continue;
            }
            if (count > 0 && segments[count - 1][2] == rows[r] && segments[count - 1][1] == r - 1) {
                segments[count - 1][1] = r;
            } else {
                segments[count][0] = r;
                segments[count][1] = r;
                segments[count][2] = rows[r];
                count++;
            }
        }
        // The trunk, in one box, up to the top of the highest trunk row (what is above it is leaves).
        int highestTrunk = -1;
        for (int r = 0; r < n; r++) {
            if (rows[r] == 0) {
                highestTrunk = r;
                break;
            }
        }
        if (highestTrunk >= 0) {
            out.box(bx, bz, trunkWidth, y0, y0 + Math.round(h * (float) (n - highestTrunk) / n), TRUNK, false);
        }
        for (int i = 0; i < count; i++) {
            int px = segments[i][2];
            if (px == 0) {
                continue;
            }
            int width = crownWidth(px, scale);
            float yTop = y0 + Math.round(h * (float) (n - segments[i][0]) / n);
            float yBottom = y0 + Math.round(h * (float) (n - 1 - segments[i][1]) / n);
            // What is under this crown: the next segment, or the trunk.
            int below = trunkWidth;
            for (int j = i + 1; j < count; j++) {
                if (segments[j][2] > 0) {
                    below = crownWidth(segments[j][2], scale);
                    break;
                }
            }
            // The top tier is the lightest, the lowest the darkest.
            float tier = 0.8F + 0.2F * (1.0F - (float) i / Math.max(1, count - 1));
            out.box(bx, bz, width, yBottom, yTop, shade(leaf, tier), true, width > below);
        }
        return y0 + h;
    }

    /**
     * One tree as a billboard: two crossed quads with a sprite, the small one for the small leafy trees, the big ones for conifers and
     * the tall trees of jungles. Returns the top of the tree.
     */
    private static float spriteTree(Out out, boolean conifer, float x, float ground, float z, float height, int leaf, int hash) {
        int h = Math.max(4, Math.round(height));
        float y0 = (float) Math.floor(ground) - 1.0F;
        int sprite = spriteOf(conifer, h, hash);
        // The crown of the sprite fills most of it: the quad is as wide as the crown of the box tree, scaled by what the sprite leaves empty.
        float half = h * 0.38F;
        out.sprite(x - half, z, x + half, z, y0, y0 + h, leaf, sprite);
        out.sprite(x, z - half, x, z + half, y0, y0 + h, leaf, sprite);
        return y0 + h;
    }

    /** The color of the leaves of a kind of tree in a biome, as the game paints the block; 0 when that cannot be told. */
    private static int leafBase(Species sp, Identifier biomeId, @Nullable Holder<Biome> biome, int x, int z) {
        if (sp.tint != 0) {
            return sp.tint;
        }
        if (biome == null) {
            return 0;
        }
        Identifier leaves = sp.conifer ? SPRUCE_LEAVES : biomeId.getPath().contains("birch") ? BIRCH_LEAVES : OAK_LEAVES;
        try {
            return TileColors.blockColor(leaves, biome, x, 80, z, 0.0F);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The leaves take the color of the ground under them, darker, unless that is snow or sand, or the biome has its own. Only the fallback now. */
    private static int leafColor(int ground, Species sp, float bright) {
        if (sp.tint != 0) {
            return shade(sp.tint, bright);
        }
        int r = ground >>> 16 & 255;
        int g = ground >>> 8 & 255;
        int b = ground & 255;
        if ((r + g + b) / 3 > 170 || g < r) {
            return shade(DARK_LEAVES, bright);
        }
        // The faces of the boxes are shaded again by their side, so the base color is lighter than it was for flat shapes.
        return shade(0xFF000000 | r << 16 | g << 8 | b, (sp.conifer ? 0.72F : 0.88F) * bright);
    }

    private static int shade(int argb, float f) {
        int r = Math.min(255, Math.round((argb >>> 16 & 255) * f));
        int g = Math.min(255, Math.round((argb >>> 8 & 255) * f));
        int b = Math.min(255, Math.round((argb & 255) * f));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int hash(int x, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) h;
    }

    private static float unit(int hash) {
        return (hash >>> 8) / 16777216.0F;
    }
}
