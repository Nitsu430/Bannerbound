package com.bannerbound.antiquity.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
import com.bannerbound.antiquity.social.AntiquityThoughts;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * Leisure goal (GROG_PLAN.md Phase 4): an idle adult citizen walks to the nearest claimed-chunk
 * {@link FermentationTroughBlockEntity} holding a finished grog serving (within SEARCH_RADIUS), sips
 * for ~3.5s with an audible gulp at the start and halfway (so it reads as drinking rather than
 * staring), then takes the serving and gains a positive {@link AntiquityThoughts#ENJOYED_GROG}
 * thought plus ~4s of ambient Slowness I as a tipsy flavour debuff. Grog is entirely an Antiquity
 * system: this goal is attached to Core's {@link CitizenEntity} through the generic
 * {@code CitizenGoalRegistry} (registered in {@code BannerboundAntiquity} setup), so Core never
 * references grog.
 *
 * <p>Modelled on Core's {@code ConversationGoal}: the settlement-wide block-entity scan runs only on
 * think ticks with an extra SCAN_INTERVAL throttle on top, and every attempt (success or failure)
 * rolls a random cooldown of 5-12 in-game minutes (COOLDOWN_MIN..MAX) so a settlement never forms a
 * drinking heartbeat. Must be registered at priority 3 (the leisure tier, alongside
 * ConversationGoal) - NOT 4: a priority-4 goal cannot preempt the already-running priority-4
 * SettlementPatrolGoal, so at 4 an idle citizen would patrol endlessly and rarely drink; at 3 a
 * running work goal still holds MOVE, so drinks happen off-shift. Skipped for kids, passengers, and
 * while a settlement crisis is active. DRINK_REACH_SQ is a generous 2.5 blocks squared so a citizen
 * the pathfinder parks just shy of the trough starts drinking instead of shuffling/re-pathing in
 * place; the walk gives up after WALK_TIMEOUT ticks, and the trough is re-validated every tick in
 * case it breaks or another citizen drains it before we arrive.
 */
public class GrogDrinkGoal extends Goal {
    private static final int SEARCH_RADIUS = 24;
    private static final double DRINK_REACH_SQ = 6.25;
    private static final int DRINK_DURATION = 70;
    private static final int WALK_TIMEOUT = 200;
    private static final int COOLDOWN_MIN = 6_000;
    private static final int COOLDOWN_MAX = 14_400;
    private static final int SCAN_INTERVAL = 40;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private BlockPos targetTrough;
    private int cooldown = 0;
    private int scanCooldown = 0;
    private int ticksRunning = 0;
    private int drinkTicks = 0;
    private boolean drinking = false;

    public GrogDrinkGoal(CitizenEntity citizen, double speedModifier) {
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

        BlockPos trough = findTrough(sl, settlement);
        if (trough == null) return false;
        this.targetTrough = trough;
        return true;
    }

    @Override
    public void start() {
        ticksRunning = 0;
        drinkTicks = 0;
        drinking = false;
        if (targetTrough != null) {
            citizen.getNavigation().moveTo(
                targetTrough.getX() + 0.5, targetTrough.getY(), targetTrough.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return targetTrough != null;
    }

    @Override
    public void tick() {
        if (targetTrough == null || !(citizen.level() instanceof ServerLevel sl)) return;
        ticksRunning++;

        if (!(sl.getBlockEntity(targetTrough) instanceof FermentationTroughBlockEntity)) {
            targetTrough = null;
            return;
        }

        if (drinking) {
            faceTrough();
            drinkTicks--;
            if (drinkTicks == DRINK_DURATION / 2) {
                playSip(sl);
            }
            if (drinkTicks <= 0) {
                if (FermentationTroughBlock.takeServing(sl, targetTrough)) {
                    onDrank(sl);
                }
                targetTrough = null;
            }
            return;
        }

        double d2 = citizen.position().distanceToSqr(
            targetTrough.getX() + 0.5, targetTrough.getY() + 0.5, targetTrough.getZ() + 0.5);
        if (d2 <= DRINK_REACH_SQ) {
            if (!FermentationTroughBlock.hasReadyServing(sl, targetTrough)) {
                targetTrough = null;
                return;
            }
            citizen.getNavigation().stop();
            faceTrough();
            drinking = true;
            drinkTicks = DRINK_DURATION;
            playSip(sl);
        } else if (citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(
                targetTrough.getX() + 0.5, targetTrough.getY(), targetTrough.getZ() + 0.5, speedModifier);
        }
        if (ticksRunning > WALK_TIMEOUT) {
            targetTrough = null;
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
        targetTrough = null;
        drinking = false;
        drinkTicks = 0;
        cooldown = rollCooldown();
    }

    private void onDrank(ServerLevel sl) {
        long now = sl.getGameTime();
        citizen.getThoughts().add(AntiquityThoughts.ENJOYED_GROG, null, now, sl.random);
        citizen.recomputeHappiness();
        citizen.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 0, true, false));
    }

    private void playSip(ServerLevel sl) {
        if (targetTrough == null) return;
        sl.playSound(null, targetTrough, SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL,
            0.6F, 0.9F + sl.random.nextFloat() * 0.2F);
    }

    private void faceTrough() {
        if (targetTrough == null) return;
        citizen.getLookControl().setLookAt(
            targetTrough.getX() + 0.5, targetTrough.getY() + 0.5, targetTrough.getZ() + 0.5);
    }

    @Nullable
    private BlockPos findTrough(ServerLevel sl, Settlement settlement) {
        BlockPos origin = citizen.blockPosition();
        double bestSq = (double) SEARCH_RADIUS * SEARCH_RADIUS;
        BlockPos best = null;
        for (long packed : settlement.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            LevelChunk chunk = sl.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockEntity be = entry.getValue();
                if (!(be instanceof FermentationTroughBlockEntity)) continue;
                BlockPos p = entry.getKey();
                if (!FermentationTroughBlock.hasReadyServing(sl, p)) continue;
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
