package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Side-effects fired when a policy is confirmed (activated) or removed (deactivated), plus the
 * per-in-game-hour upkeep called from {@code ImmigrationManager.tickAll}. Only the one-shot
 * compliance deltas live here - Workload Share's -10 and Domestication's -15 are applied once on
 * activation and reversed on deactivation, NOT drained per hour (confirmed design). Every other
 * policy effect is either a passive gate read at its own hook point ({@code settlement.hasPolicy}
 * in a goal's canUse, a yield multiplier, a speed check) or an hourly Thought handled here:
 * {@link #syncPolicyThoughts} reconciles the policy/war happiness thoughts (Nightshift fatigue,
 * Night Watch weariness, Domestication, War weariness, Rallying Speeches) with current state, and
 * is idempotent. It runs both hourly (via {@link #tickHourly}) AND immediately on any policy change
 * ({@code SettlementManager.enactPolicyChange}); the immediate call is essential because the hourly
 * tick short-circuits when no policy is active, so removing the LAST policy would otherwise strand
 * its thought. The Opinionated-Crowd bonus (+25 compliance, -10 leader-resentment) is applied ONLY
 * from the hourly path while its window is open, deliberately kept out of syncPolicyThoughts (which
 * also fires on policy changes) so it is never double-applied.
 */
@ApiStatus.Internal
public final class PolicyEffects {
    private PolicyEffects() {}

    public static final int WORKLOAD_SHARE_COMPLIANCE_DELTA = -10;
    public static final int DOMESTICATION_COMPLIANCE_DELTA = -15;
    public static final int AGRICULTURAL_YIELD_MULTIPLIER = 2;
    public static final double ROADS_SPEED_BONUS = 0.15;
    public static final int NIGHTSHIFT_HAPPINESS_DELTA = -10;
    public static final int DOMESTICATION_HAPPINESS_DELTA = 15;
    public static final int OPINIONATED_BONUS_COMPLIANCE = 25;
    public static final int OPINIONATED_BONUS_RESENTMENT = -10;
    public static final long OPINIONATED_BONUS_DURATION_TICKS = 12_000L;
    public static final int RALLYING_SPEECHES_COMPLIANCE = 10;
    public static final int GLORY_TALES_COMPLIANCE = 20;

    public static void onActivated(MinecraftServer server, Settlement settlement, String policyId) {
        int delta = complianceDelta(policyId);
        if (delta != 0) adjustComplianceForAll(server, settlement, delta);
    }

    public static void onDeactivated(MinecraftServer server, Settlement settlement, String policyId) {
        int delta = complianceDelta(policyId);
        if (delta != 0) adjustComplianceForAll(server, settlement, -delta);
    }

    private static int complianceDelta(String policyId) {
        if (PolicyRegistry.WORKLOAD_SHARE.equals(policyId)) return WORKLOAD_SHARE_COMPLIANCE_DELTA;
        if (PolicyRegistry.DOMESTICATION.equals(policyId)) return DOMESTICATION_COMPLIANCE_DELTA;
        return 0;
    }

    private static void adjustComplianceForAll(MinecraftServer server, Settlement settlement, int delta) {
        if (server == null || settlement == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        for (CitizenEntity c : SettlementManager.allCitizensOf(overworld, settlement)) {
            c.setCompliance(c.getCompliance() + delta);
        }
    }

    public static void tickHourly(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        syncPolicyThoughts(server, settlement);

        long now = overworld.getGameTime();
        boolean opinionatedActive = settlement.isOpinionatedBonusActive(now);
        if (!opinionatedActive && settlement.policyOpinionatedBonusExpiry() > 0L) {
            settlement.setPolicyOpinionatedBonusExpiry(0L);
        }
        if (!opinionatedActive) return;
        for (CitizenEntity c : SettlementManager.allCitizensOf(overworld, settlement)) {
            c.setCompliance(c.getCompliance() + OPINIONATED_BONUS_COMPLIANCE);
            for (java.util.UUID leader : settlement.leaderPlayerIds()) {
                int cur = c.getResentment(leader);
                if (cur > 0) {
                    c.addResentment(leader, Math.max(-cur, OPINIONATED_BONUS_RESENTMENT));
                }
            }
        }
    }

    public static void syncPolicyThoughts(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        boolean nightshift = settlement.hasPolicy(PolicyRegistry.NIGHTSHIFT);
        boolean nightWatch = settlement.hasPolicy(PolicyRegistry.NIGHT_WATCH);
        boolean domestication = settlement.hasPolicy(PolicyRegistry.DOMESTICATION);
        boolean atWar = isAtWar(overworld, settlement);
        boolean rallyingSpeeches = wantsRallyingSpeeches(settlement, atWar);
        for (CitizenEntity c : SettlementManager.allCitizensOf(overworld, settlement)) {
            boolean changed = false;
            boolean wantNightshift = nightshift && c.isEmployed();
            changed |= syncThought(c, com.bannerbound.core.social.ThoughtKind.NIGHTSHIFT_FATIGUE,
                wantNightshift, overworld);
            changed |= syncThought(c, com.bannerbound.core.social.ThoughtKind.NIGHT_WATCH_WEARY,
                nightWatch && c.isGuard(), overworld);
            changed |= syncThought(c, com.bannerbound.core.social.ThoughtKind.DOMESTICATION_HAPPY,
                domestication, overworld);
            changed |= syncThought(c, com.bannerbound.core.social.ThoughtKind.WAR_WEARINESS,
                atWar, overworld);
            changed |= syncRallyingSpeeches(c, rallyingSpeeches, overworld);
            if (changed) c.recomputeHappiness();
        }
    }

    public static void syncWarMoraleNow(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        boolean atWar = isAtWar(overworld, settlement);
        boolean rallyingSpeeches = wantsRallyingSpeeches(settlement, atWar);
        for (CitizenEntity c : SettlementManager.allCitizensOf(overworld, settlement)) {
            boolean changed = syncThought(c, com.bannerbound.core.social.ThoughtKind.WAR_WEARINESS,
                atWar, overworld);
            changed |= syncRallyingSpeeches(c, rallyingSpeeches, overworld);
            if (changed) c.recomputeHappiness();
        }
    }

    private static boolean isAtWar(ServerLevel overworld, Settlement settlement) {
        return DiplomacyManager.isSettlementAtWar(SettlementData.get(overworld), settlement.id());
    }

    private static boolean wantsRallyingSpeeches(Settlement settlement, boolean atWar) {
        return atWar && settlement.hasPolicy(PolicyRegistry.RALLYING_SPEECHES);
    }

    public static void onWarStarted(MinecraftServer server, Settlement settlement, Settlement enemy,
                                    SettlementData.DiplomacyRelation relation) {
        if (server == null || settlement == null || relation == null) return;
        if (!settlement.hasPolicy(PolicyRegistry.GLORY_TALES) || relation.gloryUsedBy(settlement.id())) {
            return;
        }
        ServerLevel overworld = server.overworld();
        for (CitizenEntity c : SettlementManager.allCitizensOf(overworld, settlement)) {
            c.getThoughts().add(com.bannerbound.core.social.ThoughtKind.GLORY_TALES,
                null, overworld.getGameTime(), overworld.getRandom());
            c.setCompliance(c.getCompliance() + GLORY_TALES_COMPLIANCE);
            c.recomputeHappiness();
        }
        relation.setGloryUsedBy(settlement.id());
    }

    private static boolean syncRallyingSpeeches(CitizenEntity c, boolean want, ServerLevel level) {
        boolean has = c.getThoughts().has(com.bannerbound.core.social.ThoughtKind.RALLYING_SPEECHES, null);
        if (want && !has) {
            c.getThoughts().add(com.bannerbound.core.social.ThoughtKind.RALLYING_SPEECHES,
                null, level.getGameTime(), level.getRandom());
            c.setCompliance(c.getCompliance() + RALLYING_SPEECHES_COMPLIANCE);
            return true;
        }
        if (!want && has) {
            boolean removed = c.getThoughts().remove(
                com.bannerbound.core.social.ThoughtKind.RALLYING_SPEECHES, null);
            if (removed) {
                c.setCompliance(c.getCompliance() - RALLYING_SPEECHES_COMPLIANCE);
            }
            return removed;
        }
        return false;
    }

    private static boolean syncThought(CitizenEntity c,
                                       com.bannerbound.core.social.ThoughtKind kind,
                                       boolean want, ServerLevel level) {
        boolean has = c.getThoughts().has(kind, null);
        if (want && !has) {
            c.getThoughts().add(kind, null, level.getGameTime(), level.getRandom());
            return true;
        }
        if (!want && has) {
            return c.getThoughts().remove(kind, null);
        }
        return false;
    }
}
