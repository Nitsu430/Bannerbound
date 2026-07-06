package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

/**
 * The trade-courier BLOCKER goal: runs the entire time a citizen is adopted by a
 * {@code TraderSimManager} journey ({@link CitizenEntity#isOnTradeJourney()}) and does nothing but
 * hold MOVE+LOOK. Registered at priority 0 ahead of the combat/flee goals, so while it runs every
 * other movement goal - work, patrol, sleep, conversation, panic, combat - is starved by the
 * strict-less-than preemption rule. The sim drives the citizen externally via
 * {@code getNavigation().moveTo(...)}, which ticks in {@code Mob.serverAiStep} independent of goals;
 * flagless helpers (fence-gate opening) still run. The courier deliberately does not fight back - a
 * caravan is killable cargo, not a combatant. A stale journey (server restart, or the deal resolved
 * while the citizen was unloaded) is detected in {@link #canUse()} and cleared, releasing the citizen
 * back to its own AI instead of freezing it forever under a flag nobody will clear; {@link #stop()}
 * drops any half-issued path so the resuming stocker AI starts clean.
 */
@ApiStatus.Internal
public final class TradeCourierGoal extends net.minecraft.world.entity.ai.goal.Goal {
    private final CitizenEntity citizen;

    public TradeCourierGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!citizen.isOnTradeJourney()) return false;
        if (com.bannerbound.core.trade.TradeCourierManager.isStaleJourney(citizen)) {
            com.bannerbound.core.trade.TradeCourierManager.clearStale(citizen);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return citizen.isOnTradeJourney();
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
    }
}
