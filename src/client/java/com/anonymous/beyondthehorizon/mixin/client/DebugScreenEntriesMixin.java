package com.anonymous.beyondthehorizon.mixin.client;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.client.lod.PerfDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the cost lines of the mod to the entries of the F3 screen, once vanilla has registered its own. */
@Mixin(value = DebugScreenEntries.class, remap = false)
abstract class DebugScreenEntriesMixin {
    @Shadow
    private static Identifier register(Identifier id, DebugScreenEntry entry) {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void beyondthehorizon$addPerformanceLines(CallbackInfo ci) {
        register(Identifier.fromNamespaceAndPath(BeyondTheHorizonConstants.MOD_ID, "performance"), new PerfDebugEntry());
    }
}
