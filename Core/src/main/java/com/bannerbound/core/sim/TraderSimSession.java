package com.bannerbound.core.sim;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Server-side state for one running long-distance journey - either the
 * {@code /bannerbound trader_simulate} debug run (a spawned throwaway trader) or an adopted trade
 * courier (a real roster stocker walking a deal's goods; see {@code TradeCourierManager}). Holds the
 * waypoint chain ({x, z} columns start -> end), the moving force-ticket centre (the {@link #id} also
 * serves as the chunk-ticket value), and the stuck/progress counters the per-tick driver mutates.
 * Sessions are transient (rebuilt after a restart by their owner); the road they lay persists
 * independently.
 * <p>
 * Realize-on-observe: while unobserved a session goes {@link #ghost} - no entity, no chunk-load,
 * position advanced by a computed clock at {@link #observedSpeed} (measured while real, reused by the
 * ghost) - then re-spawns a real entity when a player is near. When {@link #sailing} is false deep
 * water is a wall; otherwise the trader may cross it by boat. Adopted-courier mode drives a real
 * roster citizen that is never ghosted or discarded. The ghost-road dedup fields are per-session
 * (they were once static) so concurrent journeys do not clobber each other's last-paved column.
 */
public final class TraderSimSession {
    public final UUID id;
    public UUID traderId;
    public final UUID initiator;
    public final BlockPos start;
    public final BlockPos end;
    public List<int[]> waypoints;
    public final long startGameTick;
    public long maxGameTick;
    public final double totalDist;
    public final boolean sailing;

    public int index = 0;
    public ChunkPos ticketCenter;
    public BlockPos lastRoadPos = null;
    public UUID boatId = null;
    public long noBoatUntil = 0;
    public double bestDist = Double.MAX_VALUE;
    public int noProgressTicks = 0;
    public int nudges = 0;
    public long lastProgressTick = 0;
    public boolean returning = false;
    public String pendingFail = null;
    public String pendingFailReason = null;

    public boolean ghost = false;
    public double gx, gy, gz;
    public double observedSpeed = 0.18;
    public double lastX = Double.NaN; // NaN = skip the speed sample this tick (no prior real position yet)
    public double lastZ = Double.NaN;
    public int deepWaterTicks = 0;

    public CompletableFuture<List<int[]>> planFuture;

    public int lastGhostRoadX = Integer.MIN_VALUE;
    public int lastGhostRoadZ = Integer.MIN_VALUE;

    public boolean adopted = false;
    public boolean debug = false;
    public boolean mustered = false;
    public long musterDeadline = 0;
    public double prevStepHeight = Double.NaN;
    public int lostTicks = 0;
    public transient TraderSimManager.JourneyListener listener;

    public TraderSimSession(UUID id, UUID traderId, UUID initiator, BlockPos start, BlockPos end,
                            List<int[]> waypoints, ChunkPos ticketCenter,
                            long startGameTick, long maxGameTick, double totalDist, boolean sailing) {
        this.id = id;
        this.traderId = traderId;
        this.initiator = initiator;
        this.start = start;
        this.end = end;
        this.waypoints = waypoints;
        this.ticketCenter = ticketCenter;
        this.startGameTick = startGameTick;
        this.maxGameTick = maxGameTick;
        this.totalDist = totalDist;
        this.sailing = sailing;
    }
}
