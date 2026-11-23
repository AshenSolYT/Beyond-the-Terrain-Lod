package com.anonymous.beyondthehorizon.tile;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Rough surface of one tile: the height of the top non-air block, what kind of surface it is and the biome there.
 * All arrays are indexed by {@code x + z * TILE_SAMPLES}.
 */
public final class TileData {
    public static final int FLAG_WATER = 1;
    public static final int FLAG_LAVA = 2;
    /** Nothing at all below this sample (the void between the End islands); its height is the one of the land next to it. */
    public static final int FLAG_VOID = 4;
    /** One of vanilla's icebergs on a frozen ocean: land of packed ice standing out of the water. */
    public static final int FLAG_ICEBERG = 8;
    /** With {@link #FLAG_ICEBERG}: snow on top of the iceberg. */
    public static final int FLAG_ICEBERG_SNOW = 16;
    /** With {@link #FLAG_ICEBERG}: an iceberg of blue ice. */
    public static final int FLAG_BLUE_ICE = 32;

    public static final int MAX_PALETTE = 256;
    private static final int MAX_ID_LENGTH = 256;
    public static final int MAX_ENCODED_SIZE = 64 * 1024;
    /**
     * First byte of the compressed form (0.8.18.9). The old plain form starts with the palette size as a short, at most 256, so its
     * first byte is 0 or 1 and can never be this: old caches and old data still decode.
     */
    private static final int PACKED_MARKER = 0xFF;
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(4, true));
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<byte[]> RAW = ThreadLocal.withInitial(() -> new byte[MAX_ENCODED_SIZE]);
    /** Sub-samples per sample on filtered (coarse) tiles. */
    public static final int SUB_SAMPLES = 4;

    public final short[] heights;
    public final byte[] biomes;
    public final byte[] flags;
    public final List<Identifier> palette;
    /**
     * Present on coarse tiles only: the biome of each of the {@link #SUB_SAMPLES} sub-samples of every sample, so the client can
     * average their colors instead of showing one random point per cell.
     */
    public final byte @Nullable [] subBiomes;
    /** Bit i is set when sub-sample i is under water. Present together with {@link #subBiomes}. */
    public final short @Nullable [] subWater;
    /**
     * How deep the water is at each sample, in blocks from the sea level down to the ground, at most 255; 0 for land and where it is
     * not known. Coarse samples have the average of their water sub-samples. Null when the generator does not know it (the End).
     */
    public final byte @Nullable [] waterDepths;
    /**
     * The block the world generator's surface rules put on top of each sample (for water samples the block of the sea floor), as one
     * byte per sample: 0 when unknown (icebergs, lava, void, generators without surface rules), otherwise 1 plus the index into
     * {@link #blocks}. Null when the generator did not work the blocks out; the client then guesses from the biome.
     */
    public final byte @Nullable [] surface;
    /** The blocks {@link #surface} refers to. Empty exactly when {@link #surface} is null. */
    public final List<Identifier> blocks;
    /** Most surface blocks one tile can name, the index byte is 1 based. */
    public static final int MAX_BLOCKS = 255;

    public TileData(short[] heights, byte[] biomes, byte[] flags, List<Identifier> palette) {
        this(heights, biomes, flags, palette, null, null);
    }

    public TileData(short[] heights, byte[] biomes, byte[] flags, List<Identifier> palette, byte @Nullable [] subBiomes, short @Nullable [] subWater) {
        this(heights, biomes, flags, palette, subBiomes, subWater, null);
    }

    public TileData(
        short[] heights, byte[] biomes, byte[] flags, List<Identifier> palette, byte @Nullable [] subBiomes, short @Nullable [] subWater,
        byte @Nullable [] waterDepths
    ) {
        this(heights, biomes, flags, palette, subBiomes, subWater, waterDepths, List.of(), null);
    }

    public TileData(
        short[] heights, byte[] biomes, byte[] flags, List<Identifier> palette, byte @Nullable [] subBiomes, short @Nullable [] subWater,
        byte @Nullable [] waterDepths, List<Identifier> blocks, byte @Nullable [] surface
    ) {
        if (surface == null ? !blocks.isEmpty() : surface.length != BeyondTheHorizonConstants.TILE_SAMPLE_COUNT || blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("Bad surface block arrays");
        }
        if (surface != null) {
            for (byte index : surface) {
                if ((index & 0xFF) > blocks.size()) {
                    throw new IllegalArgumentException("Surface block index out of palette");
                }
            }
        }
        this.surface = surface;
        this.blocks = List.copyOf(blocks);
        if (waterDepths != null && waterDepths.length != BeyondTheHorizonConstants.TILE_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Bad water depth array");
        }
        this.waterDepths = waterDepths;
        if (heights.length != BeyondTheHorizonConstants.TILE_SAMPLE_COUNT
            || biomes.length != BeyondTheHorizonConstants.TILE_SAMPLE_COUNT
            || flags.length != BeyondTheHorizonConstants.TILE_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Bad tile array size");
        }
        if ((subBiomes == null) != (subWater == null)
            || subBiomes != null && (subBiomes.length != BeyondTheHorizonConstants.TILE_SAMPLE_COUNT * SUB_SAMPLES || subWater.length != BeyondTheHorizonConstants.TILE_SAMPLE_COUNT)) {
            throw new IllegalArgumentException("Bad sub-sample arrays");
        }
        if (subBiomes != null) {
            for (byte biome : subBiomes) {
                if ((biome & 0xFF) >= palette.size()) {
                    throw new IllegalArgumentException("Biome index out of palette");
                }
            }
        }
        this.subBiomes = subBiomes;
        this.subWater = subWater;
        if (palette.isEmpty() || palette.size() > MAX_PALETTE) {
            throw new IllegalArgumentException("Bad palette size " + palette.size());
        }
        for (byte biome : biomes) {
            if ((biome & 0xFF) >= palette.size()) {
                throw new IllegalArgumentException("Biome index out of palette");
            }
        }
        this.heights = heights;
        this.biomes = biomes;
        this.flags = flags;
        this.palette = List.copyOf(palette);
    }

    /**
     * The compressed form: marker, the length of the plain form (short), then the plain form deflated, with the heights stored as
     * differences from the sample before (neighbouring heights are close, so the differences are small and deflate well).
     */
    public byte[] encode() {
        byte[] raw = this.encodePlain(true);
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(raw);
        deflater.finish();
        byte[] out = new byte[raw.length + 16];
        out[0] = (byte) PACKED_MARKER;
        out[1] = (byte) (raw.length >> 8);
        out[2] = (byte) raw.length;
        int n = 3;
        while (!deflater.finished()) {
            if (n == out.length) {
                out = java.util.Arrays.copyOf(out, out.length * 2);
            }
            n += deflater.deflate(out, n, out.length - n);
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private byte[] encodePlain(boolean deltaHeights) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(BeyondTheHorizonConstants.TILE_SAMPLE_COUNT * 4 + 512);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(this.palette.size());
            for (Identifier id : this.palette) {
                byte[] text = id.toString().getBytes(StandardCharsets.UTF_8);
                out.writeShort(text.length);
                out.write(text);
            }
            short previous = 0;
            for (short height : this.heights) {
                out.writeShort(deltaHeights ? (short) (height - previous) : height);
                previous = height;
            }
            out.write(this.biomes);
            out.write(this.flags);
            out.writeBoolean(this.subBiomes != null);
            if (this.subBiomes != null) {
                out.write(this.subBiomes);
                for (short mask : this.subWater) {
                    out.writeShort(mask);
                }
            }
            out.writeBoolean(this.waterDepths != null);
            if (this.waterDepths != null) {
                out.write(this.waterDepths);
            }
            out.writeBoolean(this.surface != null);
            if (this.surface != null) {
                out.writeByte(this.blocks.size());
                for (Identifier id : this.blocks) {
                    byte[] text = id.toString().getBytes(StandardCharsets.UTF_8);
                    out.writeShort(text.length);
                    out.write(text);
                }
                out.write(this.surface);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Strict decoder for data that came from the network or from disk. Anything malformed throws and is dropped by the caller.
     */
    public static TileData decode(byte[] data) throws IOException {
        if (data.length > MAX_ENCODED_SIZE) {
            throw new IOException("Tile data too large");
        }
        if (data.length >= 3 && (data[0] & 0xFF) == PACKED_MARKER) {
            int rawLength = (data[1] & 0xFF) << 8 | data[2] & 0xFF;
            byte[] raw = RAW.get();
            Inflater inflater = INFLATER.get();
            try {
                inflater.reset();
                inflater.setInput(data, 3, data.length - 3);
                int n = 0;
                while (n < rawLength && !inflater.finished()) {
                    int got = inflater.inflate(raw, n, rawLength - n);
                    if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        break;
                    }
                    n += got;
                }
                if (n != rawLength || !inflater.finished() && inflater.inflate(new byte[1]) != 0) {
                    throw new IOException("Bad packed tile length");
                }
            } catch (DataFormatException e) {
                throw new IOException("Bad packed tile data");
            }
            return parse(new ByteArrayInputStream(raw, 0, rawLength), true);
        }
        return parse(new ByteArrayInputStream(data), false);
    }

    private static TileData parse(ByteArrayInputStream source, boolean deltaHeights) throws IOException {
        DataInputStream in = new DataInputStream(source);
        int paletteSize = in.readUnsignedShort();
        if (paletteSize == 0 || paletteSize > MAX_PALETTE) {
            throw new IOException("Bad palette size " + paletteSize);
        }
        List<Identifier> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            int length = in.readUnsignedShort();
            if (length == 0 || length > MAX_ID_LENGTH) {
                throw new IOException("Bad identifier length");
            }
            byte[] text = new byte[length];
            in.readFully(text);
            Identifier id = Identifier.tryParse(new String(text, StandardCharsets.UTF_8));
            if (id == null) {
                throw new IOException("Bad identifier");
            }
            palette.add(id);
        }
        short[] heights = new short[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
        short previous = 0;
        for (int i = 0; i < heights.length; i++) {
            short value = in.readShort();
            heights[i] = deltaHeights ? (short) (value + previous) : value;
            previous = heights[i];
        }
        byte[] biomes = new byte[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
        in.readFully(biomes);
        byte[] flags = new byte[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
        in.readFully(flags);
        byte[] subBiomes = null;
        short[] subWater = null;
        if (in.readBoolean()) {
            subBiomes = new byte[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT * SUB_SAMPLES];
            in.readFully(subBiomes);
            subWater = new short[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
            for (int i = 0; i < subWater.length; i++) {
                subWater[i] = in.readShort();
            }
        }
        byte[] waterDepths = null;
        if (in.readBoolean()) {
            waterDepths = new byte[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
            in.readFully(waterDepths);
        }
        List<Identifier> blocks = List.of();
        byte[] surface = null;
        if (in.readBoolean()) {
            int blockCount = in.readUnsignedByte();
            blocks = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                int length = in.readUnsignedShort();
                if (length == 0 || length > MAX_ID_LENGTH) {
                    throw new IOException("Bad identifier length");
                }
                byte[] text = new byte[length];
                in.readFully(text);
                Identifier id = Identifier.tryParse(new String(text, StandardCharsets.UTF_8));
                if (id == null) {
                    throw new IOException("Bad identifier");
                }
                blocks.add(id);
            }
            surface = new byte[BeyondTheHorizonConstants.TILE_SAMPLE_COUNT];
            in.readFully(surface);
        }
        if (in.available() != 0) {
            throw new IOException("Trailing tile data");
        }
        try {
            return new TileData(heights, biomes, flags, palette, subBiomes, subWater, waterDepths, blocks, surface);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
    }
}
