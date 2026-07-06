package com.bannerbound.core.api.walls;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.walls.DefaultWallDesigns.WallDesignSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Computes a WallPlan from a settlement's claimed-chunk set, the terrain under the border, and an
 * active design set. Pure derivation -- deterministic for (claims, terrain, designs); callers
 * freeze the result into WallData when the player commits, after which the plan is fixed and later
 * claim/terrain changes do nothing until an explicit re-adapt.
 *
 * <p>Pipeline (WALLS_PLAN.md sec B):
 * <ol>
 *   <li>Border tracing -- chunk edges whose 4-neighbour is unclaimed, chained into closed loops
 *       walked with the interior on the RIGHT (loops run clockwise; a right turn = convex corner,
 *       left = concave). Outposts (workingClaims) are excluded.
 *   <li>Corners -- every loop vertex stamps the corner design into the N x N square inside the
 *       territory diagonal to the vertex's open side; the design's outward faces point at that
 *       diagonal quadrant, one rule covering convex AND concave.
 *   <li>Run fill -- straight runs (outermost block ring inside the claim, clipped where corner
 *       squares overlap) tile with the segment design; a remainder that doesn't fit a full
 *       instance emits a truncated piece, never a hole. Gate anchors truncate the filler into the
 *       gate start so gates sit at 1-block granularity, but a gate must fit WHOLE.
 *   <li>Terrain -- per-column ground via a walk-down from the motion-blocking heightmap through
 *       vegetation and water. Placement is top-aligned onto a level top; a run-level chain
 *       (assignRunTops) keeps one walkable top across slopes -- high ground buries base courses
 *       (omitted, cheaper), dips get foundation fill up to MAX_FOUNDATION_COURSES.
 *   <li>Obstacles -- footprint vegetation is counted for the clear list; a piece whose outer row
 *       is mostly DEEP_WATER_DEPTH+ water becomes a recorded water gap (water IS the wall there).
 * </ol>
 *
 * <p>Ground is defined by the WALL_GROUND data tag, never by mere solidity: the existing wall, a
 * house straddling the border, or any player build reads as an OBSTACLE red-marked at true ground
 * level, not a surface to stack on (playtest 2026-06-11: solidity-based ground stacked walls on
 * walls). WALL_CLEARABLE (plus vegetation tags, replaceables, and empty-collision blocks such as
 * the Antiquity surface rocks) is removable decor. Both tags are data-driven so expansions extend
 * them without touching Core. The ground walk is server-side ONLY (client heightmaps past
 * WORLD_SURFACE/MOTION_BLOCKING are garbage) and passes committed-plan positions (existingWall)
 * through as air so a re-run never stacks a second wall on the half-built one (idempotent). Gate
 * anchors are packed (x,0,z) piece-start positions recorded from this same deterministic tiling,
 * so they stay stable across recomputes.
 */
public final class WallLayoutEngine {

    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> WALL_CLEARABLE =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "wall_clearable"));

    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> WALL_GROUND =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "wall_ground"));

    public static final int DRAPE_MAX_SLOPE = 1;
    public static final int DEEP_WATER_DEPTH = 4;
    private static final int GROUND_SCAN_CAP = 48;

    private WallLayoutEngine() {
    }

    public record Stats(int loops, int convexCorners, int concaveCorners,
                        int segments, int truncatedSegments, int gates, int waterGaps,
                        int draped, int stepped, int foundationBlocks, int clearBlocks,
                        int obstacleBlocks, int perimeterColumns) {
    }

    public record LayoutResult(WallPlan plan, Stats stats, List<String> warnings) {
    }

    private record Edge(int vx, int vz, Direction dir) {
        int endVx() { return vx + dir.getStepX(); }
        int endVz() { return vz + dir.getStepZ(); }
    }

    private record Corner(int vx, int vz, Direction in, Direction out) {
        boolean convex() {
            return out == in.getClockWise();
        }
    }

    private record Run(int vx, int vz, Direction dir, int lengthChunks, Corner startCorner, Corner endCorner) {
    }

    private record Ground(int groundY, int waterDepth, int clearBlocks, int obstacleBlocks) {
    }

    public static LayoutResult compute(ServerLevel level, Settlement settlement, WallDesignSet designs) {
        return compute(level, settlement, designs, it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET,
            it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET);
    }

    public static LayoutResult compute(ServerLevel level, Settlement settlement, WallDesignSet designs,
                                       it.unimi.dsi.fastutil.longs.LongSet existingWall,
                                       it.unimi.dsi.fastutil.longs.LongSet gateAnchors) {
        List<String> warnings = new ArrayList<>();
        if (settlement.claimedChunks().isEmpty()) {
            return new LayoutResult(new WallPlan(new ArrayList<>()),
                new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of("Settlement has no claimed territory."));
        }

        List<List<Edge>> loops = traceLoops(settlement.claimedChunks());
        if (loops.size() > 1) {
            warnings.add(loops.size() + " separate border loops (disjoint territory or interior hole) — all walled.");
        }

        List<WallPiece> pieces = new ArrayList<>();
        int convex = 0;
        int concave = 0;
        int segments = 0;
        int truncated = 0;
        int gates = 0;
        int waterGaps = 0;
        int draped = 0;
        int stepped = 0;
        int foundation = 0;
        int clears = 0;
        int obstacles = 0;
        int perimeter = 0;

        Map<BlockPos, Ground> groundCache = new HashMap<>();
        Sampler sampler = new Sampler(level, existingWall, gateAnchors, groundCache);

        for (List<Edge> loop : loops) {
            List<Corner> corners = cornersOf(loop);
            List<Run> runs = runsOf(corners);

            for (Corner corner : corners) {
                if (corner.convex()) convex++;
                else concave++;
                WallPiece piece = placeCorner(sampler, corner, designs.corner());
                if (piece.waterGap()) waterGaps++;
                pieces.add(piece);
                perimeter += piece.length() * piece.depth();
            }

            for (Run run : runs) {
                for (WallPiece piece : placeRun(sampler, run, designs)) {
                    pieces.add(piece);
                    perimeter += piece.length() * piece.depth();
                    if (piece.waterGap()) {
                        waterGaps++;
                    } else if (piece.kind() == WallDesign.Kind.GATE) {
                        gates++;
                    } else {
                        segments++;
                        WallDesign design = designs.byId(piece.designId());
                        if (design != null && piece.length() < design.length()) truncated++;
                    }
                }
            }
        }

        for (WallPiece piece : pieces) {
            if (piece.waterGap()) continue;
            if (piece.mode() == WallPiece.Mode.DRAPE) draped++;
            else stepped++;
            WallDesign design = designs.byId(piece.designId());
            if (design == null) continue;
            int[] counts = countFoundationAndClears(piece, design, groundCache);
            foundation += counts[0];
            clears += counts[1];
            obstacles += counts[2];
        }

        Stats stats = new Stats(loops.size(), convex, concave, segments, truncated, gates,
            waterGaps, draped, stepped, foundation, clears, obstacles, perimeter);
        return new LayoutResult(new WallPlan(pieces), stats, warnings);
    }

    private static List<List<Edge>> traceLoops(java.util.Set<Long> claimed) {
        Map<Long, List<Edge>> outgoing = new HashMap<>();
        int edgeCount = 0;
        for (long packed : claimed) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            if (!claimed.contains(ChunkPos.asLong(cx, cz - 1))) {
                addEdge(outgoing, new Edge(cx, cz, Direction.EAST));
                edgeCount++;
            }
            if (!claimed.contains(ChunkPos.asLong(cx + 1, cz))) {
                addEdge(outgoing, new Edge(cx + 1, cz, Direction.SOUTH));
                edgeCount++;
            }
            if (!claimed.contains(ChunkPos.asLong(cx, cz + 1))) {
                addEdge(outgoing, new Edge(cx + 1, cz + 1, Direction.WEST));
                edgeCount++;
            }
            if (!claimed.contains(ChunkPos.asLong(cx - 1, cz))) {
                addEdge(outgoing, new Edge(cx, cz + 1, Direction.NORTH));
                edgeCount++;
            }
        }

        List<List<Edge>> loops = new ArrayList<>();
        int consumed = 0;
        while (consumed < edgeCount) {
            Edge start = null;
            for (List<Edge> list : outgoing.values()) {
                if (!list.isEmpty()) {
                    start = list.get(0);
                    break;
                }
            }
            if (start == null) break;

            List<Edge> loop = new ArrayList<>();
            Edge current = start;
            while (true) {
                outgoing.get(vertexKey(current.vx(), current.vz())).remove(current);
                consumed++;
                loop.add(current);
                Edge next = nextEdge(outgoing, current, start);
                if (next == null || next.equals(start)) break; // loop closed
                current = next;
            }
            loops.add(loop);
        }
        return loops;
    }

    private static void addEdge(Map<Long, List<Edge>> outgoing, Edge edge) {
        outgoing.computeIfAbsent(vertexKey(edge.vx(), edge.vz()), k -> new ArrayList<>()).add(edge);
    }

    private static long vertexKey(int vx, int vz) {
        return ((long) vx << 32) ^ (vz & 0xFFFFFFFFL);
    }

    private static Edge nextEdge(Map<Long, List<Edge>> outgoing, Edge current, Edge start) {
        List<Edge> candidates = outgoing.get(vertexKey(current.endVx(), current.endVz()));
        boolean atStartVertex = current.endVx() == start.vx() && current.endVz() == start.vz();
        Direction[] preference = {
            current.dir().getClockWise(), current.dir(), current.dir().getCounterClockWise()
        };
        for (Direction dir : preference) {
            if (atStartVertex && start.dir() == dir) return start;
            if (candidates != null) {
                for (Edge candidate : candidates) {
                    if (candidate.dir() == dir) return candidate;
                }
            }
        }
        return candidates == null || candidates.isEmpty() ? null : candidates.get(0);
    }

    private static List<Corner> cornersOf(List<Edge> loop) {
        List<Corner> corners = new ArrayList<>();
        for (int i = 0; i < loop.size(); i++) {
            Edge edge = loop.get(i);
            Edge next = loop.get((i + 1) % loop.size());
            if (edge.dir() != next.dir()) {
                corners.add(new Corner(next.vx(), next.vz(), edge.dir(), next.dir()));
            }
        }
        return corners;
    }

    private static List<Run> runsOf(List<Corner> corners) {
        if (corners.isEmpty()) return List.of();
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < corners.size(); i++) {
            Corner from = corners.get(i);
            Corner to = corners.get((i + 1) % corners.size());
            Direction dir = from.out();
            int lengthChunks = Math.abs(to.vx() - from.vx()) + Math.abs(to.vz() - from.vz());
            runs.add(new Run(from.vx(), from.vz(), dir, lengthChunks, from, to));
        }
        return runs;
    }

    private static WallPiece placeCorner(Sampler sampler, Corner corner, WallDesign design) {
        int n = design.length();
        int vx = corner.vx() * 16;
        int vz = corner.vz() * 16;

        boolean xEast = interiorSignX(corner) > 0;
        boolean zSouth = interiorSignZ(corner) > 0;
        int minX = xEast ? vx : vx - n;
        int minZ = zSouth ? vz : vz - n;
        int maxX = minX + n - 1;
        int maxZ = minZ + n - 1;

        Direction outward;
        int startX;
        int startZ;
        if (xEast && zSouth) {
            outward = Direction.NORTH;
            startX = minX;
            startZ = minZ;
        } else if (!xEast && zSouth) {
            outward = Direction.EAST;
            startX = maxX;
            startZ = minZ;
        } else if (!xEast && !zSouth) {
            outward = Direction.SOUTH;
            startX = maxX;
            startZ = maxZ;
        } else {
            outward = Direction.WEST;
            startX = minX;
            startZ = maxZ;
        }

        return samplePiece(sampler, design, design.id(), WallDesign.Kind.CORNER, outward,
            startX, startZ, n, n);
    }

    private static int interiorSignX(Corner corner) {
        int sign = interiorSignX(corner.in());
        return sign != 0 ? sign : interiorSignX(corner.out());
    }

    private static int interiorSignZ(Corner corner) {
        int sign = interiorSignZ(corner.in());
        return sign != 0 ? sign : interiorSignZ(corner.out());
    }

    private static int interiorSignX(Direction dir) {
        return switch (dir) {
            case SOUTH -> -1;
            case NORTH -> 1;
            default -> 0;
        };
    }

    private static int interiorSignZ(Direction dir) {
        return switch (dir) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    private static List<WallPiece> placeRun(Sampler sampler, Run run, WallDesignSet designs) {
        Direction dir = run.dir();
        int vx = run.vx() * 16;
        int vz = run.vz() * 16;
        int lengthBlocks = run.lengthChunks() * 16;
        int cornerN = designs.corner().length();

        Direction outward;
        int rowX = 0;
        int rowZ = 0;
        int alongMin;
        int alongMax;
        boolean alongIsX;
        switch (dir) {
            case EAST -> {
                outward = Direction.NORTH;
                rowZ = vz;
                alongIsX = true;
                alongMin = vx;
                alongMax = vx + lengthBlocks - 1;
            }
            case SOUTH -> {
                outward = Direction.EAST;
                rowX = vx - 1;
                alongIsX = false;
                alongMin = vz;
                alongMax = vz + lengthBlocks - 1;
            }
            case WEST -> {
                outward = Direction.SOUTH;
                rowZ = vz - 1;
                alongIsX = true;
                alongMin = vx - lengthBlocks;
                alongMax = vx - 1;
            }
            case NORTH -> {
                outward = Direction.WEST;
                rowX = vx;
                alongIsX = false;
                alongMin = vz - lengthBlocks;
                alongMax = vz - 1;
            }
            default -> throw new IllegalStateException("Vertical border direction");
        }

        // Apply BOTH bounds from BOTH corner clips: along can be flipped vs travel, so a corner may sit at the interval's MAX end; taking one bound per corner buries corners under segments.
        int[] clipped = clipForCorner(run.startCorner(), cornerN, alongIsX, alongMin, alongMax);
        alongMin = clipped[0];
        alongMax = clipped[1];
        clipped = clipForCorner(run.endCorner(), cornerN, alongIsX, alongMin, alongMax);
        alongMin = clipped[0];
        alongMax = clipped[1];
        if (alongMax < alongMin) return List.of();

        Direction along = outward.getClockWise();
        boolean canonicalAscending = along.getStepX() + along.getStepZ() > 0;
        WallDesign wall = designs.wall();
        WallDesign gate = designs.gate();

        it.unimi.dsi.fastutil.ints.IntArrayList gateOffsets = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.longs.LongIterator anchorIt = sampler.gateAnchors().iterator();
        while (anchorIt.hasNext()) {
            long anchor = anchorIt.nextLong();
            int ax = BlockPos.getX(anchor);
            int az = BlockPos.getZ(anchor);
            if (alongIsX ? az != rowZ : ax != rowX) continue;
            int a = alongIsX ? ax : az;
            if (a < alongMin || a > alongMax) continue;
            gateOffsets.add(canonicalAscending ? a - alongMin : alongMax - a);
        }
        gateOffsets.sort(it.unimi.dsi.fastutil.ints.IntComparators.NATURAL_COMPARATOR);

        List<WallPiece> pieces = new ArrayList<>();
        int remaining = alongMax - alongMin + 1;
        int cursor = canonicalAscending ? alongMin : alongMax;
        int walked = 0;
        int gateIdx = 0;
        while (remaining > 0) {
            while (gateIdx < gateOffsets.size() && gateOffsets.getInt(gateIdx) < walked) gateIdx++;
            boolean isGate = gateIdx < gateOffsets.size() && gateOffsets.getInt(gateIdx) == walked
                // A gate must fit WHOLE: a span truncated by the run end would lose its opening.
                && remaining >= gate.length();
            WallDesign design = isGate ? gate : wall;
            int take = Math.min(design.length(), remaining);
            if (!isGate && gateIdx < gateOffsets.size()) {
                take = Math.min(take, gateOffsets.getInt(gateIdx) - walked);
            }
            int startX = alongIsX ? cursor : rowX;
            int startZ = alongIsX ? rowZ : cursor;
            pieces.add(samplePiece(sampler, design, design.id(),
                isGate ? WallDesign.Kind.GATE : WallDesign.Kind.SEGMENT, outward,
                startX, startZ, take, design.depth()));
            cursor += (canonicalAscending ? 1 : -1) * take;
            walked += take;
            remaining -= take;
            if (isGate) gateIdx++;
        }
        return assignRunTops(pieces, designs);
    }

    private static int[] clipForCorner(Corner corner, int cornerN, boolean alongIsX,
                                       int alongMin, int alongMax) {
        int v = (alongIsX ? corner.vx() : corner.vz()) * 16;
        boolean positiveSide = alongIsX ? interiorSignX(corner) > 0 : interiorSignZ(corner) > 0;
        int squareMin = positiveSide ? v : v - cornerN;
        int squareMax = squareMin + cornerN - 1;
        int newMin = alongMin;
        int newMax = alongMax;
        if (squareMax >= alongMin && squareMin <= alongMax) {
            if (squareMin <= alongMin) {
                newMin = squareMax + 1;
            }
            if (squareMax >= alongMax) {
                newMax = squareMin - 1;
            }
        }
        return new int[]{newMin, newMax};
    }

    private record Sampler(ServerLevel level, it.unimi.dsi.fastutil.longs.LongSet existingWall,
                           it.unimi.dsi.fastutil.longs.LongSet gateAnchors,
                           Map<BlockPos, Ground> groundCache) {
    }

    private static WallPiece samplePiece(Sampler sampler, WallDesign design, String designId,
                                         WallDesign.Kind kind, Direction outward,
                                         int startX, int startZ, int length, int depth) {
        Direction along = outward.getClockWise();
        Direction inward = outward.getOpposite();
        int[] groundY = new int[length * depth];
        int deepWaterOuterColumns = 0;
        int minGround = Integer.MAX_VALUE;
        List<Integer> grounds = new ArrayList<>(length * depth);
        for (int l = 0; l < length; l++) {
            for (int d = 0; d < depth; d++) {
                int x = startX + along.getStepX() * l + inward.getStepX() * d;
                int z = startZ + along.getStepZ() * l + inward.getStepZ() * d;
                Ground ground = sampleGround(sampler, x, z);
                groundY[l * depth + d] = ground.groundY();
                grounds.add(ground.groundY());
                minGround = Math.min(minGround, ground.groundY());
                if (d == 0 && ground.waterDepth() >= DEEP_WATER_DEPTH) {
                    deepWaterOuterColumns++;
                }
            }
        }

        boolean waterGap = deepWaterOuterColumns > length / 2;

        int baseY = (minGround == Integer.MAX_VALUE ? 0 : maxOf(groundY)) + 1;
        return new WallPiece(designId, kind, outward, startX, startZ, length, depth,
            WallPiece.Mode.STEPPED, baseY, groundY, waterGap);
    }

    private static int maxOf(int[] values) {
        int max = Integer.MIN_VALUE;
        for (int v : values) max = Math.max(max, v);
        return max;
    }

    private static final int MAX_FOUNDATION_COURSES = 4;

    private static int maxBuriedCourses(int height) {
        return Math.max(1, height / 3);
    }

    private static List<WallPiece> assignRunTops(List<WallPiece> pieces, WallDesignSet designs) {
        List<WallPiece> out = new ArrayList<>(pieces.size());
        int currentTop = Integer.MIN_VALUE;
        for (WallPiece piece : pieces) {
            WallDesign design = designs.byId(piece.designId());
            if (piece.waterGap() || design == null) {
                out.add(piece);
                currentTop = Integer.MIN_VALUE; // a gap breaks the level chain
                continue;
            }
            int height = design.height();
            // A gate's passage rows must never be buried: force a re-anchor so the full gate sits on its own crest, then chain onward.
            if (piece.kind() == WallDesign.Kind.GATE) {
                currentTop = Integer.MIN_VALUE;
            }
            int minTop = piece.maxGround() + height - maxBuriedCourses(height);
            int maxTop = piece.minGround() + height + MAX_FOUNDATION_COURSES;
            if (currentTop < minTop || currentTop > maxTop) {
                currentTop = piece.maxGround() + height;
            }
            out.add(piece.withBaseY(currentTop - height + 1));
        }
        return out;
    }

    private static Ground sampleGround(Sampler sampler, int x, int z) {
        BlockPos key = new BlockPos(x, 0, z);
        Ground cached = sampler.groundCache().get(key);
        if (cached != null) return cached;

        ServerLevel level = sampler.level();
        // Server-side ONLY: client heightmaps past WORLD_SURFACE/MOTION_BLOCKING are garbage.
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int water = 0;
        int clears = 0;
        int obstacles = 0;
        int scanned = 0;
        int firstSolid = Integer.MIN_VALUE;
        boolean groundFound = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (y > level.getMinBuildHeight() + 1 && scanned++ < GROUND_SCAN_CAP) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            // Pass committed-plan blocks through BEFORE the WALL_GROUND check: a wall designed from terrain blocks must read as wall, not a hill (checking ground first re-stacks walls on walls).
            if (sampler.existingWall().contains(cursor.asLong())) {
                y--;
                continue;
            }
            if (state.is(WALL_GROUND)) {
                groundFound = true;
                break;
            }
            if (state.isAir()) {
                y--;
            } else if (state.getFluidState().is(FluidTags.WATER)) {
                water++;
                y--;
            } else if (isClearable(level, cursor, state)) {
                clears++;
                y--;
            } else {
                if (firstSolid == Integer.MIN_VALUE) {
                    firstSolid = y;
                }
                obstacles++;
                y--;
            }
        }
        if (!groundFound && firstSolid != Integer.MIN_VALUE) {
            y = firstSolid;
            obstacles = 0;
        }
        Ground ground = new Ground(y, water, clears, obstacles);
        sampler.groundCache().put(key, ground);
        return ground;
    }

    public static boolean isClearableBlock(ServerLevel level, BlockPos pos, BlockState state) {
        return isClearable(level, pos, state);
    }

    private static boolean isClearable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.is(BlockTags.LEAVES)
            || state.is(BlockTags.LOGS)
            || state.is(BlockTags.SAPLINGS)
            || state.is(BlockTags.FLOWERS)
            || state.is(WALL_CLEARABLE)
            || state.canBeReplaced()
            || state.getCollisionShape(level, pos).isEmpty();
    }

    private static int[] countFoundationAndClears(WallPiece piece, WallDesign design,
                                                  Map<BlockPos, Ground> groundCache) {
        int foundation = 0;
        int clears = 0;
        int obstacles = 0;
        Direction along = piece.along();
        Direction inward = piece.inward();
        for (int l = 0; l < piece.length(); l++) {
            for (int d = 0; d < piece.depth(); d++) {
                int x = piece.startX() + along.getStepX() * l + inward.getStepX() * d;
                int z = piece.startZ() + along.getStepZ() * l + inward.getStepZ() * d;
                Ground ground = groundCache.get(new BlockPos(x, 0, z));
                if (ground == null) continue;
                clears += ground.clearBlocks();
                obstacles += ground.obstacleBlocks();
                if (piece.mode() == WallPiece.Mode.STEPPED) {
                    foundation += Math.max(0, piece.baseY() - (ground.groundY() + 1));
                }
            }
        }
        return new int[]{foundation, clears, obstacles};
    }

    public static Map<WallDesign.Kind, Integer> countByKind(WallPlan plan) {
        Map<WallDesign.Kind, Integer> counts = new EnumMap<>(WallDesign.Kind.class);
        for (WallPiece piece : plan.pieces()) {
            counts.merge(piece.kind(), 1, Integer::sum);
        }
        return counts;
    }

    public static Map<net.minecraft.world.item.Item, Integer> sortedRequired(Map<net.minecraft.world.item.Item, Integer> required) {
        List<Map.Entry<net.minecraft.world.item.Item, Integer>> entries = new ArrayList<>(required.entrySet());
        entries.sort(Map.Entry.<net.minecraft.world.item.Item, Integer>comparingByValue().reversed());
        Map<net.minecraft.world.item.Item, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
