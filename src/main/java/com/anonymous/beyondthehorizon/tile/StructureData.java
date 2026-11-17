package com.anonymous.beyondthehorizon.tile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * The distant structure layer: structures above ground (villages, outposts, temples, ruins) as plain boxes, one box per piece of the
 * structure, taken from the structure starts the world generator works out without generating any chunk. It is a layer of its own, apart
 * from the distant terrain tiles: its own request, its own answer, its own regions of {@link #REGION} blocks, nothing of it is in a
 * terrain tile. A region holds the boxes of the structures that start in it; the boxes may reach out of it.
 */
public final class StructureData {
    /** Side of a region, in blocks. */
    public static final int REGION = 256;
    /** Most regions a request or an answer carries. */
    public static final int MAX_REGIONS = 64;
    /** Most boxes in one answer; the rest is left out. */
    public static final int MAX_BOXES = 20000;
    public static final int MAX_COMPRESSED_SIZE = 512 * 1024;
    private static final int MAX_RAW_SIZE = 1024 * 1024;
    private static final int MAX_PALETTE = 256;
    private static final int MAX_ID_LENGTH = 256;

    /** One piece of a structure: its corner, its size in blocks, and its structure as an index into the palette. */
    public record Box(int type, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    }

    /** The boxes of the structures that start in the region {@code x} {@code z}, which may be none. */
    public record Region(int x, int z, List<Box> boxes) {
    }

    public final List<Identifier> palette;
    public final List<Region> regions;

    public StructureData(List<Identifier> palette, List<Region> regions) {
        this.palette = palette;
        this.regions = regions;
    }

    public byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(Zip.compress(bytes))) {
            out.writeShort(this.palette.size());
            for (Identifier id : this.palette) {
                byte[] text = id.toString().getBytes(StandardCharsets.UTF_8);
                out.writeShort(text.length);
                out.write(text);
            }
            out.writeShort(this.regions.size());
            int left = MAX_BOXES;
            for (Region region : this.regions) {
                int count = Math.min(region.boxes().size(), left);
                left -= count;
                out.writeInt(region.x());
                out.writeInt(region.z());
                out.writeShort(count);
                for (int i = 0; i < count; i++) {
                    Box box = region.boxes().get(i);
                    out.writeShort(box.type());
                    out.writeInt(box.minX());
                    out.writeShort(box.minY());
                    out.writeInt(box.minZ());
                    out.writeShort(box.sizeX());
                    out.writeShort(box.sizeY());
                    out.writeShort(box.sizeZ());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Strict decoder for data from the network. Both the compressed and the unpacked size are limited. */
    public static StructureData decode(byte[] compressed) throws IOException {
        if (compressed.length > MAX_COMPRESSED_SIZE) {
            throw new IOException("Structure data too large");
        }
        byte[] raw;
        try (InputStream in = Zip.decompress(new ByteArrayInputStream(compressed))) {
            raw = in.readNBytes(MAX_RAW_SIZE + 1);
        }
        if (raw.length > MAX_RAW_SIZE) {
            throw new IOException("Structure data unpacks too large");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int paletteSize = in.readUnsignedShort();
        if (paletteSize > MAX_PALETTE) {
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
        int regionCount = in.readUnsignedShort();
        if (regionCount > MAX_REGIONS) {
            throw new IOException("Too many regions " + regionCount);
        }
        List<Region> regions = new ArrayList<>(regionCount);
        int total = 0;
        for (int r = 0; r < regionCount; r++) {
            int x = in.readInt();
            int z = in.readInt();
            int count = in.readUnsignedShort();
            total += count;
            if (total > MAX_BOXES) {
                throw new IOException("Too many boxes");
            }
            List<Box> boxes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int type = in.readUnsignedShort();
                if (type >= paletteSize) {
                    throw new IOException("Bad structure index " + type);
                }
                int minX = in.readInt();
                int minY = in.readShort();
                int minZ = in.readInt();
                int sizeX = in.readUnsignedShort();
                int sizeY = in.readUnsignedShort();
                int sizeZ = in.readUnsignedShort();
                boxes.add(new Box(type, minX, minY, minZ, sizeX, sizeY, sizeZ));
            }
            regions.add(new Region(x, z, boxes));
        }
        return new StructureData(palette, regions);
    }
}
