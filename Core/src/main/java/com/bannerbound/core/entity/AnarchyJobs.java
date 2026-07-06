package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.job.CitizenJobRegistry;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.WorkstationUnlocks;

/**
 * Gatherer-job catalogue + the settlement's labor-priority maths. With no government to assign work,
 * a settlement's citizens employ themselves - but only into the gatherer roles, the self-directed jobs
 * that scan the world for resources (ForesterWorkGoal, FisherWorkGoal, ForagerWorkGoal). The ordered
 * roles (digger/farmer, driven by Foreman's-Rod orders) and logistics/herder roles need direction and
 * stay manual.
 *
 * Single source of truth for "which jobs are gatherers" and for turning the settlement's player-set
 * priority list into weighted target head-counts that AnarchyJobDistributor staffs toward. The Job-tab
 * switch list and the server's switch-request guard read the same gatherer set so everything agrees.
 * Built-in gatherers rank first in a fixed order; registry gatherers (added via CitizenJobRegistry)
 * append after them sorted by anarchyOrder. A registry gatherer marked obsoletedByUnit drops out of
 * the unlocked set once its successor unit is researched, so the distributor re-skills its holders
 * toward the successor (e.g. spear -> rod fisher).
 *
 * weightedTargets uses linear weights by priority position bounded by each job's laborCap (-1 = no
 * limit), handing workers out one at a time to the best weight/(held+1) ratio (the
 * Webster/Sainte-Lague divisor method): a capped job's surplus cascades to the other gatherers, and if
 * every enabled job is capped the remaining workers stay unemployed and are re-checked next tick.
 */
@ApiStatus.Internal
public final class AnarchyJobs {
    private static final Set<String> BUILTIN_GATHERER_IDS = Set.of(
        ForesterWorkGoal.JOB_TYPE_ID,
        FisherWorkGoal.JOB_TYPE_ID,
        ForagerWorkGoal.JOB_TYPE_ID);

    private static final String[] BUILTIN_ORDER = {
        ForesterWorkGoal.JOB_TYPE_ID,
        FisherWorkGoal.JOB_TYPE_ID,
        ForagerWorkGoal.JOB_TYPE_ID,
    };

    private AnarchyJobs() {
    }

    public static boolean isGathererJob(@Nullable String id) {
        if (id == null) return false;
        if (BUILTIN_GATHERER_IDS.contains(id)) return true;
        CitizenJobRegistry.JobDef d = CitizenJobRegistry.byId(id);
        return d != null && d.gatherer();
    }

    private static List<String> gathererOrder() {
        List<String> out = new ArrayList<>(BUILTIN_ORDER.length + 2);
        for (String j : BUILTIN_ORDER) out.add(j);
        List<CitizenJobRegistry.JobDef> defs = new ArrayList<>();
        for (CitizenJobRegistry.JobDef d : CitizenJobRegistry.all()) {
            if (d.gatherer()) defs.add(d);
        }
        defs.sort(Comparator.comparingInt(CitizenJobRegistry.JobDef::anarchyOrder));
        for (CitizenJobRegistry.JobDef d : defs) {
            if (!out.contains(d.jobTypeId())) out.add(d.jobTypeId());
        }
        return out;
    }

    public static List<String> unlockedGathererJobs(Settlement s) {
        List<String> out = new ArrayList<>();
        for (String job : gathererOrder()) {
            String flag = WorkstationUnlocks.flagForWorkstation(job);
            if (flag != null && !ResearchManager.hasFlag(s, flag)) continue;
            if (isObsoleted(s, job)) continue;
            out.add(job);
        }
        return out;
    }

    public static boolean isObsoleted(Settlement s, String job) {
        CitizenJobRegistry.JobDef d = CitizenJobRegistry.byId(job);
        if (d == null || d.obsoletedByUnit() == null) return false;
        return ResearchManager.hasFlag(s, WorkstationUnlocks.flagForUnit(d.obsoletedByUnit()));
    }

    public static List<String> orderedUnlockedGatherers(Settlement s) {
        List<String> unlocked = unlockedGathererJobs(s);
        List<String> out = new ArrayList<>(unlocked.size());
        for (String j : s.laborPriority()) {
            if (unlocked.contains(j) && !out.contains(j)) out.add(j);
        }
        for (String j : unlocked) {
            if (!out.contains(j)) out.add(j);
        }
        return out;
    }

    public static List<String> enabledOrderedGatherers(Settlement s) {
        List<String> out = new ArrayList<>();
        for (String j : orderedUnlockedGatherers(s)) {
            if (!s.isLaborJobDisabled(j)) out.add(j);
        }
        return out;
    }

    public static Map<String, Integer> weightedTargets(Settlement s, int poolSize) {
        List<String> jobs = enabledOrderedGatherers(s);
        Map<String, Integer> targets = new LinkedHashMap<>();
        int k = jobs.size();
        if (k == 0) return targets;
        int[] res = new int[k];
        int[] weight = new int[k];
        int[] cap = new int[k];
        for (int i = 0; i < k; i++) {
            weight[i] = k - i;
            cap[i] = s.laborCap(jobs.get(i));
        }
        for (int n = 0; n < poolSize; n++) {
            int best = -1;
            double bestScore = -1.0;
            for (int i = 0; i < k; i++) {
                if (cap[i] >= 0 && res[i] >= cap[i]) continue;
                double score = (double) weight[i] / (res[i] + 1);
                if (score > bestScore) { bestScore = score; best = i; }
            }
            if (best < 0) break;
            res[best]++;
        }
        for (int i = 0; i < k; i++) targets.put(jobs.get(i), res[i]);
        return targets;
    }
}
