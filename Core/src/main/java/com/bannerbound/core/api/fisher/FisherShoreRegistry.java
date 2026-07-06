package com.bannerbound.core.api.fisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Server-wide registry that locks a shore tile to a single fisher so multiple citizens - within one
 * settlement or across settlements - never converge on the exact same spot. Each fisher holds at
 * most one shore; claiming a new one atomically releases the old ({@link #tryClaim}), and
 * {@link com.bannerbound.core.entity.FisherWorkGoal#stop} calls {@link #release} when the goal ends.
 * A forward map (packed shore {@link BlockPos#asLong()} -&gt; owner id) plus a reverse index (owner
 * -&gt; shore) keeps both directions O(1). {@link #isClaimedByOther} filters candidate shores and
 * {@link #isAnyClaimWithin} enforces a minimum separation so two fishers never stand adjacent (both
 * for visual distinctness and because adjacent cast targets would merge on screen).
 * <p>
 * Mirrors {@link com.bannerbound.core.api.entity.ForesterTreeRegistry} in pattern + lifecycle: it
 * is in-memory only and drops on server restart (no fisher is mid-cast then, so the next scan
 * re-claims from scratch). Chunk unload skips {@code Goal.stop()}, so a claim whose owner is no
 * longer loaded/alive is treated as stale and cleared on read ({@link #ownerExists}, the
 * {@code DiggerClaims} pattern) rather than blocking that shore until restart. {@code tryClaim}
 * fails (preserving the caller's existing claim) when the target is held by a live different owner,
 * and is idempotent for same-citizen self-claims.
 * <p>
 * All calls happen on the server thread, so plain {@link HashMap} access is safe here.
 */
public final class FisherShoreRegistry {
    private static final Map<Long, UUID> shoreToFisher = new HashMap<>();
    private static final Map<UUID, Long> fisherToShore = new HashMap<>();

    private FisherShoreRegistry() {
    }

    public static boolean tryClaim(UUID citizenId, BlockPos shore) {
        long key = shore.asLong();
        UUID currentOwner = shoreToFisher.get(key);
        if (currentOwner != null && !currentOwner.equals(citizenId)) {
            if (ownerExists(currentOwner)) {
                return false;
            }
            release(currentOwner);   // stale: owner gone (unloaded/died without stop())
        }
        Long previousKey = fisherToShore.get(citizenId);
        if (previousKey != null && previousKey != key) {
            shoreToFisher.remove(previousKey);
        }
        shoreToFisher.put(key, citizenId);
        fisherToShore.put(citizenId, key);
        return true;
    }

    public static void release(UUID citizenId) {
        Long key = fisherToShore.remove(citizenId);
        if (key == null) return;
        UUID owner = shoreToFisher.get(key);
        if (citizenId.equals(owner)) {
            shoreToFisher.remove(key);
        }
    }

    public static boolean isClaimedByOther(BlockPos shore, UUID citizenId) {
        UUID owner = shoreToFisher.get(shore.asLong());
        if (owner == null || owner.equals(citizenId)) return false;
        if (ownerExists(owner)) return true;
        release(owner);   // stale: owner gone (unloaded/died without stop())
        return false;
    }

    public static boolean isAnyClaimWithin(BlockPos shore, UUID citizenId, int rangeBlocks) {
        double sqRange = (double) rangeBlocks * (double) rangeBlocks;
        List<UUID> stale = null;
        for (Map.Entry<Long, UUID> e : shoreToFisher.entrySet()) {
            if (e.getValue().equals(citizenId)) continue;
            BlockPos otherShore = BlockPos.of(e.getKey());
            if (shore.distSqr(otherShore) >= sqRange) continue;
            if (ownerExists(e.getValue())) return true;
            if (stale == null) stale = new ArrayList<>();
            stale.add(e.getValue());   // stale owner: collect, release after iteration (no mutation mid-loop)
        }
        if (stale != null) {
            for (UUID owner : stale) release(owner);
        }
        return false;
    }

    private static boolean ownerExists(UUID citizenId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return true;   // can't verify -> treat the claim as live
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(citizenId);
            if (e != null && e.isAlive()) return true;
        }
        return false;
    }
}
