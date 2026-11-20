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
    /** The lowest point of the ground itself, without the skirt below the tile's edges: what the horizon culling treats as the tile's floor. */
    public float surfaceMinY;
    public float maxY;
    /** The height of the water surface of this tile's mesh (meaningful when it has water). */
    public float waterLevel;
    /** The tick it was last in the picture or asked for; among the tiles outside the picture the farthest go first when there are too many. */
    public long lastUsed;

    public Tile(TilePos pos) {
        this.key = pos.pack();
        this.pos = pos;
        this.minX = pos.minX();
        this.minZ = pos.minZ();
        this.size = pos.size();
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
