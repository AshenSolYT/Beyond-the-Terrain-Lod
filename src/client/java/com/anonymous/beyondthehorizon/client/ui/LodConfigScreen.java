package com.anonymous.beyondthehorizon.client.ui;

import com.anonymous.beyondthehorizon.BeyondTheHorizon;
import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.client.BeyondTheHorizonClient;
import com.anonymous.beyondthehorizon.client.config.ClientConfig;
import com.anonymous.beyondthehorizon.server.TileServer;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** All of the settings on one screen. */
public class LodConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = BeyondTheHorizonConstants.MOD_ID + ".options.";

    public LodConfigScreen(Screen lastScreen) {
        super(lastScreen, Minecraft.getInstance().options, Component.translatable(PREFIX + "title"));
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

    /** The box the distance is typed in; read when the screen closes. */
    private EditBox distanceBox;

    @Override
    public void removed() {
        ClientConfig config = BeyondTheHorizonClient.manager().config();
        if (this.distanceBox != null) {
            try {
                config.distanceChunks = Integer.parseInt(this.distanceBox.getValue().replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                // Nothing typed: the value stays.
            }
        }
        super.removed();
        config.clamp();
        config.save();
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }
        ClientConfig c = BeyondTheHorizonClient.manager().config();
        this.list.addBig(OptionInstance.createBoolean(
            PREFIX + "enabled", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "enabled.tooltip")), c.enabled, v -> c.enabled = v
        ));
        // Typed in, the label on the left and the box on the right.
        Font font = Minecraft.getInstance().font;
        StringWidget distanceLabel = new StringWidget(Component.translatable(PREFIX + "distance"), font);
        distanceLabel.setWidth(150);
        this.distanceBox = new EditBox(font, 0, 0, 150, 20, Component.translatable(PREFIX + "distance"));
        this.distanceBox.setMaxLength(5);
        this.distanceBox.setValue(Integer.toString(c.distanceChunks));
        this.distanceBox.setTooltip(Tooltip.create(Component.translatable(PREFIX + "distance.tooltip")));
        this.list.addSmall(distanceLabel, this.distanceBox);
        this.list.addBig(slider("sharpness", 10, 80, c.sharpness, v -> Component.literal(String.format(Locale.ROOT, "%.1f", v / 10.0)), v -> c.sharpness = v));
        this.list.addBig(slider("start_level", 0, 6, c.startLevel, v -> Component.literal(Integer.toString(1 << v)), v -> c.startLevel = v));
        this.list.addBig(slider("max_tiles", 200, 6000, c.maxTiles, v -> Component.literal(number(v) + " " + Component.translatable(PREFIX + "tiles").getString()), v -> c.maxTiles = v / 100 * 100));
        this.list.addBig(slider("fog_mode", 0, 3, c.fogMode, v -> Component.translatable(PREFIX + "fog_mode." + v), v -> c.fogMode = v));
        this.list.addBig(slider("fog_start", 0, 99, c.fogStartPercent, v -> Component.literal(v + " %"), v -> c.fogStartPercent = v));
        // 0 off, then 2x2, 4x4 and 8x8.
        this.list.addBig(slider("pixel_filter", 0, 3, c.pixelFilter == 0 ? 0 : Integer.numberOfTrailingZeros(c.pixelFilter),
            v -> v == 0 ? Component.translatable(PREFIX + "off") : Component.literal((1 << v) + "x" + (1 << v)), v -> c.pixelFilter = v == 0 ? 0 : 1 << v));
        this.list.addBig(slider("step_level", 0, 5, c.stepLevel + 1, v -> v == 0 ? Component.translatable(PREFIX + "off") : Component.literal((1 << (v - 1)) + " " + Component.translatable(PREFIX + "blocks").getString()), v -> c.stepLevel = v - 1));
        this.list.addBig(slider("tree_level", 0, 4, c.treeLevel, v -> v == 0 ? Component.translatable(PREFIX + "off") : Component.literal((1 << v) + " " + Component.translatable(PREFIX + "blocks").getString()), v -> c.treeLevel = v));
        this.list.addBig(slider("tree_density", 10, 200, c.treeDensity, v -> Component.literal(v + " %"), v -> c.treeDensity = v));
        this.list.addBig(slider("tree_billboard", 0, 5, c.treeBillboardLevel, v -> v == 5 ? Component.translatable(PREFIX + "off") : Component.literal((1 << v) + " " + Component.translatable(PREFIX + "blocks").getString()), v -> c.treeBillboardLevel = v));
        this.list.addBig(slider("structure_range", 0, 32, c.structureRange, v -> v == 0 ? Component.translatable(PREFIX + "off") : Component.literal((v * 256) + " " + Component.translatable(PREFIX + "blocks").getString()), v -> c.structureRange = v));
        this.list.addBig(Button.builder(Component.translatable(PREFIX + "clear_cache"), button -> {
            BeyondTheHorizonClient.manager().reloadAll();
            button.setMessage(Component.translatable(PREFIX + "clear_cache.done"));
        }).build());
        this.list.addBig(OptionInstance.createBoolean(
            PREFIX + "water_fog", OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "water_fog.tooltip")), c.waterFog, v -> c.waterFog = v
        ));
        // The settings of the tile server: only where this game is the server, that is in a singleplayer world.
        boolean hosting = Minecraft.getInstance().getSingleplayerServer() != null && BeyondTheHorizon.tileServer() != null;
        Button serverButton = Button.builder(Component.translatable(PREFIX + "server_settings"), button -> {
            TileServer current = BeyondTheHorizon.tileServer();
            if (current != null) {
                Minecraft.getInstance().gui.setScreen(new ServerConfigScreen(this, current.config()));
            }
        }).build();
        serverButton.active = hosting;
        serverButton.setTooltip(Tooltip.create(Component.translatable(PREFIX + (hosting ? "server_settings.tooltip" : "server_settings.unavailable"))));
        this.list.addBig(serverButton);
    }

    private static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
