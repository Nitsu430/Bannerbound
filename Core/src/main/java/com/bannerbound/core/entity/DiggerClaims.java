package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-wide reservation of dig targets so multiple quarryworkers divide a work area instead of all
 * piling onto the same block (which wedged them against each other, neither able to reach it). One
 * BlockPos maps to the entity id of its owning worker; a worker skips any block (and, via
 * hasOtherClaimNear, any nearby standing spot) claimed by another worker while scanning, so the
 * second worker naturally picks the next block. In a one-wide tunnel the lone stand tile reads as
 * taken, so the second worker leaves the whole area alone rather than fighting for it.
 *
 * Claims are released when the owner mines the block, abandons it, or stops working. A claim whose
 * owner no longer exists in the world (died / unloaded without cleanup) is treated as stale and
 * removed lazily on access, so the position frees up on its own. Safe without explicit locking:
 * entity AI ticks run sequentially on the server thread, so claim/scan order is deterministic;
 * ConcurrentHashMap only guards against the odd cross-thread peek.
 */
@ApiStatus.Internal
final class DiggerClaims {
    private static final Map<BlockPos, Integer> CLAIMS = new ConcurrentHashMap<>();

    private DiggerClaims() {
    }

    static boolean isClaimedByOther(ServerLevel level, BlockPos pos, int selfId) {
        Integer owner = CLAIMS.get(pos);
        if (owner == null || owner == selfId) return false;
        if (level.getEntity(owner) instanceof CitizenEntity) return true;
        CLAIMS.remove(pos, owner);
        return false;
    }

    static boolean hasOtherClaimNear(ServerLevel level, BlockPos pos, int selfId, double radius) {
        double r2 = radius * radius;
        for (Map.Entry<BlockPos, Integer> e : CLAIMS.entrySet()) {
            int owner = e.getValue();
            if (owner == selfId) continue;
            if (pos.distSqr(e.getKey()) > r2) continue;
            if (level.getEntity(owner) instanceof CitizenEntity) return true;
            CLAIMS.remove(e.getKey(), owner);
        }
        return false;
    }

    static void claim(BlockPos pos, int selfId) {
        CLAIMS.put(pos.immutable(), selfId);
    }

    static void release(BlockPos pos, int selfId) {
        CLAIMS.remove(pos, selfId);
    }

    static void releaseAll(int selfId) {
        CLAIMS.values().removeIf(owner -> owner == selfId);
    }
}
