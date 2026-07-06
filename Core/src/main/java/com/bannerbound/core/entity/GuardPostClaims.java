package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-wide reservation of OPEN guard posts (rod-marked posts not bound to a specific citizen),
 * so a watch spreads one-guard-per-post instead of the whole squad crowding the gate. Mirrors
 * MinerClaims/PenClaims: a post's anchor BlockPos maps to the entity id of the guard manning it. A
 * claim whose owner no longer exists is stale and cleared lazily on access (isClaimedByOther), so
 * the map self-heals without a sweep. GuardWorkGoal uses ownedBy to prefer staying on the same post,
 * claim to reserve/refresh, and releaseAll when a guard stops or changes posts. Keys are stored
 * immutable so a caller's mutable BlockPos can't corrupt an existing reservation.
 *
 * <p>Bound posts (assigned to one citizen via the rod's target) don't go through here -- they're
 * private to that citizen and need no race. Server-thread only in practice; the map is concurrent
 * for safety.</p>
 */
@ApiStatus.Internal
final class GuardPostClaims {
    private static final Map<BlockPos, Integer> CLAIMS = new ConcurrentHashMap<>();

    private GuardPostClaims() {}

    static boolean isClaimedByOther(ServerLevel level, BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        if (owner == null || owner == selfId) return false;
        if (level.getEntity(owner) instanceof CitizenEntity c && c.isAlive()) return true;
        CLAIMS.remove(anchor, owner);
        return false;
    }

    static boolean ownedBy(BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        return owner != null && owner == selfId;
    }

    static void claim(BlockPos anchor, int selfId) {
        CLAIMS.put(anchor.immutable(), selfId);
    }

    static void releaseAll(int selfId) {
        CLAIMS.values().removeIf(owner -> owner == selfId);
    }
}
