package com.bannerbound.antiquity.craft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.network.CarpentryActionPayload;
import com.bannerbound.antiquity.network.OpenCarpentrySawPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;
import com.bannerbound.antiquity.workshop.Cost;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * Server-authoritative driver for the carpenter's-table saw minigame. Holds one in-flight session
 * per player (keyed by UUID; server thread only), opens the client minigame via
 * OpenCarpentrySawPayload, and on a validated COMPLETE has the table output its whole build list
 * while consuming only the materials it cost. Stroke count scales with the total materials the
 * batch consumes (units x per-unit cost across every list entry), clamped to [MIN_STROKES,
 * MAX_STROKES] so a big batch saws longer without getting tedious; batch size is the only lever
 * and is never scored. The minigame is non-skill: no quality roll, no anti-reroll forfeit, and a
 * cancel or disconnect leaves the wood budget and build list exactly as they were. While a session
 * is open the table is claimed through WorkBlockLocks (the same lock crafter citizens honor, so an
 * NPC skips the table mid-minigame); every exit path -- complete, cancel, disconnect, table broken
 * (abortSessionAt) -- must release that lock. COMPLETE is validated by MinigameGuard reach and
 * elapsed-time checks and fires the "woodworking_sawed" Chronicle tutorial step. handleAction also
 * services REMOVE_QUEUE, a sessionless direct build-list edit with its own reach/lock checks.
 */
@ApiStatus.Internal
public final class Carpentry {
    private Carpentry() {
    }

    private static final int MIN_STROKES = 4;
    private static final int MAX_STROKES = 28;

    private record Session(BlockPos pos, int strokes, long startTime) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void startSawing(ServerPlayer player, BlockPos pos, WoodworkingTableBlockEntity be) {
        int strokes = strokesFor(be);
        SESSIONS.put(player.getUUID(),
            new Session(pos.immutable(), strokes, player.serverLevel().getGameTime()));
        com.bannerbound.core.api.workshop.WorkBlockLocks.lock(pos, player.getUUID());
        PacketDistributor.sendToPlayer(player, new OpenCarpentrySawPayload(pos, strokes));
    }

    private static int strokesFor(WoodworkingTableBlockEntity be) {
        int materials = 0;
        for (WoodworkingTableBlockEntity.ListEntry e : be.getBuildList()) {
            for (com.bannerbound.antiquity.workshop.Cost c : e.costs()) {
                materials += e.units() * c.perUnit();
            }
        }
        return Math.max(MIN_STROKES, Math.min(MAX_STROKES, MIN_STROKES + materials / 2));
    }

    public static void handleAction(ServerPlayer player, CarpentryActionPayload payload) {
        if (payload.action() == CarpentryActionPayload.REMOVE_QUEUE) {
            BlockPos pos = payload.pos();
            if (!player.level().isLoaded(pos)) return;
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return;
            if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) return;
            if (player.serverLevel().getBlockEntity(pos) instanceof WoodworkingTableBlockEntity be) {
                if (be.removeEntryAt(payload.index())) {
                    player.serverLevel().playSound(null, pos,
                        net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
                }
            }
            return;
        }

        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos().equals(payload.pos())) return;
        BlockPos sessionPos = session.pos();
        ServerLevel level = player.serverLevel();
        if (payload.action() == CarpentryActionPayload.COMPLETE
                && MinigameGuard.stationInReach(player, sessionPos)
                && MinigameGuard.elapsedOk(player, session.startTime(), session.strokes(), 4)
                && level.getBlockEntity(sessionPos) instanceof WoodworkingTableBlockEntity be) {
            be.completeAndOutput(level);
            level.playSound(null, sessionPos, BannerboundAntiquity.SAW_DONE_SOUND.get(),
                SoundSource.BLOCKS, 0.9F, 1.0F);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                sessionPos.getX() + 0.5, sessionPos.getY() + 1.05, sessionPos.getZ() + 0.5,
                18, 0.5, 0.15, 0.4, 0.02);
            com.bannerbound.core.codex.CodexManager.onCustom(player, "woodworking_sawed", "");
        }
        com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(sessionPos, player.getUUID());
        SESSIONS.remove(player.getUUID());
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            com.bannerbound.core.api.workshop.WorkBlockLocks.unlock(session.pos(), player.getUUID());
        }
    }

    public static void abortSessionAt(BlockPos pos) {
        SESSIONS.values().removeIf(s -> s.pos().equals(pos));
        com.bannerbound.core.api.workshop.WorkBlockLocks.forceUnlock(pos);
    }
}
