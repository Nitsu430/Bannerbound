package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workstation;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Common base for every citizen work goal - owns the scaffolding every concrete goal would otherwise
 * duplicate: the owning {@link CitizenEntity} and pathfinding {@code speedModifier} (exposed as
 * protected fields), the vanilla {@link Goal.Flag#MOVE}+{@link Goal.Flag#LOOK} flags (all current
 * work goals claim both), and the {@link #findAssignment()} resolver (settlement ->
 * workstation-for-this-citizen -> {@link #workstationTypeId()} match -> building-valid -> active
 * toggle; returns null, the universal "yield to patrol" signal, on any miss). Intermediate category
 * abstracts ({@link GathererWorkGoal}, {@link OrderedWorkGoal}, {@link LogisticsWorkGoal}) sit
 * between this and the leaf goals to keep the taxonomy explicit and give shared behaviour a home.
 *
 * <p>Skill curve: {@link #jobSkill()} reads the same per-job XP bucket {@code grantJobXp} writes, on
 * the crafter XP-saturation curve {@code xp/(xp+NPC_XP_HALF)} (0 for a novice, asymptotic to 1).
 * {@link #skilledSpeed()} makes a better worker travel faster (+{@value #SKILL_SPEED_BONUS} at
 * mastery) and {@link #skilledWorkTicks(int)} makes a task quicker (down to
 * x(1-{@value #SKILL_WORK_SPEEDUP})), both with mood riding on top exactly as it scales crafter work
 * speed - mirroring {@code CrafterWorkGoal.skillScaledTicks} so every profession quickens on one
 * curve. skilledWorkTicks divides by the mood multiplier (a happy worker is quicker) and never
 * returns below 1 tick.
 *
 * <p>{@link #canUse()} / {@link #canContinueToUse()} are {@code final} here so no goal can bypass the
 * shared gates; subclasses express only their own start/continue conditions via
 * {@link #canStartWork()} / {@link #canKeepWorking()}, and each goal owns how much stamina its task
 * spends. Shared gates in both: adopted trade courier (the sim owns the citizen), ambient-brain
 * citizens (Village+ labor is grouped/rate-based, not per-citizen A*), AI-inactive (no player
 * nearby), 0 stamina (yield to {@link SettlementPatrolGoal} to rest), poisoned/pregnant/child, a
 * downed faction banner (no banner -> ALL labor halts, anarchy included - the settlement is bound to
 * nothing), settlement rally (total mobilization: every citizen drops work to fight; guards keep
 * defending via the non-WorkGoal GuardCombatGoal), and - for non-anarchy-auto goals - the afternoon
 * social window and any active refusal thought. Anarchy gatherers ({@link #isAnarchyAutoEligible()})
 * work willingly and bypass the social window plus every refusal/strike thought; compliance instead
 * governs whether they consent to a player-requested job switch. Only canUse additionally staggers
 * the (A*-triggering) work scan onto this citizen's think tick so starts spread across ticks; a
 * running job continues every tick.
 *
 * <p>{@link #hasRefusalThought} treats NO_WORK_RIGHT_NOW / NO_WORK_TODAY / NO_WORK_AS_JOB alike: any
 * one pauses the citizen's whole work loop until it expires (refusing one job pauses all work - the
 * simpler behaviour). {@link #isAfternoonGathering} opens a pre-bed social window for the last 2
 * in-game minutes before night (so even a fully-employed settlement gathers before sleep, and
 * ConversationGoal fires once work yields); the Nightshift policy cancels it entirely and Night
 * Watch keeps guards on the beat through dusk.
 */
@ApiStatus.Internal
public abstract class WorkGoal extends Goal {
    protected final CitizenEntity citizen;
    protected final double speedModifier;

    private static final double SKILL_SPEED_BONUS = 0.4;
    private static final float SKILL_WORK_SPEEDUP = 0.45F;

    protected WorkGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    protected final float jobSkill() {
        float xp = citizen.getJobXp(workstationTypeId());
        return xp / (xp + com.bannerbound.core.api.quality.QualityMath.NPC_XP_HALF);
    }

    protected final double skilledSpeed() {
        return speedModifier * (1.0 + SKILL_SPEED_BONUS * jobSkill())
            * citizen.happinessPerformanceMultiplier();
    }

    protected final int skilledWorkTicks(int baseTicks) {
        float mult = 1.0F - SKILL_WORK_SPEEDUP * jobSkill();
        mult /= citizen.happinessPerformanceMultiplier();
        return Math.max(1, Math.round(baseTicks * mult));
    }

    @Override
    public final boolean canUse() {
        if (citizen.isOnTradeJourney()) return false;
        if (citizen.usesAmbientBrain()) return false;
        if (!citizen.isAiActive()) return false;
        if (citizen.isStaminaExhausted()) return false;
        if (citizen.isPoisoned()) return false;
        if (citizen.isPregnant() || citizen.isChild()) return false;
        if (isBannerDown()) return false;
        if (citizen.isSettlementRallying()) return false;
        if (!isAnarchyAuto()) {
            if (isAfternoonGathering(citizen)) return false;
            if (hasRefusalThought(citizen)) return false;
        }
        if (isAnarchyAuto() && citizen.isAnarchyHaulDropOff() && citizen.isHaulFull()) return false;
        if (!citizen.isThinkTick()) return false;
        return canStartWork();
    }

    @Override
    public final boolean canContinueToUse() {
        if (citizen.isOnTradeJourney()) return false;
        if (citizen.usesAmbientBrain()) return false;
        if (citizen.isStaminaExhausted()) return false;
        if (citizen.isPoisoned()) return false;
        if (citizen.isPregnant() || citizen.isChild()) return false;
        if (isBannerDown()) return false;
        if (citizen.isSettlementRallying()) return false;
        if (!isAnarchyAuto()) {
            if (isAfternoonGathering(citizen)) return false;
            if (hasRefusalThought(citizen)) return false;
        }
        if (isAnarchyAuto() && citizen.isAnarchyHaulDropOff() && citizen.isHaulFull()) return false;
        return canKeepWorking();
    }

    private boolean isBannerDown() {
        Settlement s = citizen.getSettlement();
        return s != null && !s.hasFactionBanner();
    }

    private boolean isAnarchyAuto() {
        return isAnarchyAutoEligible() && citizen.isAnarchy();
    }

    protected boolean isAnarchyAutoEligible() {
        return false;
    }

    public static boolean hasRefusalThought(CitizenEntity citizen) {
        com.bannerbound.core.social.Thoughts t = citizen.getThoughts();
        if (t == null) return false;
        if (t.has(com.bannerbound.core.social.ThoughtKind.NO_WORK_RIGHT_NOW, null)) return true;
        if (t.has(com.bannerbound.core.social.ThoughtKind.NO_WORK_TODAY, null)) return true;
        for (com.bannerbound.core.social.Thought th : t.entries()) {
            if (th.kind() == com.bannerbound.core.social.ThoughtKind.NO_WORK_AS_JOB) return true;
        }
        return false;
    }

    public static boolean isAfternoonGathering(CitizenEntity c) {
        if (!(c.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        com.bannerbound.core.api.settlement.Settlement s = c.getSettlement();
        if (s != null && s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHTSHIFT)) {
            return false;
        }
        if (s != null && c.isGuard()
                && s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHT_WATCH)) {
            return false;
        }
        long t = sl.getDayTime() % 24_000L;
        // 10_100 = 12_500 (night start, matches SleepGoal) - 2_400 (the 2 in-game min social window).
        return t >= 10_100L && t < 12_500L;
    }

    protected abstract boolean canStartWork();

    protected abstract boolean canKeepWorking();

    protected abstract String workstationTypeId();

    protected Workstation findAssignment() {
        Settlement s = citizen.getSettlement();
        if (s == null) return null;
        Workstation ws = s.getWorkstationFor(citizen.getUUID());
        if (ws == null) return null;
        if (!workstationTypeId().equals(ws.type())) return null;
        if (!ws.buildingValid()) return null;
        if (!ws.active()) return null;
        return ws;
    }
}
