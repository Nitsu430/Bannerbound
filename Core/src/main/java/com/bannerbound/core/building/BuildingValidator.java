package com.bannerbound.core.building;

import com.bannerbound.core.api.settlement.ImmigrationManager;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

/**
 * Tektopia-style enclosure validator. Starting from the air blocks adjacent to a marker block
 * (e.g. a Town Hall), flood-fills through air, requires every visited air block to have a non-air
 * "roof" block above it within MAX_Y, and stops at non-air blocks (walls/floor). Caps total volume
 * so an open structure can't run away across the world.
 *
 * <p>Two tiers: ROOF_ONLY (ancient-era workstations) just scans straight up from the marker and
 * succeeds on the first non-air block within MAX_Y - no flood, no walls, empty interior set (the
 * one-per-building check is skipped for this tier). ENCLOSED (later-era workstations) runs the full
 * flood-fill + roof scan. The 2-arg validate() defaults to ENCLOSED so existing call sites
 * (ImmigrationManager.revalidateWorkstationBuildings) keep working unchanged.
 *
 * <p>ENCLOSED runs in two passes and the ORDER matters: pass 1 floods and only checks the bounding
 * box / volume cap, so a wall gap or missing roof lets the flood escape outdoors and blow the cap -
 * this deliberately makes TOO_LARGE mean "not enclosed". Pass 2 (per-cell roof scan) is therefore
 * only reached once the flood stayed bounded, so a NO_ROOF failure there is a genuine roof gap, not
 * a leak through the walls.
 */
@ApiStatus.Internal
public final class BuildingValidator {
    public static final int MAX_X = 32;
    public static final int MAX_Z = 32;
    public static final int MAX_Y = 16;
    public static final int MAX_BLOCKS = 4096;

    private BuildingValidator() {
    }

    public enum FailReason {
        NO_INTERIOR,
        NO_ROOF,
        TOO_LARGE
    }

    public enum BuildingTier {
        ROOF_ONLY,
        ENCLOSED
    }

    public record Result(boolean valid, FailReason reason, BlockPos failPos, Set<BlockPos> interior) {
        public static Result success(Set<BlockPos> interior) {
            return new Result(true, null, null, interior);
        }

        public static Result fail(FailReason reason, BlockPos pos) {
            return new Result(false, reason, pos, Collections.emptySet());
        }
    }

    public static Result validate(BlockGetter level, BlockPos markerPos) {
        return validate(level, markerPos, BuildingTier.ENCLOSED);
    }

    public static Result validate(BlockGetter level, BlockPos markerPos, BuildingTier tier) {
        if (tier == BuildingTier.ROOF_ONLY) {
            BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
            for (int dy = 1; dy <= MAX_Y; dy++) {
                scan.set(markerPos.getX(), markerPos.getY() + dy, markerPos.getZ());
                if (!level.getBlockState(scan).isAir()) {
                    return Result.success(Collections.emptySet());
                }
            }
            return Result.fail(FailReason.NO_ROOF,
                new BlockPos(markerPos.getX(), markerPos.getY() + MAX_Y, markerPos.getZ()));
        }
        return validateEnclosed(level, markerPos);
    }

    private static Result validateEnclosed(BlockGetter level, BlockPos markerPos) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = markerPos.relative(dir);
            if (level.getBlockState(neighbor).isAir()) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        if (queue.isEmpty()) {
            return Result.fail(FailReason.NO_INTERIOR, markerPos);
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            minX = Math.min(minX, current.getX()); maxX = Math.max(maxX, current.getX());
            minY = Math.min(minY, current.getY()); maxY = Math.max(maxY, current.getY());
            minZ = Math.min(minZ, current.getZ()); maxZ = Math.max(maxZ, current.getZ());
            if (maxX - minX + 1 > MAX_X || maxZ - minZ + 1 > MAX_Z || maxY - minY + 1 > MAX_Y
                    || visited.size() > MAX_BLOCKS) {
                return Result.fail(FailReason.TOO_LARGE, current);
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (level.getBlockState(neighbor).isAir()) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        for (BlockPos pos : visited) {
            boolean hasRoof = false;
            for (int dy = 1; dy <= MAX_Y; dy++) {
                scan.set(pos.getX(), pos.getY() + dy, pos.getZ());
                if (!level.getBlockState(scan).isAir()) {
                    hasRoof = true;
                    break;
                }
            }
            if (!hasRoof) {
                return Result.fail(FailReason.NO_ROOF, pos);
            }
        }

        return Result.success(visited);
    }
}
