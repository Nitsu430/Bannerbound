package com.bannerbound.core.sim;

import java.util.UUID;

import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.network.SimulationStatePayload;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives the throwaway {@code /bannerbound simulate} crowd-LOD stress test (plan:
 * look-at-our-mod-jaunty-hinton.md). Static singleton, one session at a time: on start it spawns a
 * bounded pool of real near-band {@link CitizenEntity} (the genuine "workers"), then broadcasts a
 * tiny {@link SimulationStatePayload} once per second so the client can render a deterministic
 * decorative crowd (generated client-side from the session seed) plus a believed-vs-rendered HUD,
 * and discards everything when the duration elapses.
 *
 * <p>The point it proves: a thousands-strong believed population costs the server only a handful of
 * synced numbers + a bounded entity count, so TPS barely moves. Real spawns are capped at
 * min(realBudget, believedPopulation, MAX_REAL_BUDGET=64) so the rendered/entity count stays bounded
 * while the believed number scales freely.
 *
 * <p>tickAll is wired into ResearchEvents.onServerTick. computeRadius bounds the crowd to the
 * settlement's claimed footprint (chebyshev extent from the town-hall chunk, clamped to 32..192,
 * default 48 when nothing is claimed).
 */
public final class SimulationManager {
    private static final int MAX_REAL_BUDGET = 64;

    private static SimulationSession active;
    private static int tickCounter = 0;

    private SimulationManager() {
    }

    public static boolean isActive() { return active != null; }

    public static int start(MinecraftServer server, Settlement s, int believedPopulation,
                            int realBudget, int durationSeconds) {
        if (server == null || s == null) return 0;
        ServerLevel level = server.overworld();
        BlockPos townHall = s.townHallPos();
        if (townHall == null) return 0;

        // Clear any prior session first (discards its entities) so real budgets don't stack.
        stop(server, false);

        int radius = computeRadius(s, townHall);
        long seed = s.id().getMostSignificantBits() ^ (level.getGameTime() * 0x9E3779B97F4A7C15L);
        long endTick = level.getGameTime() + (long) durationSeconds * 20L;
        SimulationSession session = new SimulationSession(s.id(), townHall, radius,
            believedPopulation, seed, endTick, s.age().ordinal());

        int want = Math.min(Math.min(realBudget, believedPopulation), MAX_REAL_BUDGET);
        for (int i = 0; i < want; i++) {
            CitizenEntity e = ImmigrationManager.spawnSimCitizen(level, s);
            if (e != null) session.spawned.add(e.getUUID());
        }

        active = session;
        tickCounter = 0;
        broadcast(server, true);
        return session.spawned.size();
    }

    public static void tickAll(MinecraftServer server) {
        if (active == null || server == null) return;
        ServerLevel level = server.overworld();
        if (level.getGameTime() >= active.endGameTick) {
            stop(server, true);
            return;
        }
        if (++tickCounter % 20 == 0) {
            broadcast(server, true);
        }
    }

    public static void stop(MinecraftServer server, boolean notifyClients) {
        SimulationSession session = active;
        active = null;
        if (session == null) return;
        if (server != null) {
            ServerLevel level = server.overworld();
            for (UUID id : session.spawned) {
                Entity e = level.getEntity(id);
                if (e != null) e.discard();
            }
            if (notifyClients) {
                broadcastStopped(server, session);
            }
        }
    }

    private static int computeRadius(Settlement s, BlockPos townHall) {
        ChunkPos thc = new ChunkPos(townHall);
        int maxCheb = 0;
        for (long packed : s.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            int cheb = Math.max(Math.abs(cp.x - thc.x), Math.abs(cp.z - thc.z));
            if (cheb > maxCheb) maxCheb = cheb;
        }
        int radius = (maxCheb + 1) * 16;
        if (s.claimedChunks().isEmpty()) radius = 48;
        return Math.max(32, Math.min(192, radius));
    }

    private static int countLivingReal(ServerLevel level, SimulationSession session) {
        int n = 0;
        for (UUID id : session.spawned) {
            Entity e = level.getEntity(id);
            if (e != null && !e.isRemoved()) n++;
        }
        return n;
    }

    private static void broadcast(MinecraftServer server, boolean activeFlag) {
        SimulationSession session = active;
        if (session == null) return;
        ServerLevel level = server.overworld();
        int remaining = (int) Math.max(0L, session.endGameTick - level.getGameTime());
        float mspt = (float) (server.getAverageTickTimeNanos() / 1_000_000.0);
        SimulationStatePayload payload = new SimulationStatePayload(
            activeFlag,
            session.settlementId.toString(),
            session.townHall.getX(), session.townHall.getY(), session.townHall.getZ(),
            session.radius,
            session.believedPopulation,
            countLivingReal(level, session),
            session.seed,
            remaining,
            mspt,
            session.eraOrdinal,
            true
        );
        sendToAll(server, payload);
    }

    private static void broadcastStopped(MinecraftServer server, SimulationSession session) {
        SimulationStatePayload payload = new SimulationStatePayload(
            false, session.settlementId.toString(),
            session.townHall.getX(), session.townHall.getY(), session.townHall.getZ(),
            session.radius, session.believedPopulation, 0, session.seed, 0, 0f, session.eraOrdinal, true);
        sendToAll(server, payload);
    }

    private static void sendToAll(MinecraftServer server, SimulationStatePayload payload) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
