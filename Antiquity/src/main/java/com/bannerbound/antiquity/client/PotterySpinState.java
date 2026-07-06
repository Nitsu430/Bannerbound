package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-only shared state between {@link PotteryScreen} and {@link PotterySlabRenderer}: the
 * screen writes the active wheel pos, accumulated spin angle, hold flag and progress; the renderer
 * reads them so the in-progress clay at that block spins with the player's mouse instead of the
 * idle fallback. One wheel at a time (static fields); the screen must clear() on removal.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PotterySpinState {
    private static BlockPos activePos;
    private static float angleDegrees;
    public static boolean holding;
    public static float progress;

    private PotterySpinState() {}

    public static void begin(BlockPos pos) {
        activePos = pos.immutable();
        angleDegrees = 0.0F;
        holding = false;
        progress = 0.0F;
    }

    public static void addRadians(double radians) {
        angleDegrees = (float) ((angleDegrees + Math.toDegrees(radians)) % 360.0);
    }

    public static float angleDegrees(BlockPos pos, float fallback) {
        return activePos != null && activePos.equals(pos) ? angleDegrees : fallback;
    }

    public static boolean activeFor(BlockPos pos) {
        return activePos != null && activePos.equals(pos);
    }

    public static void clear() {
        activePos = null;
        angleDegrees = 0.0F;
        holding = false;
        progress = 0.0F;
    }
}
