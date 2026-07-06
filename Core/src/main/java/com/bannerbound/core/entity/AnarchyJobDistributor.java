package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementManager;

import net.minecraft.server.level.ServerLevel;

/**
 * Settlement-level labor distribution. Runs once per second (from ImmigrationManager.tickAll) and
 * nudges gatherer-eligible citizens toward the weighted target head-counts derived from the
 * settlement's labor-priority list (AnarchyJobs.weightedTargets):
 *
 * - Employ: every unemployed adult is put straight into the most under-staffed job, so fresh
 *   immigrants start working within a second. This runs before re-skill and never thrashes (they had
 *   no job to flip from).
 * - Re-skill: at most ONE over-staffed citizen switches toward an under-staffed job per run, so the
 *   mix converges gradually without job-flip thrash. This is what stops a fast-growing player from
 *   being stuck with (say) six foragers forever: the moment a new gatherer is researched the targets
 *   shift and workers re-skill one per second. A citizen stranded in a now-disabled / no-longer-staffed
 *   gatherer job is moved FIRST (order-dependent) - otherwise the distributor never touches them and
 *   they stay stuck there forever after the player disables that job.
 *
 * Runs in anarchy always; under a government only while Settlement.laborAutoAssign() is left on (a
 * chief / council member can switch it off to assign per-citizen instead). Ordered jobs
 * (digger/farmer/herder/stocker) need work-area setup and stay manual, so their holders are excluded
 * from the distributable pool, as are Village+ ambient-brain citizens (grouped labor) and PINNED
 * citizens (a hand-set manual override the distributor must not touch). Re-skilling uses
 * CitizenEntity.setJobType, which keeps the citizen's drop-off / carry pack (it only clears those on a
 * true unassign), so a re-skilled worker never loses its destination.
 */
@ApiStatus.Internal
public final class AnarchyJobDistributor {
    private AnarchyJobDistributor() {
    }

    public static void tick(ServerLevel sl, Settlement s) {
        if (s == null) return;
        List<CitizenEntity> pool = distributablePool(sl, s);
        if (pool.isEmpty()) return;
        Map<String, Integer> targets = AnarchyJobs.weightedTargets(s, pool.size());
        if (targets.isEmpty()) return;

        Map<String, Integer> current = currentCounts(pool, targets.keySet());

        for (CitizenEntity c : pool) {
            if (c.getJobType() != null) continue;
            String job = mostUnder(targets, current);
            if (job == null) break;
            c.setJobType(job);
            current.put(job, current.getOrDefault(job, 0) + 1);
        }

        String under = mostUnder(targets, current);
        if (under == null) return;
        CitizenEntity mover = firstInUnstaffedGathererJob(pool, targets.keySet());
        if (mover == null) mover = firstInOverTargetJob(pool, targets, current, under);
        if (mover != null) mover.setJobType(under);
    }

    @Nullable
    private static CitizenEntity firstInUnstaffedGathererJob(List<CitizenEntity> pool, Set<String> staffed) {
        for (CitizenEntity c : pool) {
            String j = c.getJobType();
            if (j != null && AnarchyJobs.isGathererJob(j) && !staffed.contains(j)) return c;
        }
        return null;
    }

    @Nullable
    private static CitizenEntity firstInOverTargetJob(List<CitizenEntity> pool, Map<String, Integer> targets,
                                                      Map<String, Integer> current, String under) {
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            String over = e.getKey();
            if (over.equals(under)) continue;
            if (current.getOrDefault(over, 0) <= e.getValue()) continue;
            for (CitizenEntity c : pool) {
                if (over.equals(c.getJobType())) return c;
            }
        }
        return null;
    }

    private static List<CitizenEntity> distributablePool(ServerLevel sl, Settlement s) {
        List<CitizenEntity> out = new ArrayList<>();
        for (CitizenEntity c : SettlementManager.allCitizensOf(sl, s)) {
            if (c.isChild() || c.isPregnant() || c.usesAmbientBrain() || c.isJobPinned()) continue;
            String job = c.getJobType();
            if (job == null || AnarchyJobs.isGathererJob(job)) out.add(c);
        }
        return out;
    }

    private static Map<String, Integer> currentCounts(List<CitizenEntity> pool, Set<String> jobs) {
        Map<String, Integer> counts = new HashMap<>();
        for (String j : jobs) counts.put(j, 0);
        for (CitizenEntity c : pool) {
            String j = c.getJobType();
            if (j != null && counts.containsKey(j)) counts.put(j, counts.get(j) + 1);
        }
        return counts;
    }

    @Nullable
    private static String mostUnder(Map<String, Integer> targets, Map<String, Integer> current) {
        String best = null;
        int bestDeficit = 0;
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            int deficit = e.getValue() - current.getOrDefault(e.getKey(), 0);
            if (deficit > bestDeficit) { bestDeficit = deficit; best = e.getKey(); }
        }
        return best;
    }
}
