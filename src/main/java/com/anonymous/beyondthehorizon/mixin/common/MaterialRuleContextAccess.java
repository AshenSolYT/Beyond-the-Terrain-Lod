package com.anonymous.beyondthehorizon.mixin.common;

import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The surface rules' context has no public constructor and no public way to be moved to another column or height, and vanilla only
 * builds it for whole chunks (buildSurface) or for one block of a real chunk (topMaterial). Distant tiles need the surface block of
 * single columns far from any chunk, see SurfaceBlocks.
 */
@Mixin(MaterialRuleContext.class)
public interface MaterialRuleContextAccess {
    @Invoker("<init>")
    static MaterialRuleContext farplane$create(
        MaterialSystem system, RandomState randomState, DensityVolume expectedVolume, DensitySamplerSet samplers,
        Function<BlockPos, Holder<Biome>> biomeGetter, WorldGenerationContext context, @Nullable Set<Holder<Biome>> possibleBiomes
    ) {
        throw new AssertionError();
    }

    /** Moves to a column: its position and the height differences of its surface to the next column east and south. */
    @Invoker("updateXZ")
    void farplane$updateXZ(int blockX, int blockZ, int surfaceGradientX, int surfaceGradientZ);

    /** Moves to a block of the column: stone above and below it, the height just over the water above it, and its height. */
    @Invoker("updateY")
    void farplane$updateY(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int blockY);
}
