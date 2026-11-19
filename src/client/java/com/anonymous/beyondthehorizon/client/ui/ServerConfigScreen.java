package com.anonymous.beyondthehorizon.client.ui;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.config.ServerConfig;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The settings of the tile server, the ones in beyond-the-terrain-server.json. Only offered where this game is the server, that is
 * in a singleplayer world; a dedicated server is still set up in its file.
 *
 * <p>The screen changes a copy that is read from the file. When it closes, the settings the running server reads on every request
 * are taken over at once and the whole copy is written to the file. Threads, priority and caches are set up when the world opens, so
 * those wait for the next start and the player is told so.
 */
public class ServerConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = BeyondTheHorizonConstants.MOD_ID + ".server.";

    private final ServerConfig live;
    private final ServerConfig edit;
    private final ServerConfig before;

    public ServerConfigScreen(Screen lastScreen, ServerConfig live) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "title"));
        this.live = live;
        // Read from the file, so that a change that is still waiting for the next start is shown and kept.
        this.edit = ServerConfig.load();
        this.before = this.edit.copy();
    }

    private static OptionInstance<Integer> slider(String key, int min, int max, int value, IntFunction<Component> label, IntConsumer set) {
        return new OptionInstance<>(
            PREFIX + key,
            OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, label.apply(v)),
            new OptionInstance.IntRange(min, max),
            Mth.clamp(value, min, max),
            set::accept
        );
    }

    private static OptionInstance<Boolean> toggle(String key, boolean value, Consumer<Boolean> set) {
        return OptionInstance.createBoolean(
            PREFIX + key, OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + key + ".tooltip")), value, set::accept
        );
    }

    private static Component plain(int value) {
        return Component.literal(Integer.toString(value));
    }

    private static Component auto(int value) {
        return value == 0 ? Component.translatable(PREFIX + "auto") : plain(value);
    }

    private static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    @Override
    public void removed() {
        super.removed();
        this.edit.sanitize();
        boolean restart = this.before.needsRestart(this.edit);
        this.live.applyLive(this.edit);
        this.edit.save();
        Minecraft minecraft = Minecraft.getInstance();
        if (restart && minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable(PREFIX + "rejoin"));
        }
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ServerConfig c = this.edit;
        String blocks = Component.translatable(BeyondTheHorizonConstants.MOD_ID + ".options.blocks").getString();
        String tiles = Component.translatable(BeyondTheHorizonConstants.MOD_ID + ".options.tiles").getString();

        // Generating the distant terrain.
        this.list.addBig(slider("generator_threads", 0, 16, c.generatorThreads, ServerConfigScreen::auto, v -> c.generatorThreads = v));
        this.list.addBig(slider("generator_priority", Thread.MIN_PRIORITY, Thread.MAX_PRIORITY - 1, c.generatorPriority, ServerConfigScreen::plain, v -> c.generatorPriority = v));
        this.list.addBig(slider("in_flight_per_thread", 1, 16, c.inFlightPerThread, ServerConfigScreen::plain, v -> c.inFlightPerThread = v));
        this.list.addBig(slider("max_in_flight", 1, 64, c.maxInFlightPerPlayer, ServerConfigScreen::plain, v -> c.maxInFlightPerPlayer = v));
        // In steps of 16 requests.
        this.list.addBig(slider("max_queued", 1, 256, c.maxQueuedRequestsPerPlayer / 16, v -> plain(v * 16), v -> c.maxQueuedRequestsPerPlayer = v * 16));
        this.list.addBig(slider("min_level", 0, BeyondTheHorizonConstants.MAX_MIN_LEVEL, c.minLevel, ServerConfigScreen::plain, v -> c.minLevel = v));
        // In steps of 1 000 blocks; 0 is the whole world.
        this.list.addBig(slider("max_distance", 0, 200,
            c.maxDistance >= BeyondTheHorizonConstants.MAX_LOD_DISTANCE ? 0 : Mth.clamp(c.maxDistance / 1000, 1, 200),
            v -> v == 0 ? Component.translatable(PREFIX + "whole_world") : Component.literal(number(v * 1000) + " " + blocks),
            v -> c.maxDistance = v == 0 ? BeyondTheHorizonConstants.MAX_LOD_DISTANCE : v * 1000));
        this.list.addBig(toggle("enabled", c.enabled, v -> c.enabled = v));

        // Generating ahead of time by /farplane pregen distant.
        this.list.addBig(slider("pregen_threads", 0, 16, c.pregenThreads, ServerConfigScreen::auto, v -> c.pregenThreads = v));
        // The distant structures, a layer apart from the terrain; in steps of 256 blocks.
        this.list.addBig(toggle("structures", c.structuresEnabled, v -> c.structuresEnabled = v));
        this.list.addBig(slider("structure_distance", 2, 64, Mth.clamp(c.structureDistance / 256, 2, 64), v -> Component.literal(number(v * 256) + " " + blocks), v -> c.structureDistance = v * 256));

        // The caches.
        this.list.addBig(toggle("disk_cache", c.diskCache, v -> c.diskCache = v));
        // In steps of 256 tiles.
        this.list.addBig(slider("memory_cache", 0, 256, c.memoryCacheTiles / 256, v -> Component.literal(number(v * 256) + " " + tiles), v -> c.memoryCacheTiles = v * 256));

        // The block-level layer, which the client of this version does not draw yet.
        this.list.addBig(slider("detail_radius", 0, BeyondTheHorizonConstants.MAX_DETAIL_RADIUS_CHUNKS, c.detailMaxRadiusChunks, ServerConfigScreen::plain, v -> c.detailMaxRadiusChunks = v));
        this.list.addBig(toggle("detail_read_saved", c.detailReadSavedChunks, v -> c.detailReadSavedChunks = v));
        this.list.addBig(toggle("detail_generate_unexplored", c.detailGenerateUnexplored, v -> c.detailGenerateUnexplored = v));
        this.list.addBig(slider("detail_threads", 0, 8, c.detailThreads, ServerConfigScreen::auto, v -> c.detailThreads = v));
        this.list.addBig(slider("detail_scan_depth", BeyondTheHorizonConstants.MIN_SCAN_DEPTH, BeyondTheHorizonConstants.MAX_SCAN_DEPTH, c.detailScanDepth, ServerConfigScreen::plain, v -> c.detailScanDepth = v));
        this.list.addBig(slider("detail_scan_depth_end", BeyondTheHorizonConstants.MIN_SCAN_DEPTH, BeyondTheHorizonConstants.MAX_SCAN_DEPTH, c.detailScanDepthEnd, ServerConfigScreen::plain, v -> c.detailScanDepthEnd = v));
        this.list.addBig(toggle("detail_ore_veins", c.detailOreVeins, v -> c.detailOreVeins = v));
    }
}
