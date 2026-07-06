package com.bannerbound.core.api.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.HousingLimits;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Auto-detects a house's shell (walls/roof/floor) from its House Block so the player need not hand-draw
 * every box with the Home Marker Rod. Output is a set of {@link BlockSelection} HOME boxes ready to
 * register for the home, which then feed the existing {@link com.bannerbound.core.api.settlement.Homes}
 * validation untouched. Legacy: the {@link #detect} entry point is no longer wired to a payload; only
 * {@link #isWallOpening} is still consumed externally, by the enclosure flood in {@code Homes.validate}.
 *
 * <p>Detection is an air-only flood, not solid-to-solid flooding, precisely so it traces the enclosed
 * room of any shape (a non-cube falls out for free) and never tunnels into the ground: the floor (solid
 * directly under interior air) is part of the shell, but the dirt below it is not adjacent to interior
 * air, so detection stops at the ground. The resulting shell is 26-connected, matching {@code isConnected}.
 *
 * <p>Two floods handle open-hole windows and multi-room houses. First a strict {@link #floodInterior}
 * with no sealing: a fully-enclosed house of any size, rooms joined by interior doorways, fills cleanly
 * and cheaply (the common case). If it leaks (escapes the radius or exceeds {@link #MAX_INTERIOR}), the
 * house has openings, so detection switches to an outside-in classification, {@link #floodOpenHouse}:
 * flood the EXTERIOR air, sealing wall holes ({@link #isWallOpening} -- a window / 1-wide doorway with
 * >=3 solid faces) so the outside cannot sneak in, then keep everything the seed reaches that the outside
 * cannot. Because the interior flood never seals, interior doorways stay open and every room is captured;
 * a genuine >=2-wide missing wall lets the outside reach the seed -> reported "not enclosed". The
 * {@link #isWallOpening} predicate is reused by the periodic validator, so a windowed house both detects
 * and validates alike.
 *
 * <p>{@link #isStructural} is collision-box based, NOT {@code !isAir()}: plants, tall grass, crops,
 * torches and carpets are non-collidable decoration, so a flower bed beside the house is neither a wall
 * the flood stops at nor a block grabbed into the shell (the old {@code !isAir()} check treated a flower
 * as wall, trapping air that read as interior and distorting the rectangle decomposition). Doors /
 * trapdoors / fence gates are special-cased structural so the EXTERIOR flood still cannot enter through a
 * closed one, but the interior flood treats them as passable so a doorway with a door installed still
 * connects its rooms; they are then re-marked into the shell in {@link #extractShell} or installed doors
 * vanish from the detected shape.
 *
 * <p>{@link #extractShell} runs two passes: pass 1 grabs every solid 26-adjacent to interior air (plus
 * the flood-passed doors); pass 2 (open-house path only) drops doorway floor "spill" -- exterior ground
 * that only touches the interior diagonally -- via {@link #isExteriorSpill}. The spill test's key
 * discriminator is "stands on the rest of the shell": a real wall's top course stands on the wall below
 * (in the shell) so it is kept, while a lone ground flap outside a doorway stands on raw terrain so it is
 * dropped. Pass 2 must run after pass 1 for that test to see the full shell.
 *
 * <p>{@link #detect} applies no size gate on purpose: an over-size house is still marked and buildable,
 * it just validates as BROKEN_TOO_BIG until research grows the limit. The size check lives in
 * {@code HouseBlockEntity.runValidation} so hand-marking and Detect agree.
 */
public final class HouseStructureDetector {
    public static final int OPENING_MIN_SOLID_FACES = 3;
    public static final int MAX_INTERIOR = 8000;
    public static final int MAX_BOXES = 128;
    public static final int PREVIEW_TICKS = 100;
    public static final int OPEN_HOUSE_BOX_RADIUS = 24;

    private static final int[][] OFFSETS_26 = build26();

    private HouseStructureDetector() {
    }

    public record Result(List<BlockSelection> boxes, Set<BlockPos> shell, @Nullable String failKey,
                          String diag) {
        static Result fail(String key, String diag) { return new Result(List.of(), Set.of(), key, diag); }
    }

    public static Result detect(LevelReader level, BlockPos housePos, Settlement owner, Home home,
                                 UUID playerId) {
        BlockPos seed = findInteriorSeed(level, housePos);
        if (seed == null) {
            return Result.fail("no_interior", "no air cell adjacent to the House Block");
        }
        Set<BlockPos> interior = floodInterior(level, housePos, seed);
        String path;
        Set<BlockPos> exterior = null;
        if (interior != null) {
            path = "sealed";
        } else {
            OpenHouse open = floodOpenHouse(level, housePos, seed);
            if (open != null) {
                interior = open.interior();
                exterior = open.exterior();
                path = "open";
            } else {
                path = "leaked";
            }
        }
        if (interior == null) {
            return Result.fail("open_wall", "path=leaked seed=" + shortPos(seed)
                + " (exterior reached the seed → a ≥2-wide opening / open roof)");
        }
        Set<BlockPos> shell = extractShell(level, interior, housePos, exterior);
        List<BlockSelection> boxes = decompose(shell, owner, home, housePos, playerId);
        String diag = "path=" + path + " interior=" + interior.size()
            + (exterior != null ? " exterior=" + exterior.size() : "")
            + " shell=" + shell.size() + " boxes=" + boxes.size() + " seed=" + shortPos(seed);
        return new Result(boxes, shell, null, diag);
    }

    private static String shortPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    @Nullable
    private static BlockPos findInteriorSeed(LevelReader level, BlockPos housePos) {
        Direction[] order = { Direction.UP, Direction.NORTH, Direction.SOUTH,
                              Direction.EAST, Direction.WEST, Direction.DOWN };
        for (Direction d : order) {
            BlockPos n = housePos.relative(d);
            if (level.getBlockState(n).isAir()) return n;
        }
        return null;
    }

    @Nullable
    private static Set<BlockPos> floodInterior(LevelReader level, BlockPos housePos, BlockPos seed) {
        Set<BlockPos> interior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        interior.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (interior.contains(n)) continue;
                if (!isFloodPassable(level, n)) continue;
                if (chebyshev(n, housePos) > HousingLimits.MAX_RADIUS) {
                    return null;
                }
                if (interior.size() >= MAX_INTERIOR) {
                    return null;
                }
                interior.add(n);
                queue.add(n);
            }
        }
        return interior;
    }

    private record OpenHouse(Set<BlockPos> interior, Set<BlockPos> exterior) {}

    @Nullable
    private static OpenHouse floodOpenHouse(LevelReader level, BlockPos housePos, BlockPos seed) {
        int r = OPEN_HOUSE_BOX_RADIUS;
        int minX = housePos.getX() - r, maxX = housePos.getX() + r;
        int minZ = housePos.getZ() - r, maxZ = housePos.getZ() + r;
        int minY = Math.max(level.getMinBuildHeight(), housePos.getY() - r);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, housePos.getY() + r);

        Set<BlockPos> exterior = floodExterior(level, minX, minY, minZ, maxX, maxY, maxZ);
        if (exterior.contains(seed)) {
            return null;
        }
        Set<BlockPos> interior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        interior.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (interior.contains(n)) continue;
                if (!inBox(n, minX, minY, minZ, maxX, maxY, maxZ)) continue;
                if (!isFloodPassable(level, n)) continue;
                if (exterior.contains(n)) continue;
                if (interior.size() >= MAX_INTERIOR) return null;
                interior.add(n);
                queue.add(n);
            }
        }
        return new OpenHouse(interior, exterior);
    }

    private static Set<BlockPos> floodExterior(LevelReader level, int minX, int minY, int minZ,
                                                int maxX, int maxY, int maxZ) {
        Set<BlockPos> exterior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                seedExterior(level, new BlockPos(x, minY, z), exterior, queue);
                seedExterior(level, new BlockPos(x, maxY, z), exterior, queue);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                seedExterior(level, new BlockPos(x, y, minZ), exterior, queue);
                seedExterior(level, new BlockPos(x, y, maxZ), exterior, queue);
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                seedExterior(level, new BlockPos(minX, y, z), exterior, queue);
                seedExterior(level, new BlockPos(maxX, y, z), exterior, queue);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (exterior.contains(n)) continue;
                if (!inBox(n, minX, minY, minZ, maxX, maxY, maxZ)) continue;
                if (isStructural(level, n)) continue;
                if (isWallOpening(level, n)) continue;
                exterior.add(n);
                queue.add(n);
            }
        }
        return exterior;
    }

    private static void seedExterior(LevelReader level, BlockPos p, Set<BlockPos> exterior,
                                      ArrayDeque<BlockPos> queue) {
        if (exterior.contains(p)) return;
        if (isStructural(level, p)) return;
        if (isWallOpening(level, p)) return;
        exterior.add(p);
        queue.add(p);
    }

    private static boolean inBox(BlockPos p, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ) {
        return p.getX() >= minX && p.getX() <= maxX
            && p.getY() >= minY && p.getY() <= maxY
            && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    private static Set<BlockPos> extractShell(LevelReader level, Set<BlockPos> interior, BlockPos housePos,
                                              @Nullable Set<BlockPos> exterior) {
        Set<BlockPos> shell = new HashSet<>();
        for (BlockPos air : interior) {
            if (isDoorLike(level.getBlockState(air))) shell.add(air); // door cells: mark or they vanish
            for (int[] o : OFFSETS_26) {
                BlockPos n = air.offset(o[0], o[1], o[2]);
                if (interior.contains(n)) continue;
                if (!isStructural(level, n)) continue;
                shell.add(n);
            }
        }
        if (exterior != null) {
            shell.removeIf(solid -> isExteriorSpill(solid, interior, exterior, shell)); // after pass 1
        }
        shell.add(housePos.immutable());
        return shell;
    }

    private static boolean isExteriorSpill(BlockPos solid, Set<BlockPos> interior, Set<BlockPos> exterior,
                                            Set<BlockPos> shell) {
        if (!exterior.contains(solid.above())) return false;
        for (Direction d : Direction.values()) {
            if (interior.contains(solid.relative(d))) return false;
        }
        if (shell.contains(solid.below())) return false;
        return true;
    }

    private static boolean isStructural(BlockGetter level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return false;
        if (isDoorLike(s)) return true;
        return !s.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isFloodPassable(BlockGetter level, BlockPos pos) {
        return isDoorLike(level.getBlockState(pos)) || !isStructural(level, pos);
    }

    private static boolean isDoorLike(BlockState state) {
        return state.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof TrapDoorBlock
            || state.getBlock() instanceof FenceGateBlock;
    }

    private static List<BlockSelection> decompose(Set<BlockPos> shell, Settlement owner, Home home,
                                                   BlockPos housePos, UUID playerId) {
        Map<Integer, Set<Long>> layers = new HashMap<>();
        for (BlockPos p : shell) {
            layers.computeIfAbsent(p.getY(), k -> new HashSet<>()).add(packXZ(p.getX(), p.getZ()));
        }
        List<BlockSelection> boxes = new ArrayList<>();
        for (Map.Entry<Integer, Set<Long>> e : layers.entrySet()) {
            int y = e.getKey();
            Set<Long> cells = e.getValue();
            while (!cells.isEmpty() && boxes.size() < MAX_BOXES) {
                long start = minCell(cells);
                int x0 = unpackX(start), z0 = unpackZ(start);
                int x1 = x0;
                while (cells.contains(packXZ(x1 + 1, z0))) x1++;
                int z1 = z0;
                grow:
                while (true) {
                    int nz = z1 + 1;
                    for (int x = x0; x <= x1; x++) {
                        if (!cells.contains(packXZ(x, nz))) break grow;
                    }
                    z1 = nz;
                }
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) cells.remove(packXZ(x, z));
                }
                boxes.add(BlockSelection.home(UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                    new BlockPos(x0, y, z0), new BlockPos(x1, y, z1), home.id(), housePos.immutable(),
                    playerId));
            }
        }
        return boxes;
    }

    public static boolean isWallOpening(BlockGetter level, BlockPos pos) {
        int solidFaces = 0;
        for (Direction d : Direction.values()) {
            if (isStructural(level, pos.relative(d))) solidFaces++;
        }
        if (solidFaces >= OPENING_MIN_SOLID_FACES) return true;
        if (solidFaces < 2) return false;
        int solidEdges = 0;
        for (int[] o : EDGE_OFFSETS_12) {
            if (isStructural(level, pos.offset(o[0], o[1], o[2]))) solidEdges++;
        }
        return solidFaces + solidEdges >= DIAG_SEAL_MIN;
    }

    public static final int DIAG_SEAL_MIN = 10; // EMPIRICAL: tune in-game vs the real angled-wall house

    private static final int[][] EDGE_OFFSETS_12 = buildEdgeOffsets();

    private static int[][] buildEdgeOffsets() {
        java.util.List<int[]> offs = new java.util.ArrayList<>(12);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 2) offs.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offs.toArray(new int[0][]);
    }

    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
            Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long v) { return (int) (v >> 32); }

    private static int unpackZ(long v) { return (int) v; }

    private static long minCell(Set<Long> cells) {
        long best = Long.MAX_VALUE;
        int bestX = Integer.MAX_VALUE, bestZ = Integer.MAX_VALUE;
        for (long c : cells) {
            int x = unpackX(c), z = unpackZ(c);
            if (x < bestX || (x == bestX && z < bestZ)) {
                bestX = x; bestZ = z; best = c;
            }
        }
        return best;
    }

    private static int[][] build26() {
        List<int[]> offs = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) offs.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offs.toArray(new int[0][]);
    }
}
