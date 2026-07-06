package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-wide reservation of OPEN miner claims (ore-chunk markers not bound to a specific citizen),
 * so multiple miners spread across the marked deposits instead of all piling onto the first one.
 * Mirrors PenClaims: a marker's anchor BlockPos maps to the entity id of the miner working it; a
 * claim whose owner no longer exists is stale and cleared on access. Bound markers (assigned to one
 * citizen) don't go through here -- they're private and need no race. Server-thread only in
 * practice; the map is concurrent for safety.
 */
@ApiStatus.Internal
final class MinerClaims {
    private static final Map<BlockPos, Integer> CLAIMS = new ConcurrentHashMap<>();

    private MinerClaims() {}

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
