package com.anonymous.beyondthehorizon.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The client's settings, in {@code config/beyond-the-terrain-client.properties}. Plain numbers, nothing is a preset. */
public final class ClientConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Terrain");

    /** This mod's own fog at the end of the view. */
    public static final int FOG_OWN = 0;
    /** This mod's own fog, unless another mod has changed vanilla's. */
    public static final int FOG_AUTO = 1;
    /** Vanilla's fog goes on over the terrain as it is. */
    public static final int FOG_OTHER = 2;
    public static final int FOG_OFF = 3;

    public static final int MIN_DISTANCE_CHUNKS = 24;
    public static final int MAX_DISTANCE_CHUNKS = 10000;

    public boolean enabled = true;
    /** How far the terrain is drawn, in chunks. */
    public int distanceChunks = 256;
    /** The finest level drawn; a tile of level n has cells of 2^n blocks. */
    public int startLevel = 1;
    /** How soon tiles split into finer ones: the camera has to be nearer than a tile's size times this divided by ten. */
    public int sharpness = 20;
    /** Most tiles kept on the GPU. */
    public int maxTiles = 1500;
    public int fogMode = FOG_OWN;
    /** Where the fog starts, in percent of where it ends. */
    public int fogStartPercent = 75;
    public boolean waterFog = true;
    /** Every triangle of the terrain has one color, so the ground looks like blocks, not like a smooth blend. */
    /** The pixelated filter: the smooth colors read on a grid this many times finer than the terrain samples, with a little noise. 0 is off, else 2, 4 or 8. */
    public int pixelFilter = 0;
    /** Trees (approximated) on or off: boxes within {@link #treeDistance}, billboard sprites beyond it. */
    public boolean trees = true;
    public int treeDensity = 100;
    /** Block Heightmap on or off: the finest tiles (up to LodHeightmap.MAX_STEP_LEVEL) within {@link #blockHeightmapDistance} are columns (flat tops, walls between them) instead of the smooth ground. */
    public boolean blockHeightmap = true;
    /**
     * How far from the camera tiles may be drawn as Block Heightmap columns, in blocks. Independent of the sample spacing above (the detail) and
     * of how soon tiles split (sharpness): the planner keeps the tiles within this distance as fine as the columns need (spacing 2), so
     * this distance alone decides how far the Block Heightmap reaches. More distance means many more tiles to load and draw.
     */
    public int blockHeightmapDistance = 512;
    /** How far from the camera the trees are boxes, in blocks; beyond it they are billboard sprites. */
    public int treeDistance = 1024;

    public int distanceBlocks() {
        return this.distanceChunks * 16;
    }

    public float fogStart(float end) {
        return end * Mth.clamp(this.fogStartPercent, 0, 99) / 100.0F;
    }

    private static Path path() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path path = dir.resolve("beyond-the-terrain-client.properties");
        Path legacy = dir.resolve("beyond-the-horizon-client.properties");
        try {
            if (!Files.exists(path) && Files.isRegularFile(legacy)) {
                Files.move(legacy, path);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not move {} to {}", legacy, path, e);
        }
        return path;
    }

    public static ClientConfig load() {
        ClientConfig config = new ClientConfig();
        Path path = path();
        if (Files.isRegularFile(path)) {
            Properties p = new Properties();
            try (Reader in = Files.newBufferedReader(path)) {
                p.load(in);
                config.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
                // Before 0.0.3.2 the distance and the end of the fog were in blocks.
                config.distanceChunks = p.containsKey("distanceChunks") ? number(p, "distanceChunks", config.distanceChunks) : number(p, "distance", config.distanceChunks * 16) / 16;
                config.startLevel = number(p, "startLevel", config.startLevel);
                config.sharpness = number(p, "sharpness", config.sharpness);
                config.maxTiles = number(p, "maxTiles", config.maxTiles);
                config.fogMode = number(p, "fogMode", config.fogMode);
                config.fogStartPercent = number(p, "fogStartPercent", config.fogStartPercent);
                config.waterFog = Boolean.parseBoolean(p.getProperty("waterFog", "true"));
                config.pixelFilter = number(p, "pixelFilter", config.pixelFilter);
                // Before 0.8.16 the trees and the Block Heightmap were levels: 0 (or -1) was off.
                config.trees = p.containsKey("trees") ? Boolean.parseBoolean(p.getProperty("trees")) : number(p, "treeLevel", 2) > 0;
                config.treeDensity = number(p, "treeDensity", config.treeDensity);
                // Before 0.8.12 these were "stepSpacing" and "stepDistance" (the stepped terrain, now the Block Heightmap).
                config.blockHeightmap = p.containsKey("blockHeightmap") ? Boolean.parseBoolean(p.getProperty("blockHeightmap"))
                    : (p.containsKey("blockHeightmapLevel") ? number(p, "blockHeightmapLevel", 3) : number(p, "stepSpacing", 3)) >= 0;
                config.blockHeightmapDistance = p.containsKey("blockHeightmapDistance") ? number(p, "blockHeightmapDistance", config.blockHeightmapDistance) : number(p, "stepDistance", config.blockHeightmapDistance);
                config.treeDistance = number(p, "treeDistance", config.treeDistance);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read {}, using defaults", path, e);
            }
        }
        config.clamp();
        return config;
    }

    private static int number(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void clamp() {
        this.distanceChunks = Mth.clamp(this.distanceChunks, MIN_DISTANCE_CHUNKS, MAX_DISTANCE_CHUNKS);
        this.startLevel = Mth.clamp(this.startLevel, 0, 6);
        this.sharpness = Mth.clamp(this.sharpness, 10, 80);
        this.maxTiles = Mth.clamp(this.maxTiles, 200, 20000);
        this.fogMode = Mth.clamp(this.fogMode, 0, 3);
        this.fogStartPercent = Mth.clamp(this.fogStartPercent, 0, 99);
        this.pixelFilter = this.pixelFilter >= 8 ? 8 : this.pixelFilter >= 4 ? 4 : this.pixelFilter >= 2 ? 2 : 0;
        this.treeDensity = Mth.clamp(this.treeDensity, 10, 200);
        this.blockHeightmapDistance = Mth.clamp(this.blockHeightmapDistance, 32, 2048);
        this.treeDistance = Mth.clamp(this.treeDistance, 32, 4096);
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(this.enabled));
        p.setProperty("distanceChunks", Integer.toString(this.distanceChunks));
        p.setProperty("startLevel", Integer.toString(this.startLevel));
        p.setProperty("sharpness", Integer.toString(this.sharpness));
        p.setProperty("maxTiles", Integer.toString(this.maxTiles));
        p.setProperty("fogMode", Integer.toString(this.fogMode));
        p.setProperty("fogStartPercent", Integer.toString(this.fogStartPercent));
        p.setProperty("waterFog", Boolean.toString(this.waterFog));
        p.setProperty("pixelFilter", Integer.toString(this.pixelFilter));
        p.setProperty("trees", Boolean.toString(this.trees));
        p.setProperty("treeDensity", Integer.toString(this.treeDensity));
        p.setProperty("blockHeightmap", Boolean.toString(this.blockHeightmap));
        p.setProperty("blockHeightmapDistance", Integer.toString(this.blockHeightmapDistance));
        p.setProperty("treeDistance", Integer.toString(this.treeDistance));
        try (Writer out = Files.newBufferedWriter(path())) {
            p.store(out, "Beyond the Terrain client settings");
        } catch (IOException e) {
            LOGGER.warn("Could not save {}", path(), e);
        }
    }
}
