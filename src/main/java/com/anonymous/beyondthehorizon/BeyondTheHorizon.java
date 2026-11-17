package com.anonymous.beyondthehorizon;

import com.anonymous.beyondthehorizon.config.ServerConfig;
import com.anonymous.beyondthehorizon.net.BeyondTheHorizonPayloads;
import com.anonymous.beyondthehorizon.server.BeyondTheHorizonCommand;
import com.anonymous.beyondthehorizon.server.StructureServer;
import com.anonymous.beyondthehorizon.server.TileServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.jspecify.annotations.Nullable;

public class BeyondTheHorizon implements ModInitializer {
    private static @Nullable TileServer tileServer;
    private static @Nullable StructureServer structureServer;

    /** The tile server of the world this game is running (singleplayer or dedicated server), if any. */
    public static @Nullable TileServer tileServer() {
        return tileServer;
    }

    @Override
    public void onInitialize() {
        BeyondTheHorizonPayloads.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TileServer started = new TileServer(server, ServerConfig.load());
            tileServer = started;
            structureServer = new StructureServer(server, started.config());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (structureServer != null) {
                structureServer.close();
                structureServer = null;
            }
            if (tileServer != null) {
                tileServer.close();
                tileServer = null;
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BeyondTheHorizonCommand.register(dispatcher, () -> tileServer));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (tileServer != null) {
                tileServer.tick();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (tileServer != null) {
                tileServer.sendHello(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (tileServer != null) {
                tileServer.onDisconnect(handler.getPlayer().getUUID());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.TileRequest.TYPE, (payload, context) -> {
            if (tileServer != null) {
                tileServer.onRequest(context.player(), payload.requests());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.DetailRequest.TYPE, (payload, context) -> {
            if (tileServer != null) {
                tileServer.onDetailRequest(context.player(), payload.requests(), payload.generate(), payload.urgency() / 100.0F, payload.scanDepth(), payload.oreVeins());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.StructureRequest.TYPE, (payload, context) -> {
            if (structureServer != null) {
                structureServer.onRequest(context.player(), payload.regions());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(BeyondTheHorizonPayloads.ClearCache.TYPE, (payload, context) -> {
            if (tileServer != null) {
                tileServer.onClearCache(context.player());
            }
        });
    }
}
