package com.anonymous.beyondthehorizon.client;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.BeyondTheHorizonDebug;
import com.anonymous.beyondthehorizon.client.color.SurfaceLearner;
import com.anonymous.beyondthehorizon.client.lod.PerfStats;
import com.anonymous.beyondthehorizon.client.config.ClientConfig;
import com.anonymous.beyondthehorizon.client.lod.LodManager;
import com.anonymous.beyondthehorizon.client.render.LodRenderer;
import com.anonymous.beyondthehorizon.client.ui.LodConfigScreen;
import com.anonymous.beyondthehorizon.net.BeyondTheHorizonPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Client entry: the network receivers, the keys and the tick. The rest is in {@link LodManager} and {@link LodRenderer}. */
public final class BeyondTheHorizonClient implements ClientModInitializer {
    private static LodManager manager;
    private static LodRenderer renderer;
    private static KeyMapping reloadKey;

    public static LodManager manager() {
        return manager;
    }

    public static LodRenderer renderer() {
        return renderer;
    }

    public static KeyMapping reloadKey() {
        return reloadKey;
    }

    @Override
    public void onInitializeClient() {
        manager = new LodManager(Minecraft.getInstance(), ClientConfig.load());
        renderer = new LodRenderer(manager);

        ClientPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.Hello.TYPE, (payload, context) -> manager.onHello(payload));
        ClientPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.TileDataPayload.TYPE, (payload, context) -> manager.onTileData(payload));
        ClientPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.StructureDataPayload.TYPE, (payload, context) -> manager.onStructureData(payload));
        ClientPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.CacheCleared.TYPE, (payload, context) -> manager.onCacheCleared(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
        ClientPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.DetailDataPayload.TYPE, (payload, context) -> manager.onDetailData(payload));
        // The real ground the player sees teaches the distant terrain its colors, see SurfaceLearner.
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (manager.config().enabled && !BeyondTheHorizonDebug.NO_LEARN) {
                long start = System.nanoTime();
                SurfaceLearner.learnChunk(level, chunk);
                PerfStats.add(PerfStats.Timer.LEARN, System.nanoTime() - start);
                PerfStats.count(PerfStats.Count.LEARNED_CHUNKS);
            }
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BeyondTheHorizonConstants.MOD_ID, "main"));
        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key." + BeyondTheHorizonConstants.MOD_ID + ".toggle", InputConstants.KEY_F7, category)
        );
        KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key." + BeyondTheHorizonConstants.MOD_ID + ".settings", InputConstants.KEY_F8, category)
        );
        // Used together with F3, like vanilla's F3 + A, so it lives in the vanilla Debug category; see KeyboardHandlerMixin.
        reloadKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key." + BeyondTheHorizonConstants.MOD_ID + ".reload", InputConstants.KEY_O, KeyMapping.Category.DEBUG)
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                ClientConfig config = manager.config();
                config.enabled = !config.enabled;
                config.save();
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.translatable(BeyondTheHorizonConstants.MOD_ID + (config.enabled ? ".toggle.on" : ".toggle.off")));
                }
            }
            while (reloadKey.consumeClick()) {
                // A press without F3 does nothing, the combination is handled in KeyboardHandlerMixin.
            }
            while (settingsKey.consumeClick()) {
                client.gui.setScreen(new LodConfigScreen(client.gui.screen()));
            }
            manager.tick();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            manager.close();
            renderer.close();
            SurfaceLearner.save();
        });
    }
}
