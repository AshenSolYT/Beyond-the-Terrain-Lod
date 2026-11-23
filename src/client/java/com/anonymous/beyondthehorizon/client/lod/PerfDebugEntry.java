package com.anonymous.beyondthehorizon.client.lod;

import com.anonymous.beyondthehorizon.BeyondTheHorizonConstants;
import com.anonymous.beyondthehorizon.BeyondTheHorizonDebug;
import com.anonymous.beyondthehorizon.client.BeyondTheHorizonClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/** What the mod costs, on the F3 screen: see {@link PerfStats}. Set its entry (beyondthehorizon:performance) to Always in the debug options. */
public final class PerfDebugEntry implements DebugScreenEntry {
    private static final Identifier GROUP = Identifier.fromNamespaceAndPath(BeyondTheHorizonConstants.MOD_ID, "performance_group");

    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        LodManager manager = BeyondTheHorizonClient.manager();
        if (manager == null) {
            return;
        }
        PerfStats.snapshot();
        List<String> lines = new ArrayList<>();
        lines.add("[Beyond the Terrain] cost, CPU only (the card's time is not here)");
        lines.add(String.format(Locale.ROOT, "Per frame: terrain pass %.2f ms, depth pass %.2f ms, uploads %.2f ms",
            PerfStats.msPerCall(PerfStats.Timer.TERRAIN), PerfStats.msPerCall(PerfStats.Timer.DEPTH), PerfStats.msPerSecond(PerfStats.Timer.UPLOAD) / Math.max(1.0, PerfStats.callsPerSecond(PerfStats.Timer.TERRAIN))));
        lines.add(String.format(Locale.ROOT, "Before the passes %.2f ms per frame (what is seen, transforms) | planner tick %.2f ms per tick",
            PerfStats.msPerCall(PerfStats.Timer.PREP), PerfStats.msPerCall(PerfStats.Timer.TICK)));
        lines.add(String.format(Locale.ROOT, "Drawn: %d ground draws, %d tree meshes (%s vertices)",
            PerfStats.groundDraws, PerfStats.treeMeshes, count(PerfStats.treeVertices)));
        lines.add(String.format(Locale.ROOT, "Tiles: %d held, %d waiting for a mesh | meshing %.1f tiles/s, %.1f ms each, %.0f ms of worker time per second",
            manager.tileCount(), manager.pendingCount(), PerfStats.callsPerSecond(PerfStats.Timer.MESH), PerfStats.msPerCall(PerfStats.Timer.MESH), PerfStats.msPerSecond(PerfStats.Timer.MESH)));
        List<String> off = new ArrayList<>();
        if (BeyondTheHorizonDebug.NO_DEPTH) {
            off.add("depth pass");
        }
        if (BeyondTheHorizonDebug.NO_GROUND) {
            off.add("ground");
        }
        if (BeyondTheHorizonDebug.NO_WATER) {
            off.add("water");
        }
        if (BeyondTheHorizonDebug.NO_PIXEL) {
            off.add("pixel filter shader");
        }
        if (!off.isEmpty()) {
            lines.add("Switched off by -Dbth.no...: " + String.join(", ", off));
        }
        displayer.addToGroup(GROUP, lines);
    }

    private static String count(long n) {
        if (n >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2fM", n / 1_000_000.0);
        }
        if (n >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
        }
        return Long.toString(n);
    }
}
