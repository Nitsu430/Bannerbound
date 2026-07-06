package com.bannerbound.antiquity.craft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
import com.bannerbound.antiquity.network.MasonryActionPayload;
import com.bannerbound.antiquity.network.OpenMasonChiselPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative driver for the mason's-bench chisel-strike minigame -- the stone analogue of
 * {@link Carpentry}. Holds one in-flight session per player keyed on player UUID (SESSIONS, server
 * thread only), opens the client minigame, and on completion outputs the whole build list while
 * consuming only the stone it cost. Strike count scales with the total base stone the batch consumes,
 * clamped to [MIN_STRIKES, MAX_STRIKES]. The minigame is non-skill, so there's no quality roll and no
 * forfeit: a cancel leaves the budget + list exactly as they were. A REMOVE_QUEUE action is a direct
 * in-world edit of the bench list with no chisel session involved. The bench pos is work-locked for
 * the session's duration and released on completion, cancel, disconnect, or bench break.
 */
@ApiStatus.Internal
public final class Masonry {
    private Masonry() {
    }

    private static final int MIN_STRIKES = 4;
    private static final int MAX_STRIKES = 24;

    private record Session(BlockPos pos, int strikes, long startTime) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    public static void startChiseling(ServerPlayer player, BlockPos pos, MasonsBenchBlockEntity be) {
        int strikes = strikesFor(be);
        SESSIONS.put(player.getUUID(),
            new Session(pos.immutable(), strikes, player.serverLevel().getGameTime()));
        com.bannerbound.core.api.workshop.WorkBlockLocks.lock(pos, player.getUUID());
        PacketDistributor.sendToPlayer(player, new OpenMasonChiselPayload(pos, strikes));
    }

    private static int strikesFor(MasonsBenchBlockEntity be) {
        int materials = 0;
        for (MasonsBenchBlockEntity.ListEntry e : be.getBuildList()) {
            materials += e.units() * e.baseCost();
        }
        return Math.max(MIN_STRIKES, Math.min(MAX_STRIKES, MIN_STRIKES + materials / 2));
    }

    public static void handleAction(ServerPlayer player, MasonryActionPayload payload) {
        if (payload.action() == MasonryActionPayload.REMOVE_QUEUE) {
            BlockPos pos = payload.pos();
            if (!player.level().isLoaded(pos)) return;
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return;
            if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) return;
            if (player.serverLevel().getBlockEntity(pos) instanceof MasonsBenchBlockEntity be) {
                if (be.removeEntryAt(payload.index())) {
                    player.serverLevel().playSound(null, pos,
                        SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
                }
            }
            return;
        }

        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos().equals(payload.pos())) return;
        BlockPos sessionPos = session.pos();
        ServerLevel level = player.serverLevel();
        if (payload.action() == MasonryActionPayload.COMPLETE
                && MinigameGuard.stationInReach(player, sessionPos)
                && MinigameGuard.elapsedOk(player, session.startTime(), session.strikes(), 4)
                && level.getBlockEntity(sessionPos) instanceof MasonsBenchBlockEntity be) {
            be.completeAndOutput(level);
            level.playSound(null, sessionPos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                sessionPos.getX() + 0.5, sessionPos.getY() + 1.05, sessionPos.getZ() + 0.5,
                18, 0.5, 0.15, 0.4, 0.02);
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
