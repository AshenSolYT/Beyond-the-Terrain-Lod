package com.anonymous.beyondthehorizon.server;

import java.util.Arrays;

/**
 * Decides how much of every column of a detailed tile is worth sending, caves included. Plain arrays and no game types, so it can be
 * tested alone.
 *
 * <p>A column is a stack of segments from the top down. Its ground is the top of the first segment that hides what is under it; air
 * pockets under the ground are cave candidates. A pocket is open when it reaches a mouth: its top lies over the ground of a
 * neighbouring column (so one can look in from the side), or it lies in the outermost cells of the grid, where the real mouth may be
 * in the next tile. Open pockets pass openness on to every pocket they touch, however far, so a cave is followed to its end and
 * never cut after a fixed number of blocks. Caves that are closed on all sides cannot be seen from outside and are left out.
 */
final class CaveSelection {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private CaveSelection() {
    }

    /**
     * @param counts segments per column of a square grid {@code side} cells wide, 0 for a missing column
     * @param offsets index of the first segment of each column in {@code heights} and {@code solid}
     * @param solid whether each segment hides what is under it
     * @param heights height of each segment in blocks
     * @param stackTops top height of each column
     * @param sideKeep blocks kept under the ground of a column even with no lower neighbour
     * @param wallMargin blocks kept under the lowest place a side, floor or wall can be seen from
     * @return how many segments of each column, from the top, are kept; the last one kept always hides what is under it
     */
    static int[] select(int[] counts, int[] offsets, boolean[] solid, short[] heights, short[] stackTops, int side, int sideKeep, int wallMargin) {
        int cells = side * side;
        int[] ground = new int[cells];
        int[] pocketBottoms = new int[64];
        int[] pocketTops = new int[64];
        int[] pocketCells = new int[64];
        int[] firstPocket = new int[cells + 1];
        int pockets = 0;
        for (int index = 0; index < cells; index++) {
            firstPocket[index] = pockets;
            ground[index] = Integer.MIN_VALUE;
            int count = counts[index];
            if (count == 0) {
                continue;
            }
            int offset = offsets[index];
            int top = stackTops[index];
            int pocketTop = Integer.MIN_VALUE;
            for (int s = 0; s < count; s++) {
                int bottom = top - heights[offset + s];
                boolean hides = solid[offset + s];
                if (ground[index] == Integer.MIN_VALUE) {
                    if (hides) {
                        ground[index] = top;
                    }
                } else if (!hides) {
                    if (pocketTop == Integer.MIN_VALUE) {
                        pocketTop = top;
                    }
                } else if (pocketTop != Integer.MIN_VALUE) {
                    if (pockets == pocketBottoms.length) {
                        int capacity = pockets * 2;
                        pocketBottoms = Arrays.copyOf(pocketBottoms, capacity);
                        pocketTops = Arrays.copyOf(pocketTops, capacity);
                        pocketCells = Arrays.copyOf(pocketCells, capacity);
                    }
                    pocketBottoms[pockets] = top;
                    pocketTops[pockets] = pocketTop;
                    pocketCells[pockets] = index;
                    pockets++;
                    pocketTop = Integer.MIN_VALUE;
                }
                top = bottom;
            }
            if (ground[index] == Integer.MIN_VALUE) {
                // Nothing in the column hides what is under it (deep water): it is all sent anyway.
                ground[index] = top;
            }
        }
        firstPocket[cells] = pockets;

        // Lowest height each column is sent down to: its own ground and the ground of its lowest neighbour.
        int[] keepTo = new int[cells];
        for (int index = 0; index < cells; index++) {
            keepTo[index] = ground[index] - sideKeep;
            for (int direction = 0; direction < 4; direction++) {
                int neighbour = neighbour(index, direction, side);
                if (neighbour >= 0 && ground[neighbour] != Integer.MIN_VALUE) {
                    keepTo[index] = Math.min(keepTo[index], ground[neighbour] - wallMargin);
                }
            }
        }

        if (pockets > 0) {
            boolean[] open = new boolean[pockets];
            int[] queue = new int[pockets];
            int head = 0;
            int tail = 0;
            for (int pocket = 0; pocket < pockets; pocket++) {
                int index = pocketCells[pocket];
                boolean mouth = index % side == 0 || index / side == 0 || index % side == side - 1 || index / side == side - 1;
                for (int direction = 0; direction < 4 && !mouth; direction++) {
                    int neighbour = neighbour(index, direction, side);
                    mouth = neighbour >= 0 && ground[neighbour] != Integer.MIN_VALUE && pocketTops[pocket] > ground[neighbour];
                }
                if (mouth) {
                    open[pocket] = true;
                    queue[tail++] = pocket;
                }
            }
            while (head < tail) {
                int pocket = queue[head++];
                int index = pocketCells[pocket];
                int bottom = pocketBottoms[pocket];
                int top = pocketTops[pocket];
                keepTo[index] = Math.min(keepTo[index], bottom - wallMargin);
                for (int direction = 0; direction < 4; direction++) {
                    int neighbour = neighbour(index, direction, side);
                    if (neighbour < 0 || ground[neighbour] == Integer.MIN_VALUE) {
                        continue;
                    }
                    // The wall of the neighbour facing the pocket can be seen.
                    keepTo[neighbour] = Math.min(keepTo[neighbour], bottom - wallMargin);
                    for (int next = firstPocket[neighbour]; next < firstPocket[neighbour + 1]; next++) {
                        if (!open[next] && pocketBottoms[next] < top && bottom < pocketTops[next]) {
                            open[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }
        }

        int[] kept = new int[cells];
        for (int index = 0; index < cells; index++) {
            int count = counts[index];
            int offset = offsets[index];
            int keep = count;
            int top = stackTops[index];
            for (int s = 0; s < count; s++) {
                top -= heights[offset + s];
                if (top <= keepTo[index] && solid[offset + s]) {
                    keep = s + 1;
                    break;
                }
            }
            kept[index] = keep;
        }
        return kept;
    }

    /** The cell next to a cell of a square grid in one of four directions, or -1 outside it. */
    private static int neighbour(int index, int direction, int side) {
        int nx = index % side + DX[direction];
        int nz = index / side + DZ[direction];
        return nx < 0 || nz < 0 || nx >= side || nz >= side ? -1 : nx + nz * side;
    }
}
