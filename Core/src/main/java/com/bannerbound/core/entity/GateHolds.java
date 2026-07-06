package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;

/**
 * Single-owner reservation for fence gates a worker is actively holding open. OpenFenceGateGoal
 * defers to any held gate so it can't fight the holder over the same block; that tug-of-war was
 * the rapid open/close flicker at a herder's pen gate (two systems toggling the same block every
 * tick). A hold expires if it isn't re-asserted every tick (TTL): a holder that dies, unloads, or
 * stops wanting the gate never leaks a permanent lock, and OpenFenceGateGoal resumes managing the
 * gate a couple ticks later. Callers must hold() every tick while they want the gate. Values are
 * {ownerEntityId, gameTime of last assertion} keyed by posLong. Server-thread only in practice;
 * the map is concurrent for safety.
 */
@ApiStatus.Internal
public final class GateHolds {
    private static final long TTL = 3L;
    private static final Map<Long, long[]> HELD = new ConcurrentHashMap<>();

    private GateHolds() {}

    public static void hold(BlockPos pos, int ownerId, long gameTime) {
        HELD.put(pos.asLong(), new long[] { ownerId, gameTime });
    }

    public static void release(BlockPos pos, int ownerId) {
        long key = pos.asLong();
        long[] v = HELD.get(key);
        if (v != null && v[0] == ownerId) {
            HELD.remove(key);
        }
    }

    public static boolean isHeld(BlockPos pos, long gameTime) {
        long[] v = HELD.get(pos.asLong());
        return v != null && gameTime - v[1] <= TTL;
    }
}
