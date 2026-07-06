package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Process-wide soft lock so two diggers in the same settlement (or across settlements) do not both
 * walk to the same block. One block to one citizen, kept in two mirror maps (block->citizen and
 * citizen->block). JVM memory only, nothing to persist: a citizen that died or unloaded between ticks
 * loses its claim when its goal stops and calls release(). Every method is synchronized because
 * pathfinding goals may claim off the main thread; tryClaim is the only position-taking mutator and
 * is atomic, so two goals racing the same target see exactly one winner. releaseBlock() must be
 * called when a block is mined so the next pass's tryClaim does not see a stale entry pointing at air.
 */
@ApiStatus.Internal
public final class DiggerBlockClaimRegistry {
    private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
    private static final Map<UUID, BlockPos> BY_CITIZEN = new HashMap<>();

    private DiggerBlockClaimRegistry() {
    }

    public static synchronized boolean tryClaim(BlockPos pos, UUID citizenId) {
        BlockPos existing = BY_CITIZEN.get(citizenId);
        if (existing != null && !existing.equals(pos)) {
            CLAIMS.remove(existing);
        }
        UUID holder = CLAIMS.get(pos);
        if (holder != null && !holder.equals(citizenId)) {
            return false;
        }
        CLAIMS.put(pos.immutable(), citizenId);
        BY_CITIZEN.put(citizenId, pos.immutable());
        return true;
    }

    public static synchronized boolean isClaimedByOther(BlockPos pos, UUID citizenId) {
        UUID holder = CLAIMS.get(pos);
        return holder != null && !holder.equals(citizenId);
    }

    public static synchronized void release(UUID citizenId) {
        BlockPos held = BY_CITIZEN.remove(citizenId);
        if (held != null) CLAIMS.remove(held);
    }

    public static synchronized void releaseBlock(BlockPos pos) {
        UUID holder = CLAIMS.remove(pos);
        if (holder != null) {
            BlockPos byHolder = BY_CITIZEN.get(holder);
            if (byHolder != null && byHolder.equals(pos)) BY_CITIZEN.remove(holder);
        }
    }

    static synchronized void clearAll() {
        CLAIMS.clear();
        BY_CITIZEN.clear();
    }
}
