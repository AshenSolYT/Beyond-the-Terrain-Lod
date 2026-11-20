package com.anonymous.beyondthehorizon.server;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.tile.BlockLook;
import com.anonymous.beyondthehorizon.tile.TileData;
import com.anonymous.beyondthehorizon.tile.TilePos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Puts the real ground over the noise of a fine tile, where the world has chunks (loaded ones, or saved on disk): explored terrain and
 * what players built then match the world sample by sample. Samples over chunks that do not exist keep what the generator found.
 * Trees (leaves and logs), plants and air are looked through to the ground, as the generator's terrain has none of them. Blocks that
 * are built (planks, bricks, doors, glass, containers and the like) are part of the ground only when {@code builtBlocks} is set (the
 * structures and player-made builds setting, see ServerConfig); otherwise a column topped by them keeps the generated terrain.
 */
final class RealSurface {
    private static final int S = BeyondTheHorizonConstants.TILE_SAMPLES;

    private RealSurface() {
    }

    /** Where the summaries of the world's chunks come from. */
    interface Chunks {
        @Nullable ChunkSummary summary(int chunkX, int chunkZ);
    }

    /**
     * The tile with the real ground taken over, or the tile itself when no sample lies over a chunk with data. Tiles of the End, which
     * have no water depths, and averaged (coarse) tiles are left alone.
     */
    static TileData apply(ServerLevel level, TilePos pos, TileData data, Chunks chunks, boolean builtBlocks) {
        if (data.subBiomes != null || data.waterDepths == null) {
            return data;
        }
        int spacing = 1 << pos.level();
        int seaLevel = level.getSeaLevel();
        short[] heights = data.heights.clone();
        byte[] biomes = data.biomes.clone();
        byte[] flags = data.flags.clone();
        byte[] depths = data.waterDepths.clone();
        byte[] surface = data.surface != null ? data.surface.clone() : new byte[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
        List<Identifier> palette = new ArrayList<>(data.palette);
        List<Identifier> blocks = new ArrayList<>(data.blocks);
        boolean changed = false;
        int[] ground = new int[3];
        short[] segBlocks = new short[ChunkSummary.SEGMENT_CAP];
        short[] segHeights = new short[ChunkSummary.SEGMENT_CAP];
        byte[] segLights = new byte[ChunkSummary.SEGMENT_CAP];
        for (int sz = 0; sz < S; sz++) {
            for (int sx = 0; sx < S; sx++) {
                int x = TileGenerator.clampToWorld(pos.minX() + (long) sx * spacing);
                int z = TileGenerator.clampToWorld(pos.minZ() + (long) sz * spacing);
                ChunkSummary summary = chunks.summary(x >> 4, z >> 4);
                if (summary == null) {
                    continue;
                }
                int index = sx + sz * S;
                int kind = column(summary, x & 15, z & 15, seaLevel, builtBlocks, ground, segBlocks, segHeights, segLights);
                if (kind == NONE) {
                    continue;
                }
                changed = true;
                heights[index] = (short) Mth.clamp(ground[0], Short.MIN_VALUE, Short.MAX_VALUE);
                flags[index] = (byte) (kind == WATER ? TileData.FLAG_WATER : kind == LAVA ? TileData.FLAG_LAVA : 0);
                depths[index] = (byte) (kind == WATER ? Mth.clamp(ground[1], 1, 255) : 0);
                Holder<Biome> biome = summary.biome(x & 15, z & 15);
                if (biome != null) {
                    Identifier id = biome.unwrapKey().map(key -> key.identifier()).orElse(null);
                    if (id != null) {
                        int number = palette.indexOf(id);
                        if (number < 0 && palette.size() < TileData.MAX_PALETTE) {
                            palette.add(id);
                            number = palette.size() - 1;
                        }
                        if (number >= 0) {
                            biomes[index] = (byte) number;
                        }
                    }
                }
                surface[index] = 0;
                if (kind != LAVA && ground[2] > 0) {
                    Identifier block = BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(ground[2]));
                    int number = blocks.indexOf(block) + 1;
                    if (number == 0 && blocks.size() < TileData.MAX_BLOCKS) {
                        blocks.add(block);
                        number = blocks.size();
                    }
                    surface[index] = (byte) number;
                }
            }
        }
        if (!changed) {
            return data;
        }
        return new TileData(heights, biomes, flags, palette, null, null, depths, blocks, surface);
    }

    /** Blocks that are not found in the natural ground and make up houses, farms, paths and the like. */
    private static final Set<Block> BUILT = Set.of(
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
        Blocks.CHISELED_STONE_BRICKS, Blocks.BRICKS, Blocks.HAY_BLOCK, Blocks.GLASS, Blocks.GLASS_PANE, Blocks.IRON_BARS, Blocks.TORCH,
        Blocks.WALL_TORCH, Blocks.LANTERN, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE,
        Blocks.SMOOTH_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE, Blocks.CRAFTING_TABLE, Blocks.COMPOSTER,
        Blocks.GRINDSTONE, Blocks.STONECUTTER, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE, Blocks.BOOKSHELF,
        Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.JACK_O_LANTERN, Blocks.COBWEB
    );

    /** Whether a block is part of something built: by tag (planks, stairs, doors, wool and the like), by kind (blocks with an inventory or a state, as chests and signs), or by name. */
    private static boolean isBuilt(Block block, BlockState state) {
        return block instanceof EntityBlock
            || BUILT.contains(block)
            || state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS) || state.is(BlockTags.WALLS)
            || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
            || state.is(BlockTags.BEDS) || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.BANNERS);
    }

    private static final int NONE = 0;
    private static final int LAND = 1;
    private static final int WATER = 2;
    private static final int LAVA = 3;

    /**
     * The ground of one column, from the top down: air, plants, leaves and logs are looked through; the first block that is none of
     * them is the ground. {@code out}: 0 the height just above the ground (or the water surface of a lake, which is not at the sea
     * level and is drawn as land), 1 the depth of the sea over it, 2 the raw id of the surface block. Returns NONE for a column without ground.
     */
    private static int column(
        ChunkSummary summary, int x, int z, int seaLevel, boolean builtBlocks, int[] out, short[] blocks, short[] heights, byte[] lights
    ) {
        int count = summary.segments(x, z, blocks, heights, lights, 0);
        int top = summary.stackTop(x, z);
        boolean water = false;
        int waterTop = 0;
        for (int i = 0; i < count; i++) {
            int segmentTop = top;
            top -= heights[i];
            int id = Short.toUnsignedInt(blocks[i]);
            if (id == 0) {
                continue;
            }
            int raw = BlockLook.segmentBlock(id);
            Block block = BuiltInRegistries.BLOCK.byId(raw);
            if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) {
                if (!water) {
                    water = true;
                    waterTop = segmentTop;
                }
                continue;
            }
            BlockState state = block.defaultBlockState();
            if (BlockLook.look(state).plant() != 0) {
                if (BlockLook.segmentWet(id) && !water) {
                    water = true;
                    waterTop = segmentTop;
                }
                continue;
            }
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                continue;
            }
            // Without builds, a column topped by something built keeps the generated terrain: looked through, the ground under a house
            // would be a pit in the picture.
            if (!builtBlocks && isBuilt(block, state)) {
                return NONE;
            }
            if (block == Blocks.LAVA) {
                out[0] = segmentTop;
                out[1] = 0;
                out[2] = 0;
                return LAVA;
            }
            if (water && waterTop == seaLevel) {
                out[0] = seaLevel;
                out[1] = waterTop - segmentTop;
                out[2] = raw;
                return out[1] > 0 ? WATER : NONE;
            }
            // A lake above or below the sea level cannot be drawn as sea: its surface is the ground, with its water as the block.
            out[0] = water ? waterTop : segmentTop;
            out[1] = 0;
            out[2] = water ? BuiltInRegistries.BLOCK.getId(Blocks.WATER) : raw;
            return LAND;
        }
        return NONE;
    }
}
