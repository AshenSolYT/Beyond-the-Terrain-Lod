package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.tile.TilePos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The tiles the server has sent, kept on disk for one world and dimension, so that coming back to a world shows its terrain at once
 * instead of asking the server for every tile again. Plain files, one per tile, holding the bytes as they were sent. No game classes.
 */
public final class TileCache {
    private final Path directory;

    public TileCache(Path directory) {
        this.directory = directory;
    }

    private Path file(long key) {
        TilePos pos = TilePos.unpack(key);
        return this.directory.resolve(pos.level() + "_" + pos.x() + "_" + pos.z() + ".tile");
    }

    public boolean has(long key) {
        return Files.isRegularFile(this.file(key));
    }

    public byte @Nullable [] read(long key) {
        try {
            return Files.readAllBytes(this.file(key));
        } catch (IOException e) {
            return null;
        }
    }

    /** Written to a temporary file first, so a tile cut short by closing the game is never read as a whole one. */
    public void write(long key, byte[] data) {
        Path target = this.file(key);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(this.directory);
            Files.write(temp, data);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nothing more to do: the tile is simply not cached.
            }
        }
    }

    public static void deleteTree(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A file in use stays; it is replaced when the tile is loaded again.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }
}
