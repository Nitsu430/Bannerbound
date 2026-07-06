package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.ResearchManager;

/**
 * Hard-coded catalogue of the settlement policies. Policies are NOT data-driven - each one's
 * effect is wired at a specific hook point in code (a goal's canUse, a yield multiplier, an
 * assignment gate, etc.) keyed off {@link Settlement#hasPolicy(String)}. This registry only holds
 * the metadata the UI needs (display keys, category, government restriction) and the unlock flag a
 * research node sets. A policy is AVAILABLE to a settlement when its {@link Policy#governmentType}
 * is null (global) or matches the settlement's current government, AND a completed research
 * (science OR culture) lists the policy's {@link Policy#unlockFlag}, set in JSON via
 * {@code "unlocks": {"policy": ["id"]}} which the loaders fold into {@code "unlock.policy.<id>"}.
 * To add a policy: register it here + a lang triple (name/description/effect) + the effect wiring
 * at its hook point + a research node that unlocks it. See {@code docs/policies.md}.
 *
 * <p>On a Policy record: {@code type} is the {@link PolicyType} category and it fits a typed slot
 * of that type. {@code governmentType} is the SIGNATURE EXCLUSIVITY marker, not a general
 * availability filter: non-null means the policy is exclusive to that government and occupies its
 * single signature slot; null means the policy is global and fits any typed slot of its type
 * (signature policies still carry a real type for their display glyph). Two war policies are
 * mutually exclusive - Rallying Speeches and Glory Tales, see {@link #exclusiveWith}. Notable
 * hook points: PROSPECTING_QUARRY gives quarryworkers a small chance to find common raw ore in
 * natural stone (the deliberately-worse-than-trade scarcity floor for ore-poor starts, MINER_PLAN
 * phase 2) via {@code ProspectingQuarry.tryBonus} from {@code DiggerWorkGoal.mineBlock};
 * NIGHT_WATCH keeps guards awake and patrolling at the cost of the NIGHT_WATCH_WEARY thought via
 * {@code SleepGoal.canUse}, {@code WorkGoal.isAfternoonGathering}, and
 * {@code PolicyEffects.syncPolicyThoughts} (GUARD_PLAN.md).
 */
public final class PolicyRegistry {
    private PolicyRegistry() {}

    public record Policy(
        String id,
        @Nullable Settlement.Government governmentType,
        PolicyType type,
        String nameKey,
        String descriptionKey,
        String effectKey,
        String unlockFlag
    ) {}

    public static final String NIGHTSHIFT = "nightshift";
    public static final String WORKLOAD_SHARE = "workload_share";
    public static final String OPINIONATED_CROWD = "opinionated_crowd";
    public static final String DOMESTICATION = "domestication";
    public static final String AGRICULTURAL_EFFORT = "agricultural_effort";
    public static final String ROADS = "roads";
    public static final String RALLYING_SPEECHES = "rallying_speeches";
    public static final String GLORY_TALES = "glory_tales";
    public static final String PROSPECTING_QUARRY = "prospecting_quarry";
    public static final String NIGHT_WATCH = "night_watch";

    private static final Map<String, Policy> BY_ID = new LinkedHashMap<>();

    private static void register(String id, @Nullable Settlement.Government gov, PolicyType type) {
        BY_ID.put(id, new Policy(
            id, gov, type,
            "bannerbound.policy." + id + ".name",
            "bannerbound.policy." + id + ".description",
            "bannerbound.policy." + id + ".effect",
            "unlock.policy." + id));
    }

    static {
        // Signature policies (government-exclusive) first, then global policies.
        register(WORKLOAD_SHARE, Settlement.Government.CHIEFDOM, PolicyType.ECONOMIC);
        register(OPINIONATED_CROWD, Settlement.Government.COUNCIL, PolicyType.CULTURAL);
        register(NIGHTSHIFT, null, PolicyType.ECONOMIC);
        register(DOMESTICATION, null, PolicyType.CULTURAL);
        register(AGRICULTURAL_EFFORT, null, PolicyType.ECONOMIC);
        register(ROADS, null, PolicyType.CULTURAL);
        register(RALLYING_SPEECHES, null, PolicyType.MILITARISTIC);
        register(GLORY_TALES, null, PolicyType.CULTURAL);
        register(PROSPECTING_QUARRY, null, PolicyType.SCIENTIFIC);
        register(NIGHT_WATCH, null, PolicyType.MILITARISTIC);
    }

    public static boolean isSignature(String policyId) {
        Policy p = get(policyId);
        return p != null && p.governmentType() != null;
    }

    @Nullable
    public static String signaturePolicyFor(Settlement.Government gov) {
        if (gov == null) return null;
        for (Policy p : BY_ID.values()) {
            if (p.governmentType() == gov) return p.id();
        }
        return null;
    }

    @Nullable
    public static Policy get(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static List<Policy> all() {
        return new ArrayList<>(BY_ID.values());
    }

    @Nullable
    public static String exclusiveWith(String policyId) {
        if (RALLYING_SPEECHES.equals(policyId)) return GLORY_TALES;
        if (GLORY_TALES.equals(policyId)) return RALLYING_SPEECHES;
        return null;
    }

    public static boolean matchesGovernment(Settlement settlement, String policyId) {
        Policy p = get(policyId);
        if (p == null || settlement == null) return false;
        return p.governmentType() == null || p.governmentType() == settlement.governmentType();
    }

    public static boolean isAvailable(Settlement settlement, String policyId) {
        Policy p = get(policyId);
        if (p == null || settlement == null) return false;
        if (!matchesGovernment(settlement, policyId)) return false;
        return ResearchManager.hasFlagEitherTree(settlement, p.unlockFlag());
    }

    public static List<String> availableFor(Settlement settlement) {
        List<String> out = new ArrayList<>();
        if (settlement == null) return out;
        for (Policy p : BY_ID.values()) {
            if (isAvailable(settlement, p.id())) out.add(p.id());
        }
        return out;
    }
}
