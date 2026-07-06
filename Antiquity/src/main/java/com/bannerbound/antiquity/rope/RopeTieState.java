package com.bannerbound.antiquity.rope;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client-side preview state for rope tying: which tie point (post centre or gate upright) the local
 * player is tying a rope from, plus the just-tied rope (an order-normalized anchor pair) for the "zip
 * taut" settle animation. Drives the rope-to-hand preview line and the settle in RopeRenderEvents. The
 * anchor's roped model is shown server-authoritatively via blockstate, not here, to avoid flicker. A
 * single static is fine: only the one local player, never written server-side.
 */
@ApiStatus.Internal
public final class RopeTieState {
    private static RopeAnchor anchor;
    private static ResourceKey<Level> dimension;

    private static RopeAnchor zipA;
    private static RopeAnchor zipB;
    private static long zipStartTick;

    private RopeTieState() {}

    public static void recordZip(RopeAnchor a, RopeAnchor b, long gameTime) {
        if (a == null || b == null) {
            return;
        }
        boolean aFirst = a.compareTo(b) <= 0;
        zipA = (aFirst ? a : b).immutable();
        zipB = (aFirst ? b : a).immutable();
        zipStartTick = gameTime;
    }

    public static float zipProgress(RopeAnchor a, RopeAnchor b, long gameTime, float partial, float durationTicks) {
        if (zipA == null) {
            return -1.0F;
        }
        boolean match = (zipA.equals(a) && zipB.equals(b)) || (zipA.equals(b) && zipB.equals(a));
        if (!match) {
            return -1.0F;
        }
        float p = (gameTime - zipStartTick + partial) / durationTicks;
        return p >= 1.0F ? -1.0F : Math.max(0.0F, p);
    }

    public static void set(RopeAnchor a, ResourceKey<Level> dim) {
        anchor = a;
        dimension = dim;
    }

    public static void clear() {
        anchor = null;
        dimension = null;
    }

    public static RopeAnchor get() {
        return anchor;
    }

    public static boolean isAt(RopeAnchor a, ResourceKey<Level> dim) {
        return anchor != null && anchor.equals(a) && dim.equals(dimension);
    }
}
