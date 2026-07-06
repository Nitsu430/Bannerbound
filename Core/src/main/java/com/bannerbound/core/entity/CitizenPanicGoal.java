package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.entity.ai.goal.PanicGoal;

/**
 * Citizen-flavoured panic goal with two hard overrides on vanilla {@link PanicGoal}:
 *
 * <p>1. It yields whenever a brawl retaliation is queued. Vanilla's priority-1 PanicGoal should be
 * preempted by the priority-0 {@link BrawlRetaliationGoal} via the goal-selector flag-replacement
 * rule, but that preemption races the same-tick start of PanicGoal and the citizen kept running
 * instead of swinging back. Overriding {@link #canUse} + {@link #canContinueToUse} to check
 * {@link CitizenEntity#getPendingRetaliationTargetId()} guarantees panic never starts (and any
 * in-progress panic stops) while a counter-swing is pending - the brawl retaliation always wins,
 * whatever the tick interleaving.
 *
 * <p>2. Guards hold the line: being hit by an enemy never makes a guard flee. The yield is
 * surgical - a guard on fire / in lava / freezing still flees the hazard, otherwise a blanket
 * no-panic would walk the watch straight into a lava pool. Only combat-hurt panic is dropped.
 */
@ApiStatus.Internal
public class CitizenPanicGoal extends PanicGoal {
    private final CitizenEntity citizen;

    public CitizenPanicGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        this.citizen = citizen;
    }

    @Override
    public boolean canUse() {
        if (citizen.getPendingRetaliationTargetId() != null) return false;
        if (GuardWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())
                && !citizen.isOnFire() && !citizen.isInLava() && !citizen.isFreezing()) {
            return false;
        }
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (citizen.getPendingRetaliationTargetId() != null) return false;
        return super.canContinueToUse();
    }
}
