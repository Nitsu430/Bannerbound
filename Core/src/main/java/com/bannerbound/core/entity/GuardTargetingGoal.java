package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * The guard squad's tactical targeting brain (GUARD_PLAN.md section 10). A target-selector goal (no
 * flags -- it only writes the target field, GuardCombatGoal does the moving and swinging), so it keeps
 * working even when the work brain is throttled at Village+. Every REASSIGN_INTERVAL ticks it picks the
 * best hostile for THIS guard, replacing the naive "nearest" the shared citizen selectors use for
 * militia, so the squad spreads across raiders instead of dog-piling one.
 *
 * <p>Decentralised -- no central manager. Each guard scores every in-band hostile, reading peers' live
 * getTarget() for the spread term; staggered scans (cooldown seeded from entity id) let the assignment
 * settle over a couple of passes rather than the whole squad scanning in lockstep. Score terms:
 * - Spread (-) per OTHER guard already on the target -> the squad fans out;
 * - Objective (+, capped) for hostiles near the banner / town hall -> defend the core;
 * - Retaliation (+) for whoever is actively damaging THIS guard (the license from GuardCombatEvents),
 *   which usually outranks fanning out;
 * - Threat (+) for ranged kiters -> pin the dangerous, slippery ones;
 * - Finish (+) for targets below 40% health -> gang up to put a wounded raider down;
 * - Distance (-) as a tie-break -> don't sprint across town.
 *
 * <p>Enemy players are normally left to the rally player selector (priority 0) and this goal never
 * touches a Player target -- UNLESS that player is this guard's live retaliation attacker, in which
 * case this goal owns the fight like any raider's. The retaliation target is added to the candidate
 * list even when out-of-band, so a guard always answers its own assailant.
 */
@ApiStatus.Internal
public class GuardTargetingGoal extends Goal {
    private static final int REASSIGN_INTERVAL = 12;
    private static final double SCAN_RADIUS = 28.0;
    private static final double SCAN_HEIGHT = 10.0;

    private static final double SPREAD_WEIGHT = 6.0;
    private static final double THREAT_BONUS = 5.0;
    private static final double FINISH_BONUS = 4.0;
    private static final double RETALIATION_BONUS = 8.0;
    private static final double OBJECTIVE_WEIGHT = 0.15;
    private static final double OBJECTIVE_CAP = 6.0;
    private static final double DIST_WEIGHT = 0.08;

    private final CitizenEntity citizen;
    private int cooldown;

    public GuardTargetingGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        // No setFlags: this goal must not claim MOVE/LOOK or it fights GuardCombatGoal for the flags.
        this.cooldown = citizen.getId() % REASSIGN_INTERVAL;
    }

    @Override
    public boolean canUse() {
        return citizen.isGuard() && citizen.level() instanceof ServerLevel;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (--cooldown > 0) return;
        cooldown = REASSIGN_INTERVAL;
        if (citizen.level() instanceof ServerLevel sl) reassign(sl);
    }

    private void reassign(ServerLevel sl) {
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        LivingEntity retaliation = citizen.guardRetaliationTarget(sl);
        if (citizen.getTarget() instanceof Player p && !citizen.isGuardRetaliationTarget(p)) return;

        AABB box = citizen.getBoundingBox().inflate(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS);
        List<LivingEntity> hostiles = new ArrayList<>();
        List<CitizenEntity> peers = new ArrayList<>();
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == citizen) continue;
            if (e instanceof CitizenEntity c && c.isGuard() && c.getSettlement() == s) {
                peers.add(c);
            } else if (citizen.isHostileToMe(e) && GuardCombatGoal.withinDefenseBand(s, e.blockPosition())) {
                hostiles.add(e);
            }
        }
        if (retaliation != null && !hostiles.contains(retaliation)) {
            hostiles.add(retaliation);
        }

        if (hostiles.isEmpty()) {
            if (citizen.getTarget() != null) citizen.setTarget(null);
            return;
        }

        BlockPos hub = s.hasTownHall() ? s.townHallPos() : s.bannerPos();
        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (LivingEntity h : hostiles) {
            double score = 0.0;
            int coverage = 0;
            for (CitizenEntity p : peers) {
                if (p.getTarget() == h) coverage++;
            }
            score -= coverage * SPREAD_WEIGHT;
            if (hub != null) {
                double dHub = Math.sqrt(h.distanceToSqr(hub.getX() + 0.5, hub.getY(), hub.getZ() + 0.5));
                score += Math.max(0.0, OBJECTIVE_CAP - dHub * OBJECTIVE_WEIGHT);
            }
            if (h == retaliation) score += RETALIATION_BONUS;
            if (h instanceof CombatantCitizen cc && cc.prefersRanged()) score += THREAT_BONUS;
            if (h.getMaxHealth() > 0 && h.getHealth() < h.getMaxHealth() * 0.4f) score += FINISH_BONUS;
            score -= Math.sqrt(citizen.distanceToSqr(h)) * DIST_WEIGHT;
            if (score > bestScore) { bestScore = score; best = h; }
        }
        if (best != null && best != citizen.getTarget()) citizen.setTarget(best);
    }
}
