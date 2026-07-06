package com.bannerbound.antiquity.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.block.StoneCookingPotBlock;
import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
import com.bannerbound.antiquity.social.AntiquityThoughts;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Leisure goal: an idle citizen walks to the nearest {@link StoneCookingPotBlockEntity} in the
 * settlement's claimed chunks that holds a finished stew, eats a serving over ~3.5s with audible
 * bites (draining the pot via {@link StoneCookingPotBlock#takeServing} exactly like a player or the
 * larder would), and comes away with the strong {@link AntiquityThoughts#ENJOYED_STEW} food mood
 * ("I ate a warm stew", +10). Direct sibling of {@code GrogDrinkGoal}: same throttled think-tick
 * scan, leisure priority (3), and a long randomized post-meal cooldown (5-12 in-game minutes,
 * rolled in {@link #stop()}) so a settlement never forms an eating heartbeat. Attached to Core's
 * {@link CitizenEntity} through the generic {@code CitizenGoalRegistry}. Poisoned or unfinished
 * stews are excluded by {@link StoneCookingPotBlock#hasReadyServing}, and no leisure meals happen
 * while a crisis is active.
 */
public class StewEatGoal extends Goal {
    private static final int SEARCH_RADIUS = 24;
    private static final double EAT_REACH_SQ = 6.25;
    private static final int EAT_DURATION = 70;
    private static final int WALK_TIMEOUT = 200;
    private static final int COOLDOWN_MIN = 6_000;
    private static final int COOLDOWN_MAX = 14_400;
    private static final int SCAN_INTERVAL = 40;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private BlockPos targetPot;
    private int cooldown = 0;
    private int scanCooldown = 0;
    private int ticksRunning = 0;
    private int eatTicks = 0;
    private boolean eating = false;

    public StewEatGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) { cooldown--; return false; }
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!citizen.isAiActive()) return false;
        if (citizen.isPassenger() || citizen.isChild()) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (settlement.activeCrisis() != null) return false;
        if (!citizen.isThinkTick()) return false;
        if (scanCooldown > 0) { scanCooldown--; return false; }
        scanCooldown = SCAN_INTERVAL;

        BlockPos pot = findPot(sl, settlement);
        if (pot == null) return false;
        this.targetPot = pot;
        return true;
    }

    @Override
    public void start() {
        ticksRunning = 0;
        eatTicks = 0;
        eating = false;
        if (targetPot != null) {
            citizen.getNavigation().moveTo(
                targetPot.getX() + 0.5, targetPot.getY(), targetPot.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return targetPot != null;
    }

    @Override
    public void tick() {
        if (targetPot == null || !(citizen.level() instanceof ServerLevel sl)) return;
        ticksRunning++;

        if (!(sl.getBlockEntity(targetPot) instanceof StoneCookingPotBlockEntity)) {
            targetPot = null;
            return;
        }

        if (eating) {
            facePot();
            eatTicks--;
            if (eatTicks == EAT_DURATION / 2) {
                playBite(sl);
            }
            if (eatTicks <= 0) {
                if (StoneCookingPotBlock.takeServing(sl, targetPot)) {
                    onEaten(sl);
                }
                targetPot = null;
            }
            return;
        }

        double d2 = citizen.position().distanceToSqr(
            targetPot.getX() + 0.5, targetPot.getY() + 0.5, targetPot.getZ() + 0.5);
        if (d2 <= EAT_REACH_SQ) {
            if (!StoneCookingPotBlock.hasReadyServing(sl, targetPot)) {
                targetPot = null;
                return;
            }
            citizen.getNavigation().stop();
            facePot();
            eating = true;
            eatTicks = EAT_DURATION;
            playBite(sl);
        } else if (citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(
                targetPot.getX() + 0.5, targetPot.getY(), targetPot.getZ() + 0.5, speedModifier);
        }
        if (ticksRunning > WALK_TIMEOUT) {
            targetPot = null;
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
        targetPot = null;
        eating = false;
        eatTicks = 0;
        cooldown = rollCooldown();
    }

    private void onEaten(ServerLevel sl) {
        long now = sl.getGameTime();
        citizen.getThoughts().add(AntiquityThoughts.ENJOYED_STEW, null, now, sl.random);
        citizen.recomputeHappiness();
    }

    private void playBite(ServerLevel sl) {
        if (targetPot == null) return;
        sl.playSound(null, targetPot, SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL,
            0.6F, 0.9F + sl.random.nextFloat() * 0.2F);
    }

    private void facePot() {
        if (targetPot == null) return;
        citizen.getLookControl().setLookAt(
            targetPot.getX() + 0.5, targetPot.getY() + 0.5, targetPot.getZ() + 0.5);
    }

    @Nullable
    private BlockPos findPot(ServerLevel sl, Settlement settlement) {
        BlockPos origin = citizen.blockPosition();
        double bestSq = (double) SEARCH_RADIUS * SEARCH_RADIUS;
        BlockPos best = null;
        for (long packed : settlement.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            LevelChunk chunk = sl.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockEntity be = entry.getValue();
                if (!(be instanceof StoneCookingPotBlockEntity)) continue;
                BlockPos p = entry.getKey();
                if (!StoneCookingPotBlock.hasReadyServing(sl, p)) continue;
                double dsq = origin.distSqr(p);
                if (dsq < bestSq) {
                    bestSq = dsq;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    private int rollCooldown() {
        int span = COOLDOWN_MAX - COOLDOWN_MIN;
        if (span <= 0) return COOLDOWN_MIN;
        if (citizen.level() instanceof ServerLevel sl) {
            return COOLDOWN_MIN + sl.random.nextInt(span + 1);
        }
        return COOLDOWN_MIN;
    }
}
