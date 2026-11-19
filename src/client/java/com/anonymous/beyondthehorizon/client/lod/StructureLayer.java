package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.client.config.ClientConfig;
import com.anonymous.beyondthehorizon.client.render.VertexArena;
import com.anonymous.beyondthehorizon.net.BeyondTheHorizonPayloads;
import com.anonymous.beyondthehorizon.tile.StructureData;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The distant structure layer on the client: villages, outposts, temples and the like above ground, drawn as soft, very low poly shapes in the look of the distant terrain. It is
 * apart from the terrain tiles: it asks the server for regions of {@link StructureData#REGION} blocks around the camera by its own
 * request, keeps the boxes of every region it got, and turns them into small meshes of its own that the renderer draws after the
 * terrain. Remove this class, its hooks in the manager and the renderer, and the terrain does not change.
 * <p>
 * A region's boxes are grouped by cells of {@link #CELL} blocks, one mesh per group, so that the renderer can leave out the groups that
 * lie inside the distance of vanilla's own chunks, where the real buildings are.
 */
public final class StructureLayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beyond the Terrain");
    private static final int REGION = StructureData.REGION;
    private static final int CELL = 64;
    private static final int CHECK_TICKS = 10;
    /** A region asked for and not answered by now is asked for again. */
    private static final long REQUEST_TIMEOUT_MS = 30_000L;
    private static final int MAX_REGIONS_IN_FLIGHT = 192;
    private static final int MAX_PACKETS_PER_CHECK = 2;
    /** Pieces this low (paths) are not drawn. */
    private static final int FLAT_HEIGHT = 2;
    /** Blocks per cell of the grid a group is shaped on; bigger structures use bigger cells, so a group has at most MAX_GRID cells a side. */
    private static final int GRID_BLOCKS = 2;
    private static final int MAX_GRID = 60;
    /** The bottoms of the pieces lie in the ground a little; the shape starts this much above them. */
    private static final float GROUND_ABOVE_LOW = 1.0F;
    private static final int MAX_BOXES_PER_TILE = 3000;
    private static final Tile[] NONE = new Tile[0];

    private final Minecraft minecraft;
    private final ClientConfig config;
    private final VertexArena arena;
    private final Map<Long, Tile[]> regions = new HashMap<>();
    /** Regions asked for, with when. */
    private final Map<Long, Long> requested = new HashMap<>();
    private Tile[] drawList = NONE;
    private int ticks;

    public StructureLayer(Minecraft minecraft, ClientConfig config, VertexArena arena) {
        this.minecraft = minecraft;
        this.config = config;
        this.arena = arena;
    }

    /** The meshes to draw; empty while the layer is off. */
    public Tile[] tiles() {
        return this.drawList;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Called every tick while the terrain is drawn: every half second it asks for the regions around the camera it does not have. */
    public void update(double camX, double camZ) {
        if (++this.ticks % CHECK_TICKS != 0) {
            return;
        }
        int range = this.config.structureRange;
        if (range <= 0 || this.minecraft.level == null) {
            if (!this.regions.isEmpty() || !this.requested.isEmpty()) {
                this.reset();
            }
            return;
        }
        double distance = range * 256.0;
        // Regions that are left behind.
        boolean changed = false;
        List<Long> far = new ArrayList<>();
        for (Long key : this.regions.keySet()) {
            if (this.isFar(key, camX, camZ, distance + REGION * 2.0)) {
                far.add(key);
            }
        }
        for (Long key : far) {
            closeAll(this.regions.remove(key));
            changed = true;
        }
        long now = System.currentTimeMillis();
        this.requested.values().removeIf(at -> now - at > REQUEST_TIMEOUT_MS);
        if (changed) {
            this.rebuildList();
        }
        if (!ClientPlayNetworking.canSend(BeyondTheHorizonPayloads.StructureRequest.TYPE)) {
            return;
        }
        int inFlight = this.requested.size();
        if (inFlight >= MAX_REGIONS_IN_FLIGHT) {
            return;
        }
        int minX = Math.floorDiv((int) Math.floor(camX - distance), REGION);
        int maxX = Math.floorDiv((int) Math.floor(camX + distance), REGION);
        int minZ = Math.floorDiv((int) Math.floor(camZ - distance), REGION);
        int maxZ = Math.floorDiv((int) Math.floor(camZ + distance), REGION);
        List<int[]> wanted = new ArrayList<>();
        for (int rz = minZ; rz <= maxZ; rz++) {
            for (int rx = minX; rx <= maxX; rx++) {
                long key = key(rx, rz);
                if (!this.regions.containsKey(key) && !this.requested.containsKey(key)) {
                    wanted.add(new int[] {rx, rz});
                }
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        wanted.sort(Comparator.comparingDouble(r -> {
            double dx = (r[0] + 0.5) * REGION - camX;
            double dz = (r[1] + 0.5) * REGION - camZ;
            return dx * dx + dz * dz;
        }));
        int index = 0;
        int packets = 0;
        while (index < wanted.size() && inFlight < MAX_REGIONS_IN_FLIGHT && packets < MAX_PACKETS_PER_CHECK) {
            int n = Math.min(StructureData.MAX_REGIONS, Math.min(wanted.size() - index, MAX_REGIONS_IN_FLIGHT - inFlight));
            int[] ask = new int[n * 2];
            for (int i = 0; i < n; i++) {
                int[] region = wanted.get(index++);
                ask[i * 2] = region[0];
                ask[i * 2 + 1] = region[1];
                this.requested.put(key(region[0], region[1]), now);
            }
            inFlight += n;
            packets++;
            ClientPlayNetworking.send(new BeyondTheHorizonPayloads.StructureRequest(ask));
        }
    }

    private boolean isFar(long key, double camX, double camZ, double distance) {
        int rx = (int) (key >> 32);
        int rz = (int) key;
        return Math.abs((rx + 0.5) * REGION - camX) > distance || Math.abs((rz + 0.5) * REGION - camZ) > distance;
    }

    /** An answer of the server, on the client thread. */
    public void onData(BeyondTheHorizonPayloads.StructureDataPayload payload) {
        ClientLevel level = this.minecraft.level;
        if (level == null || !payload.dimension().equals(level.dimension().identifier())) {
            return;
        }
        StructureData data;
        try {
            data = StructureData.decode(payload.data());
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Bad structure data from the server", e);
            return;
        }
        int[] colors = new int[data.palette.size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = colorOf(data.palette.get(i));
        }
        for (StructureData.Region region : data.regions) {
            long key = key(region.x(), region.z());
            this.requested.remove(key);
            closeAll(this.regions.put(key, this.build(region, colors)));
        }
        this.rebuildList();
    }

    /** Forgets everything, when the world or the dimension changes or the layer is turned off. */
    public void reset() {
        for (Tile[] tiles : this.regions.values()) {
            closeAll(tiles);
        }
        this.regions.clear();
        this.requested.clear();
        this.drawList = NONE;
    }

    private static void closeAll(Tile @Nullable [] tiles) {
        if (tiles != null) {
            for (Tile tile : tiles) {
                tile.close();
            }
        }
    }

    private void rebuildList() {
        int count = 0;
        for (Tile[] tiles : this.regions.values()) {
            count += tiles.length;
        }
        Tile[] list = new Tile[count];
        int i = 0;
        for (Tile[] tiles : this.regions.values()) {
            for (Tile tile : tiles) {
                list[i++] = tile;
            }
        }
        this.drawList = list;
    }

    private Tile[] build(StructureData.Region region, int[] colors) {
        if (region.boxes().isEmpty()) {
            return NONE;
        }
        Map<Long, List<StructureData.Box>> cells = new HashMap<>();
        for (StructureData.Box box : region.boxes()) {
            if (box.type() >= colors.length) {
                continue;
            }
            int cx = Math.floorDiv(box.minX() + box.sizeX() / 2, CELL);
            int cz = Math.floorDiv(box.minZ() + box.sizeZ() / 2, CELL);
            cells.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(box);
        }
        List<Tile> tiles = new ArrayList<>(cells.size());
        for (Map.Entry<Long, List<StructureData.Box>> cell : cells.entrySet()) {
            Tile tile = this.mesh(cell.getKey(), cell.getValue(), colors);
            if (tile != null) {
                tiles.add(tile);
            }
        }
        return tiles.toArray(new Tile[0]);
    }

    /**
     * One mesh for a group of boxes, in the look of the distant terrain: very low poly and rounded, not boxes. The boxes are put on a
     * coarse grid of top heights; every corner of the grid takes the average of the cells around it, so a building becomes a soft
     * little hill that slopes down to the ground on all sides, and the corners are colored and shaded by their slope like the terrain.
     * Flat pieces (the paths of a village) are left out: at this size they would only be blobs.
     */
    private @Nullable Tile mesh(long key, List<StructureData.Box> boxes, int[] colors) {
        int count = Math.min(boxes.size(), MAX_BOXES_PER_TILE);
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int used = 0;
        for (int i = 0; i < count; i++) {
            StructureData.Box box = boxes.get(i);
            if (box.sizeY() <= FLAT_HEIGHT) {
                continue;
            }
            used++;
            minX = Math.min(minX, box.minX());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.minX() + box.sizeX());
            maxZ = Math.max(maxZ, box.minZ() + box.sizeZ());
        }
        if (used == 0) {
            return null;
        }
        int span = Math.max(maxX - minX, maxZ - minZ);
        int cs = GRID_BLOCKS;
        while (span / cs > MAX_GRID) {
            cs *= 2;
        }
        int originX = Math.floorDiv(minX, cs) * cs;
        int originZ = Math.floorDiv(minZ, cs) * cs;
        int nx = Math.max(1, Math.ceilDiv(maxX - originX, cs));
        int nz = Math.max(1, Math.ceilDiv(maxZ - originZ, cs));
        // The cells: the highest top and the lowest bottom of the boxes over them, and the color of the highest.
        float[] top = new float[nx * nz];
        float[] low = new float[nx * nz];
        int[] cellColor = new int[nx * nz];
        Arrays.fill(top, Float.NEGATIVE_INFINITY);
        Arrays.fill(low, Float.POSITIVE_INFINITY);
        int anyColor = 0xBDB8AE;
        float globalLow = Float.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            StructureData.Box box = boxes.get(i);
            if (box.sizeY() <= FLAT_HEIGHT) {
                continue;
            }
            // A cell belongs to a box when its center lies in it; a box smaller than a cell still gets the one cell it is in.
            // The bounding box of a piece is a little roomier than the building, so the footprint is taken a block in on every side.
            int insetX = box.sizeX() >= 6 ? 1 : 0;
            int insetZ = box.sizeZ() >= 6 ? 1 : 0;
            int fromX = box.minX() + insetX;
            int toX = box.minX() + box.sizeX() - insetX;
            int fromZ = box.minZ() + insetZ;
            int toZ = box.minZ() + box.sizeZ() - insetZ;
            int x0 = (int) Math.ceil((fromX - originX) / (double) cs - 0.5);
            int x1 = (int) Math.ceil((toX - originX) / (double) cs - 0.5);
            if (x1 <= x0) {
                x0 = Math.floorDiv((fromX + toX) / 2 - originX, cs);
                x1 = x0 + 1;
            }
            int z0 = (int) Math.ceil((fromZ - originZ) / (double) cs - 0.5);
            int z1 = (int) Math.ceil((toZ - originZ) / (double) cs - 0.5);
            if (z1 <= z0) {
                z0 = Math.floorDiv((fromZ + toZ) / 2 - originZ, cs);
                z1 = z0 + 1;
            }
            x0 = Math.max(0, x0);
            z0 = Math.max(0, z0);
            x1 = Math.min(nx, x1);
            z1 = Math.min(nz, z1);
            float boxTop = box.minY() + box.sizeY();
            for (int cz = z0; cz < z1; cz++) {
                for (int cx = x0; cx < x1; cx++) {
                    int idx = cx + cz * nx;
                    if (boxTop > top[idx]) {
                        top[idx] = boxTop;
                        cellColor[idx] = colors[box.type()];
                        anyColor = cellColor[idx];
                    }
                    low[idx] = Math.min(low[idx], box.minY());
                    globalLow = Math.min(globalLow, box.minY());
                }
            }
        }
        // The corners: the cells around a corner pull it up to their top, the empty ones leave it at the ground.
        int cw = nx + 1;
        int ch = nz + 1;
        float[] ground = new float[cw * ch];
        float[] height = new float[cw * ch];
        int[] color = new int[cw * ch];
        int[] filled = new int[cw * ch];
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                float sumTop = 0.0F;
                float sumGround = 0.0F;
                float bestTop = Float.NEGATIVE_INFINITY;
                int c = 0;
                int col = anyColor;
                for (int dz = -1; dz <= 0; dz++) {
                    for (int dx = -1; dx <= 0; dx++) {
                        int cx = i + dx;
                        int cz = j + dz;
                        if (cx < 0 || cz < 0 || cx >= nx || cz >= nz) {
                            continue;
                        }
                        int idx = cx + cz * nx;
                        if (top[idx] == Float.NEGATIVE_INFINITY) {
                            continue;
                        }
                        c++;
                        sumTop += top[idx];
                        sumGround += low[idx] + GROUND_ABOVE_LOW;
                        if (top[idx] > bestTop) {
                            bestTop = top[idx];
                            col = cellColor[idx];
                        }
                    }
                }
                int ci = i + j * cw;
                filled[ci] = c;
                color[ci] = col;
                if (c > 0) {
                    ground[ci] = sumGround / c;
                    height[ci] = (sumTop + (4 - c) * ground[ci]) / 4.0F;
                }
            }
        }
        // Corners with no building around them lie on the ground of the corners next to them.
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                int ci = i + j * cw;
                if (filled[ci] > 0) {
                    continue;
                }
                float sum = 0.0F;
                int n = 0;
                int nearColor = anyColor;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        int ni = i + di;
                        int nj = j + dj;
                        if (ni < 0 || nj < 0 || ni >= cw || nj >= ch || filled[ni + nj * cw] == 0) {
                            continue;
                        }
                        sum += ground[ni + nj * cw];
                        nearColor = color[ni + nj * cw];
                        n++;
                    }
                }
                ground[ci] = n > 0 ? sum / n : globalLow + GROUND_ABOVE_LOW;
                height[ci] = ground[ci];
                color[ci] = nearColor;
            }
        }
        // One soft pass over the heights rounds the shapes; what is on the ground stays there.
        float[] smooth = height.clone();
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                int ci = i + j * cw;
                if (filled[ci] == 0) {
                    continue;
                }
                float around = height[Math.max(0, i - 1) + j * cw] + height[Math.min(cw - 1, i + 1) + j * cw]
                    + height[i + Math.max(0, j - 1) * cw] + height[i + Math.min(ch - 1, j + 1) * cw];
                smooth[ci] = height[ci] * 0.5F + around * 0.125F;
            }
        }
        // The cells to draw: those with a building corner, which is the ones with a building and a ring of one cell around.
        int cells = 0;
        boolean[] draw = new boolean[nx * nz];
        for (int cz = 0; cz < nz; cz++) {
            for (int cx = 0; cx < nx; cx++) {
                int a = cx + cz * cw;
                if (filled[a] > 0 || filled[a + 1] > 0 || filled[a + cw] > 0 || filled[a + cw + 1] > 0) {
                    draw[cx + cz * nx] = true;
                    cells++;
                }
            }
        }
        if (cells == 0) {
            return null;
        }
        int vertexBytes = cw * ch * MeshBuilder.VERTEX_SIZE;
        int indexBytes = cells * 6 * 2;
        ByteBuffer data = ByteBuffer.allocateDirect(vertexBytes + indexBytes).order(ByteOrder.nativeOrder());
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                int ci = i + j * cw;
                int left = Math.max(0, i - 1);
                int right = Math.min(cw - 1, i + 1);
                int back = Math.max(0, j - 1);
                int front = Math.min(ch - 1, j + 1);
                float dhdx = (smooth[right + j * cw] - smooth[left + j * cw]) / ((right - left) * (float) cs);
                float dhdz = (smooth[i + front * cw] - smooth[i + back * cw]) / ((front - back) * (float) cs);
                float length = (float) Math.sqrt(dhdx * dhdx + 1.0F + dhdz * dhdz);
                // The light comes from above and a little from the north-west.
                float light = (-dhdx * -0.3F + 1.0F * 0.9F + -dhdz * -0.3F) / length;
                float factor = Math.max(0.55F, Math.min(1.1F, 0.62F + 0.5F * Math.max(0.0F, light)));
                vertex(data, i * cs, smooth[ci], j * cs, shade(color[ci], factor));
                minY = Math.min(minY, smooth[ci]);
                maxY = Math.max(maxY, smooth[ci]);
            }
        }
        for (int cz = 0; cz < nz; cz++) {
            for (int cx = 0; cx < nx; cx++) {
                if (!draw[cx + cz * nx]) {
                    continue;
                }
                int a = cx + cz * cw;
                int b = a + 1;
                int c = a + cw;
                int d = a + cw + 1;
                if (((cx + cz) & 1) == 0) {
                    data.putShort((short) a).putShort((short) c).putShort((short) b);
                    data.putShort((short) b).putShort((short) c).putShort((short) d);
                } else {
                    data.putShort((short) a).putShort((short) c).putShort((short) d);
                    data.putShort((short) a).putShort((short) d).putShort((short) b);
                }
            }
        }
        data.flip();
        int size = vertexBytes + indexBytes;
        VertexArena.Part part = this.arena.allocate(size);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(part.slice(0, size), data);
        Tile tile = new Tile(key, originX, originZ, Math.max(nx, nz) * cs);
        tile.part = part;
        tile.structure = true;
        // The geometry sits in the slot the trees use, which the renderer draws with the same flat, two-sided pipeline.
        tile.treeVertexOffset = 0;
        tile.treeVertexCount = cw * ch;
        tile.treeIndexOffset = vertexBytes;
        tile.treeIndexCount = cells * 6;
        tile.minY = minY;
        tile.maxY = maxY;
        return tile;
    }

    private static void vertex(ByteBuffer out, float x, float y, float z, int rgb) {
        out.putFloat(x).putFloat(y).putFloat(z);
        out.put((byte) (rgb >>> 16)).put((byte) (rgb >>> 8)).put((byte) rgb).put((byte) 0xFF);
    }

    private static int shade(int rgb, float factor) {
        int r = Math.min(255, Math.round((rgb >>> 16 & 255) * factor));
        int g = Math.min(255, Math.round((rgb >>> 8 & 255) * factor));
        int b = Math.min(255, Math.round((rgb & 255) * factor));
        return r << 16 | g << 8 | b;
    }

    /** A rough color for a structure, by its name; what is not known is gray. */
    private static int colorOf(Identifier id) {
        String path = id.getPath();
        if (path.startsWith("village")) {
            if (path.contains("desert")) {
                return 0xD6C58F;
            }
            if (path.contains("savanna")) {
                return 0xB5683A;
            }
            if (path.contains("snowy")) {
                return 0xE4EDEF;
            }
            if (path.contains("taiga")) {
                return 0x5E4A34;
            }
            return 0x9B7B4B;
        }
        if (path.contains("pillager_outpost")) {
            return 0x4B4036;
        }
        if (path.contains("mansion")) {
            return 0x3E3029;
        }
        if (path.contains("desert_pyramid")) {
            return 0xCDBB84;
        }
        if (path.contains("jungle_pyramid") || path.contains("jungle_temple")) {
            return 0x6E7A5C;
        }
        if (path.contains("igloo")) {
            return 0xEEF4F6;
        }
        if (path.contains("swamp_hut")) {
            return 0x4F5A33;
        }
        if (path.contains("shipwreck")) {
            return 0x6B5238;
        }
        if (path.contains("ocean_ruin")) {
            return 0x7A7A72;
        }
        if (path.contains("monument")) {
            return 0x6AA39A;
        }
        if (path.contains("ruined_portal")) {
            return 0x5A3B66;
        }
        if (path.contains("fortress")) {
            return 0x3A1E22;
        }
        if (path.contains("bastion")) {
            return 0x3B3B42;
        }
        if (path.contains("end_city")) {
            return 0xC5B7D0;
        }
        // A structure of another mod: a light stone color, as most buildings are lighter than they look through the fog.
        return 0xBDB8AE;
    }
}
