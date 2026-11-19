package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.client.render.VertexArena;
import com.anonymous.beyondthehorizon.tile.TilePos;
import org.jspecify.annotations.Nullable;

/** One tile on the client: its place and, once uploaded, where its triangles are on the GPU. Empty tiles have nothing to draw. */
public final class Tile {
    public final long key;
    public final TilePos pos;
    public final double minX;
    public final double minZ;
    public final double size;
    /** Null for a tile with nothing to draw. */
    public VertexArena.@Nullable Part part;
    public int groundIndexCount;
    /** Where the tile's own ground triangles start in the part, in bytes; -1 if it uses the shared ones. */
    public int ownGroundIndexOffset = -1;
    public int waterVertexOffset = -1;
    public int waterIndexOffset = -1;
    public int waterIndexCount;
    public int treeVertexOffset = -1;
    public int treeVertexCount;
    public int treeIndexOffset = -1;
    public int treeIndexCount;
    /** The stepped columns that stand in for the smooth ground of this tile; none when the count is 0. */
    public int stepVertexOffset = -1;
    public int stepVertexCount;
    public int stepIndexOffset = -1;
    public int stepIndexCount;
    public float minY;
    public float maxY;
    /** The tick it was last in the picture or asked for; among the tiles outside the picture the farthest go first when there are too many. */
    public long lastUsed;
    /** A mesh of the distant structure layer, not a terrain tile: drawn by the renderer's structure pass only, see StructureLayer. */
    public boolean structure;

    public Tile(TilePos pos) {
        this.key = pos.pack();
        this.pos = pos;
        this.minX = pos.minX();
        this.minZ = pos.minZ();
        this.size = pos.size();
    }

    /** A tile of the structure layer: it has no position in the terrain's grid, only the corner and the size of its boxes. */
    public Tile(long key, double minX, double minZ, double size) {
        this.key = key;
        this.pos = null;
        this.minX = minX;
        this.minZ = minZ;
        this.size = size;
    }

    public boolean drawable() {
        return this.part != null;
    }

    public void close() {
        if (this.part != null) {
            this.part.close();
            this.part = null;
        }
    }
}
