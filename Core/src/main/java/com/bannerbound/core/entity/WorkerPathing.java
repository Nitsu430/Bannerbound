package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * Shared stand-position checks for citizen work goals (farmer, digger, fisher, forester). "Walkable"
 * here means "a tile a worker can occupy to reach an adjacent work block" - NOT "empty": a worker
 * walks straight through crops, grass tufts and flowers (blocks with no collision shape), so those
 * count as passable. The old {@code isAir()} test treated them as obstacles, which left work blocks
 * fully ringed by grown crops with no eligible stand position and silently skipped. Liquids are
 * deliberately NOT passable: a no-collision water/lava block would otherwise read as standable, but
 * these land workers keep their feet on solid ground. A stand tile needs itself and the head space
 * above passable, with a collision-shape floor below.
 */
@ApiStatus.Internal
public final class WorkerPathing {
    private WorkerPathing() {
    }

    public static boolean isWalkable(BlockGetter level, BlockPos p) {
        if (!isPassable(level, p)) return false;
        if (!isPassable(level, p.above())) return false;
        return hasFloor(level, p.below());
    }

    public static boolean isPassable(BlockGetter level, BlockPos p) {
        if (!level.getFluidState(p).isEmpty()) return false;
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    public static boolean hasFloor(BlockGetter level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }
}
