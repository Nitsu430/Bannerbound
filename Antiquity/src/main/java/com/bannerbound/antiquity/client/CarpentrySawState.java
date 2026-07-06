package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Shared client-side state for the carpenter's-table saw minigame, written by
 * {@link WoodworkingTableSawScreen} (the input layer) and read by {@code WoodworkingTableRenderer} (the
 * in-world saw + log animation). Kept in a plain holder - deliberately NOT on the Screen - so the
 * block-entity renderer never has to reference a {@code Screen} subclass (a renderer pulling in GUI
 * classes during level rendering is both an anti-pattern and a class-load hazard). Mirrors how
 * {@code FletchingScreen} exposes its FOV statics, but split out because here a BER is the reader.
 * {@code sawY} (blade height, 0 low to 1 high, tracking the mouse), {@code progress} (0-1, scrolls
 * the log right), and {@code strokeProgress} (0-1 through the current stroke) drive the animation;
 * {@code activePos} identifies which table the session belongs to, {@code holding} drives the saw's
 * "biting" pose, {@code sceneYaw} is the cardinal yaw captured at open, and {@code pulse()} flashes
 * a 180ms feedback pulse when a stroke completes.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CarpentrySawState {
    private CarpentrySawState() {
    }

    public static volatile boolean active = false;
    public static volatile BlockPos activePos = null;
    public static volatile float sawY = 0.5F;
    public static volatile float progress = 0.0F;
    public static volatile int strokesNeeded = 1;
    public static volatile int strokesDone = 0;
    public static volatile float strokeProgress = 0.0F;
    public static volatile boolean holding = false;
    public static volatile float sceneYaw = 0.0F;
    private static volatile long pulseUntilMs = 0L;

    public static void begin(BlockPos pos, int strokes, float yaw) {
        activePos = pos;
        sawY = 0.5F;
        progress = 0.0F;
        strokesNeeded = Math.max(1, strokes);
        strokesDone = 0;
        strokeProgress = 0.0F;
        holding = false;
        sceneYaw = yaw;
        pulseUntilMs = 0L;
        active = true;
    }

    public static void updateProgress(double travelDone, double travelPerStroke) {
        double needed = Math.max(1.0, strokesNeeded * travelPerStroke);
        progress = (float) Mth.clamp(travelDone / needed, 0.0, 1.0);
        strokesDone = Math.min(strokesNeeded, (int) Math.floor(travelDone / travelPerStroke));
        strokeProgress = (float) ((travelDone % travelPerStroke) / travelPerStroke);
        sawY = 0.5F + 0.45F * (float) Math.sin(strokeProgress * Math.PI * 2.0);
    }

    public static void pulse() {
        pulseUntilMs = System.currentTimeMillis() + 180L;
    }

    public static float pulseAmount() {
        long left = pulseUntilMs - System.currentTimeMillis();
        return left <= 0L ? 0.0F : Mth.clamp(left / 180.0F, 0.0F, 1.0F);
    }

    public static void clear() {
        active = false;
        activePos = null;
        holding = false;
        progress = 0.0F;
        strokesNeeded = 1;
        strokesDone = 0;
        strokeProgress = 0.0F;
        sceneYaw = 0.0F;
        pulseUntilMs = 0L;
    }

    public static boolean activeFor(BlockPos pos) {
        return active && pos.equals(activePos);
    }
}
