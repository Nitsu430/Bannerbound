package com.bannerbound.core.api.entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Server-wide registry that locks a tree to a single forester so multiple citizens - within the
 * same settlement or across settlements - never converge on the same tree. Each lock covers
 * every log in the tree's connected component, taken all-or-nothing by {@code tryClaim}
 * (re-claiming logs you already own is idempotent); if any log is held by another citizen, the
 * whole tree is off-limits to others until the holder releases. {@code isAnyLogClaimedByOther}
 * is the cheap read-only form used during candidate filtering so a citizen never even considers
 * a tree another forester is mid-chop on.
 * <p>
 * Lives in memory only. Claims drop on server restart, which is correct because no citizen is
 * actively chopping at that moment - the next chop cycle re-claims from scratch. Chunk unload
 * skips {@code Goal.stop()}, so a claim whose owner is no longer loaded/alive is treated as stale
 * and cleared on read (the {@code DiggerClaims} pattern) instead of blocking others until restart.
 * <p>
 * All calls happen on the server thread (goal ticks), so plain {@link HashMap} access is fine.
 */
public final class ForesterTreeRegistry {
    private static final Map<Long, UUID> logToOwner = new HashMap<>();
    private static final Map<UUID, Set<Long>> ownerToLogs = new HashMap<>();

    private ForesterTreeRegistry() {
    }

    public static boolean tryClaim(UUID citizenId, Set<BlockPos> tree) {
        for (BlockPos p : tree) {
            UUID owner = logToOwner.get(p.asLong());
            if (owner != null && !owner.equals(citizenId)) {
                if (ownerExists(owner)) return false;
                release(owner); // stale claim: owner unloaded/died without stop(); clear on read
            }
        }
        Set<Long> claims = ownerToLogs.computeIfAbsent(citizenId, k -> new HashSet<>());
        for (BlockPos p : tree) {
            long key = p.asLong();
            logToOwner.put(key, citizenId);
            claims.add(key);
        }
        return true;
    }

    public static void release(UUID citizenId) {
        Set<Long> claims = ownerToLogs.remove(citizenId);
        if (claims == null) return;
        for (long key : claims) {
            UUID owner = logToOwner.get(key);
            if (citizenId.equals(owner)) {
                logToOwner.remove(key);
            }
        }
    }

    public static boolean isAnyLogClaimedByOther(Set<BlockPos> tree, UUID citizenId) {
        for (BlockPos p : tree) {
            UUID owner = logToOwner.get(p.asLong());
            if (owner != null && !owner.equals(citizenId)) {
                if (ownerExists(owner)) return true;
                release(owner); // stale claim: owner unloaded/died without stop(); clear on read
            }
        }
        return false;
    }

    private static boolean ownerExists(UUID citizenId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return true; // cannot verify -> treat the claim as live
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(citizenId);
            if (e != null && e.isAlive()) return true;
        }
        return false;
    }
}
