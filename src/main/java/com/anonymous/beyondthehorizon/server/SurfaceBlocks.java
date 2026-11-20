package com.anonymous.beyondthehorizon.server;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.mixin.common.MaterialRuleContextAccess;
import com.anonymous.beyondthehorizon.tile.TileData;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import org.jspecify.annotations.Nullable;

/**
 * The block the world's own surface rules put on top of a column, worked out for the samples of a distant tile without generating any
 * chunk. The rules are those of the dimension's generator settings (data packs and mods included), run the way vanilla runs them for
 * the topmost solid block of a column: with the biome, the surface depth noise, the height and the steepness of the ground there.
 * One instance belongs to one generator thread: the context it holds is changed column by column.
 */
final class SurfaceBlocks {
    /** A volume far outside the world, so the context samples the preliminary surface at single points, as vanilla's topMaterial does. */
    private static final DensityVolume NO_VOLUME = new DensityVolume(1, 1, 1, 1 << 29, 0, 1 << 29);
    private static final int S = BeyondTheHorizonConstants.TILE_SAMPLES;
    /** From this sample spacing on the gradient of the ground is limited, see compute. */
    private static final int COARSE_SPACING = 16;
    /** One under the 4 blocks of height over 2 blocks at which vanilla's surface rules call the ground steep. */
    private static final int MAX_COARSE_GRADIENT = 3;

    private final MaterialRuleContext context;
    private final MaterialRuleContextAccess access;
    private final RuleEvaluator evaluator;
    private final BlockState fallback;
    private final int minY;
    private final int seaLevel;
    /** The biome of the column being worked on, handed to the rules when they ask for it. */
    private @Nullable Holder<Biome> biome;
    /** The blocks met so far, kept from tile to tile (cleared for each) instead of made anew. */
    private final Object2IntOpenHashMap<Block> known = new Object2IntOpenHashMap<>();

    /** What a tile gets: the blocks named, and per sample 0 (unknown) or 1 plus the block's index. */
    record Result(List<Identifier> blocks, byte[] indices) {
    }

    private SurfaceBlocks(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState randomState, int minY) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        MaterialRule full = settings.materialRule().value();
        MaterialRule rule = withoutOreVeins(full);
        this.fallback = settings.defaultBlock();
        this.minY = minY;
        this.seaLevel = settings.seaLevel();
        SamplerContext samplerContext = SamplerContext.builder().enableCaches().useBufferArena(new DensityBufferPool(20)).build();
        DensitySamplerSet samplers = randomState.samplersWithContext(samplerContext);
        this.context = MaterialRuleContextAccess.farplane$create(
            randomState.surfaceSystem(), randomState, NO_VOLUME, samplers, pos -> this.biome, new WorldGenerationContext(generator, level), null
        );
        this.access = (MaterialRuleContextAccess) (Object) this.context;
        this.evaluator = (rule != null ? rule : full).compile(this.context);
        this.known.defaultReturnValue(0);
    }

    static @Nullable SurfaceBlocks create(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState randomState, int minY) {
        return new SurfaceBlocks(level, generator, randomState, minY);
    }

    /**
     * The surface block of each sample of a tile. Water samples get the block of the sea floor under them (their depth says where it
     * is); icebergs, lava and the void get none, the client has its own looks for them.
     */
    Result compute(
        TileGenerator.Palette palette, short[] heights, byte[] biomes, byte[] flags, byte @Nullable [] depths, long baseX, long baseZ, int spacing
    ) {
        byte[] indices = new byte[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
        List<Identifier> blocks = new ArrayList<>();
        Object2IntOpenHashMap<Block> known = this.known;
        known.clear();
        for (int sz = 0; sz < S; sz++) {
            for (int sx = 0; sx < S; sx++) {
                int index = sx + sz * S;
                int flag = flags[index];
                if ((flag & (TileData.FLAG_VOID | TileData.FLAG_ICEBERG | TileData.FLAG_LAVA)) != 0) {
                    continue;
                }
                boolean water = (flag & TileData.FLAG_WATER) != 0;
                int y;
                if (water) {
                    int depth = depths == null ? 0 : depths[index] & 0xFF;
                    if (depth == 0) {
                        continue;
                    }
                    y = this.seaLevel - depth - 1;
                } else {
                    y = heights[index] - 1;
                }
                y = Math.max(y, this.minY);
                int west = heights[Math.max(sx - 1, 0) + sz * S];
                int east = heights[Math.min(sx + 1, S - 1) + sz * S];
                int north = heights[sx + Math.max(sz - 1, 0) * S];
                int south = heights[sx + Math.min(sz + 1, S - 1) * S];
                int widthX = (Math.min(sx + 1, S - 1) - Math.max(sx - 1, 0)) * spacing;
                int widthZ = (Math.min(sz + 1, S - 1) - Math.max(sz - 1, 0)) * spacing;
                // Vanilla's gradient is the height difference over two blocks; samples are further apart than that.
                int gradientX = Math.round((east - west) * 2.0F / widthX);
                int gradientZ = Math.round((south - north) * 2.0F / widthZ);
                if (spacing >= COARSE_SPACING) {
                    // Over 16 blocks or more a rolling hill rises by many blocks, which vanilla's steep rule (4 blocks over 2) would take
                    // for a cliff and put bare stone on every slope; on such tiles the ground is never steeper than that.
                    gradientX = Mth.clamp(gradientX, -MAX_COARSE_GRADIENT, MAX_COARSE_GRADIENT);
                    gradientZ = Mth.clamp(gradientZ, -MAX_COARSE_GRADIENT, MAX_COARSE_GRADIENT);
                }
                int x = TileGenerator.clampToWorld(baseX + (long) sx * spacing);
                int z = TileGenerator.clampToWorld(baseZ + (long) sz * spacing);
                this.biome = palette.holders.get(biomes[index] & 0xFF);
                this.access.farplane$updateXZ(x, z, gradientX, gradientZ);
                // The topmost solid block of its column: one block of stone above it counts it, all the way down to the bottom is stone.
                this.access.farplane$updateY(1, Math.max(1, y - this.minY + 1), water ? this.seaLevel : Integer.MIN_VALUE, y);
                BlockState state = this.evaluator.tryApply(x, y, z);
                Block block = (state != null ? state : this.fallback).getBlock();
                int number = known.getInt(block);
                if (number == 0) {
                    if (blocks.size() >= TileData.MAX_BLOCKS) {
                        continue;
                    }
                    blocks.add(BuiltInRegistries.BLOCK.getKey(block));
                    number = blocks.size();
                    known.put(block, number);
                }
                indices[index] = (byte) number;
            }
        }
        this.biome = null;
        return new Result(blocks, indices);
    }

    /** A material rule with its ore veins left out, which only show deep under the ground; the rule itself when it has none. */
    private static @Nullable MaterialRule withoutOreVeins(MaterialRule rule) {
        if (rule instanceof OreVeinRule) {
            return null;
        }
        if (rule instanceof MaterialRule.HolderHolder(var holder)) {
            MaterialRule inner = withoutOreVeins(holder.value());
            return inner == holder.value() ? rule : inner;
        }
        if (rule instanceof SequenceRule(var sequence)) {
            List<MaterialRule> kept = new ArrayList<>(sequence.size());
            boolean changed = false;
            for (MaterialRule part : sequence) {
                MaterialRule inner = withoutOreVeins(part);
                changed |= inner != part;
                if (inner != null) {
                    kept.add(inner);
                }
            }
            return !changed ? rule : kept.isEmpty() ? null : new SequenceRule(List.copyOf(kept));
        }
        return rule;
    }
}
