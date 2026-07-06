package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.barbarian.BarbarianCampManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Raid muster (GUARD_PLAN.md P2): while a barbarian raid besieges the settlement and this guard has
 * no target in detection range, converge on the raid's hub (the town hall / banner the squad is
 * marching on) to intercept -- a raid on the far corner of a big territory no longer goes unanswered
 * just because no guard happened to stand within scan range.
 *
 * <p>A plain Goal (not a WorkGoal) at priority 1, so it runs through the Village+ ambient-brain
 * throttle AND preempts SleepGoal (priority 2): the war horn wakes the watch, so a sleeping guard
 * without the Night Watch policy still gets up and fights when a raid arrives (the policy's value is
 * being already ON the border when it does).
 *
 * <p>Walks in short hops (HOP_BLOCKS) -- one moveTo to a 100-block-away hub truncates at the
 * follow-range search radius and stalls (the outpost-commute lesson). ARRIVE_SQ is set so
 * GuardTargetingGoal's ~28-block scan covers the hub from where muster stops. Ends the moment
 * GuardTargetingGoal hands the guard a target (GuardCombatGoal takes over at priority 0), when the
 * guard gets close enough for its own scan, or when the raid lifts. Yields to environmental panic
 * (fire/lava/freeze) by ending itself -- panic (also priority 1) claims the MOVE flag next tick.
 */
@ApiStatus.Internal
public class GuardMusterGoal extends Goal {
    private static final double ARRIVE_SQ = 20.0 * 20.0;
    private static final int HOP_BLOCKS = 16;
    private static final int REPATH_INTERVAL = 20;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private BlockPos hub;
    private int repathCooldown;

    public GuardMusterGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!citizen.isGuard()) return false;
        if (citizen.getTarget() != null) return false;
        if (citizen.getSettlementId() == null) return false;
        if (!(citizen.level() instanceof ServerLevel)) return false;
        BlockPos target = BarbarianCampManager.activeRaidTarget(citizen.getSettlementId());
        if (target == null) return false;
        if (citizen.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) <= ARRIVE_SQ) {
            return false;
        }
        hub = target;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!citizen.isGuard() || hub == null) return false;
        if (citizen.getTarget() != null) return false;
        if (citizen.isOnFire() || citizen.isInLava() || citizen.isFreezing()) return false;
        if (BarbarianCampManager.activeRaidTarget(citizen.getSettlementId()) == null) return false;
        return citizen.distanceToSqr(hub.getX() + 0.5, hub.getY(), hub.getZ() + 0.5) > ARRIVE_SQ;
    }

    @Override
    public void start() {
        if (citizen.isSleeping()) citizen.stopSleeping();
        repathCooldown = 0;
    }

    @Override
    public void tick() {
        if (hub == null) return;
        if (--repathCooldown > 0 && !citizen.getNavigation().isDone()) return;
        repathCooldown = REPATH_INTERVAL;
        Vec3 here = citizen.position();
        Vec3 to = new Vec3(hub.getX() + 0.5, here.y, hub.getZ() + 0.5);
        Vec3 dir = to.subtract(here);
        double dist = dir.horizontalDistance();
        Vec3 step = dist <= HOP_BLOCKS ? to : here.add(dir.scale(HOP_BLOCKS / dist));
        citizen.getNavigation().moveTo(step.x, step.y, step.z, speedModifier);
    }

    @Override
    public void stop() {
        hub = null;
        citizen.getNavigation().stop();
    }
}
