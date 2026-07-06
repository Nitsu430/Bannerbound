package com.bannerbound.core.building;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Stockpile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Enclosure scanner for the Stockpile Rack. Unlike {@link BuildingValidator} - a 3D air flood that
 * leaks over a 1-tall fence and never checks boundary block type - this is a <b>2D flood at the
 * rack's own Y level</b>: it walks outward, treats fences / fence gates / walls as the ONLY
 * perimeter, and floods through everything else (air, terrain, raised platforms, decorations,
 * containers) as interior, so you can put solid blocks inside. Every interior tile must be roofed
 * (a non-air block within MAX_ROOF_HEIGHT above), and the perimeter must include at least one fence
 * gate so citizens can walk in and out. An un-fenced/gapped side leaks the flood -> TOO_LARGE.
 *
 * <p>The fenced floor may sit BELOW an elevated rack (fence ring on the ground, block up on a
 * platform), so {@link #scan} floods the block's own plane first then successively lower planes (up
 * to MAX_ANCHOR_DROP) and the first valid enclosure wins; if none validate the block-plane result
 * is returned so the failure message reflects where the player is looking. The fence ring is
 * projected across a small vertical band (PROJECT_DOWN..PROJECT_UP = +-2, covering common one/two
 * block terrain steps) so a ring that terraces with the terrain reads as one continuous wall.
 *
 * <p>Storage blocks are collected from the whole structure volume (footprint bounding box widened a
 * block so perimeter-post storage counts, across the plane +-MAX_ROOF_HEIGHT) and capped at
 * {@link #MAX_STORAGE}. A block counts as storage if it exposes the item-handler capability (vanilla
 * chests/barrels, modded containers) OR its block entity is a vanilla {@link net.minecraft.world.Container}
 * - the Antiquity basket backs a Container but does not register the capability, so both are checked.
 */
@ApiStatus.Internal
public final class StockpileEnclosure {
    public static final int MAX_SPAN = 32;
    public static final int MAX_INTERIOR = 1024;
    public static final int MAX_ROOF_HEIGHT = 16;
    public static final int MAX_STORAGE = Stockpile.MAX_CONTAINERS;

    public enum FailReason { NOT_ENCLOSED, NO_GATE, NO_ROOF, TOO_LARGE }

    public record Result(boolean valid, FailReason reason, BlockPos failPos,
                          Set<BlockPos> interior, List<BlockPos> storage) {
        public static Result success(Set<BlockPos> interior, List<BlockPos> storage) {
            return new Result(true, null, null, interior, storage);
        }

        public static Result fail(FailReason reason, BlockPos pos, Set<BlockPos> interior) {
            return new Result(false, reason, pos, interior, List.of());
        }
    }

    private StockpileEnclosure() {
    }

    public static final int MAX_ANCHOR_DROP = 8;

    public static Result scan(ServerLevel level, BlockPos rackPos) {
        Result blockPlane = null;
        for (int drop = 0; drop <= MAX_ANCHOR_DROP; drop++) {
            Result r = scanAt(level, new BlockPos(rackPos.getX(), rackPos.getY() - drop, rackPos.getZ()));
            if (r.valid()) return r;
            if (blockPlane == null) blockPlane = r;
        }
        return blockPlane;
    }

    private static Result scanAt(ServerLevel level, BlockPos seed) {
        Set<BlockPos> interior = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = seed.immutable();
        interior.add(start);
        queue.add(start);

        int minX = start.getX(), maxX = start.getX();
        int minZ = start.getZ(), maxZ = start.getZ();
        boolean hasGate = false;

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = cur.relative(dir);
                if (interior.contains(n)) continue;
                int boundary = boundaryColumn(level, n.getX(), n.getZ(), seed.getY());
                if (boundary > 0) {
                    if (boundary == 2) hasGate = true;
                    continue;
                }
                interior.add(n);
                queue.add(n);
                minX = Math.min(minX, n.getX());
                maxX = Math.max(maxX, n.getX());
                minZ = Math.min(minZ, n.getZ());
                maxZ = Math.max(maxZ, n.getZ());
                if (maxX - minX + 1 > MAX_SPAN || maxZ - minZ + 1 > MAX_SPAN
                        || interior.size() > MAX_INTERIOR) {
                    return Result.fail(FailReason.TOO_LARGE, n, interior);
                }
            }
        }

        BlockPos.MutableBlockPos rp = new BlockPos.MutableBlockPos();
        for (BlockPos cell : interior) {
            boolean roofed = false;
            for (int dy = 1; dy <= MAX_ROOF_HEIGHT; dy++) {
                rp.set(cell.getX(), cell.getY() + dy, cell.getZ());
                if (!level.getBlockState(rp).isAir()) {
                    roofed = true;
                    break;
                }
            }
            if (!roofed) return Result.fail(FailReason.NO_ROOF, cell, interior);
        }

        if (!hasGate) return Result.fail(FailReason.NO_GATE, seed, interior);

        List<BlockPos> storage = new ArrayList<>();
        BlockPos.MutableBlockPos sp = new BlockPos.MutableBlockPos();
        int y0 = seed.getY() - MAX_ROOF_HEIGHT;
        int y1 = seed.getY() + MAX_ROOF_HEIGHT;
        storageScan:
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (isContainer(level, sp.set(x, y, z))) {
                        storage.add(new BlockPos(x, y, z));
                        if (storage.size() >= MAX_STORAGE) break storageScan;
                    }
                }
            }
        }
        return Result.success(interior, storage);
    }

    private static boolean isFenceLike(BlockState state) {
        return state.is(BlockTags.FENCES)
            || state.is(BlockTags.FENCE_GATES)
            || state.is(BlockTags.WALLS);
    }

    private static final int PROJECT_DOWN = 2;
    private static final int PROJECT_UP = 2;

    private static int boundaryColumn(ServerLevel level, int x, int z, int scanY) {
        int code = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -PROJECT_DOWN; dy <= PROJECT_UP; dy++) {
            BlockState s = level.getBlockState(p.set(x, scanY + dy, z));
            if (isFenceLike(s)) {
                if (s.is(BlockTags.FENCE_GATES)) return 2;
                code = 1;
            }
        }
        return code;
    }

    public static boolean isContainer(ServerLevel level, BlockPos pos) {
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) return true;
        return level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
    }
}
