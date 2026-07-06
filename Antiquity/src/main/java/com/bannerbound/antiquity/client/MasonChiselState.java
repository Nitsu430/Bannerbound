package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Shared client-side state for the mason's-bench chisel-strike minigame, written by
 * MasonChiselScreen (the input layer) and read every frame by MasonsBenchRenderer (the in-world
 * chisel + stone animation, matched on activePos via activeFor). Kept in a plain static holder -
 * deliberately NOT on the Screen - so the block-entity renderer never references a Screen
 * subclass. Mirrors CarpentrySawState, but models discrete timed strikes rather than continuous
 * sawing travel: strike() bumps strikesDone/progress and opens a 160ms down-stroke window that
 * animate() (call once per render frame) converts into toolY (0 raised -> 1 driven down -> 0, a
 * sine hump), plus a 180ms feedback pulse read via pulseAmount(). sceneYaw is the cardinal-snapped
 * yaw captured when the minigame opens. Fields are volatile; begin() seeds every field before
 * flipping active so a reader never sees a half-initialized session, and clear() fully resets on
 * screen close.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MasonChiselState {
    private MasonChiselState() {
    }

    public static volatile boolean active = false;
    public static volatile BlockPos activePos = null;
    public static volatile float progress = 0.0F;
    public static volatile int strikesNeeded = 1;
    public static volatile int strikesDone = 0;
    public static volatile float toolY = 0.0F;
    public static volatile float sceneYaw = 0.0F;
    private static volatile long pulseUntilMs = 0L;
    private static volatile long strikeAnimUntilMs = 0L;

    public static void begin(BlockPos pos, int strikes, float yaw) {
        activePos = pos;
        progress = 0.0F;
        strikesNeeded = Math.max(1, strikes);
        strikesDone = 0;
        toolY = 0.0F;
        sceneYaw = yaw;
        pulseUntilMs = 0L;
        strikeAnimUntilMs = 0L;
        active = true; // set last: readers key off active and must never see a half-seeded session
    }

    public static void strike() {
        strikesDone = Math.min(strikesNeeded, strikesDone + 1);
        progress = (float) strikesDone / Math.max(1, strikesNeeded);
        pulseUntilMs = System.currentTimeMillis() + 180L;
        strikeAnimUntilMs = System.currentTimeMillis() + 160L;
    }

    public static void animate() {
        long left = strikeAnimUntilMs - System.currentTimeMillis();
        if (left <= 0L) {
            toolY = 0.0F;
            return;
        }
        float f = 1.0F - Mth.clamp(left / 160.0F, 0.0F, 1.0F);
        toolY = (float) Math.sin(f * Math.PI);
    }

    public static float pulseAmount() {
        long left = pulseUntilMs - System.currentTimeMillis();
        return left <= 0L ? 0.0F : Mth.clamp(left / 180.0F, 0.0F, 1.0F);
    }

    public static void clear() {
        active = false;
        activePos = null;
        progress = 0.0F;
        strikesNeeded = 1;
        strikesDone = 0;
        toolY = 0.0F;
        sceneYaw = 0.0F;
        pulseUntilMs = 0L;
        strikeAnimUntilMs = 0L;
    }

    public static boolean activeFor(BlockPos pos) {
        return active && pos.equals(activePos);
    }
}
