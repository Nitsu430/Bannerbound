package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-only shared state between the press-and-grind screen and the mortar renderer. The screen
 * feeds the player's live press depth (0 lifted .. 1 fully down) and accumulated grind motion
 * here; {@code MortarAndPestleRenderer} reads it to drive the pestle in-world so it dips and
 * circles in time with the mouse. Grind motion is stored pre-mapped to "Mix" animation
 * milliseconds (1 full turn ~= 1000 ms) so the renderer can hand it straight to
 * KeyframeAnimations. The screen must call clear() on removal or the pestle keeps animating.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MortarGrindState {
    private static BlockPos activePos;
    private static float pressDepth;
    private static long grindElapsedMs;

    private MortarGrindState() {}

    public static void begin(BlockPos pos) {
        activePos = pos.immutable();
        pressDepth = 0.0F;
        grindElapsedMs = 0L;
    }

    public static void setPress(float depth) {
        pressDepth = Math.max(0.0F, Math.min(1.0F, depth));
    }

    public static void addGrind(double radians) {
        grindElapsedMs += (long) (Math.abs(radians) / (Math.PI * 2.0) * 1000.0);
    }

    public static boolean activeFor(BlockPos pos) {
        return activePos != null && activePos.equals(pos);
    }

    public static float pressDepth() {
        return pressDepth;
    }

    public static long grindElapsedMs() {
        return grindElapsedMs;
    }

    public static void clear() {
        activePos = null;
        pressDepth = 0.0F;
        grindElapsedMs = 0L;
    }
}
