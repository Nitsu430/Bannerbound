package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;

/**
 * Walks an outpost-assigned worker the long haul from the settlement out to its remote work site,
 * then hands off to the local work/sleep/idle goals once within ARRIVE (12 blocks horizontally).
 * The actual travel -- short vanilla-pathable hops instead of one truncating 128-block moveTo,
 * plus an off-screen abstract-step rescue -- lives in the shared {@link LongHaulWalker}; this goal
 * only owns the lifecycle (when to start/stop) and the arrival hand-off.
 *
 * <p>Registered at priority 2, before SleepGoal (see {@link CitizenEntity#registerGoals}): the
 * shared priority means a worker mid-commute at nightfall isn't preempted into a doomed single
 * 128-block walk to a far outpost bed -- it finishes the hop-walk to the site first, then SleepGoal
 * beds it down on arrival. Inert (one cheap null check) for any citizen without an outpost
 * assignment, since only the outpost work goals ever set {@link CitizenEntity#getOutpostSite()}.
 * liveSite() gates on the site still being a working claim so a fallen/moved outpost ends the
 * commute. canUse starts only on a think tick, spreading the fleet's first pathfinds across ticks.
 * {@link SettlementPatrolGoal} idles the worker around the site once arrived.
 */
@ApiStatus.Internal
public class OutpostCommuteGoal extends Goal {
    private static final double ARRIVE = 12.0;
    private static final double ARRIVE_SQ = ARRIVE * ARRIVE;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private final LongHaulWalker walker = new LongHaulWalker();

    private BlockPos site;

    public OutpostCommuteGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!citizen.isAiActive()) return false;
        BlockPos s = liveSite();
        if (s == null) return false;
        if (horizDistSq(s) <= ARRIVE_SQ) return false;
        if (!citizen.isThinkTick()) return false;
        this.site = s;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos s = liveSite();
        if (s == null || !s.equals(site)) return false;
        return horizDistSq(s) > ARRIVE_SQ;
    }

    @Override
    public void start() {
        walker.reset(citizen);
    }

    @Override
    public void stop() {
        walker.reset(citizen);
        site = null;
    }

    @Override
    public void tick() {
        if (site != null) {
            walker.stepToward(citizen, site, speedModifier, ARRIVE, true);
        }
    }

    private BlockPos liveSite() {
        BlockPos s = citizen.getOutpostSite();
        if (s == null) return null;
        Settlement set = citizen.getSettlement();
        if (set == null || !set.workingClaims().contains(new ChunkPos(s).toLong())) return null;
        return s;
    }

    private double horizDistSq(BlockPos p) {
        double dx = (p.getX() + 0.5) - citizen.getX();
        double dz = (p.getZ() + 0.5) - citizen.getZ();
        return dx * dx + dz * dz;
    }
}
