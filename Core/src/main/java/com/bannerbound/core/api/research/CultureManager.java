package com.bannerbound.core.api.research;

import java.util.Set;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.data.CultureTreeLoader;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.network.CultureStateSyncPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-settlement engine for the Culture research tree: the mirror of {@link ResearchManager} -
 * same shape, separate state (the culture fields on {@link Settlement}) and a separate datapack
 * ({@link CultureTreeLoader}). Handles start/enqueue (tryEnqueue is a toggle: clicking the active
 * node pauses it with progress preserved, clicking a queued node removes it; enqueueing appends
 * unmet prerequisites first via DFS post-order), per-tick accrual, auto-unlocks, age regression,
 * insight progress banking, and state sync.
 *
 * <p>Mutual exclusion: only ONE research slot is active across both trees at any time, so players
 * cannot stack a science and a culture research to double the per-second drain. tryStart pauses an
 * active science research (progress preserved - switching trees is one click, not a manual
 * unqueue), and promoteFromQueue re-checks the science slot because a science research may have
 * started between queue-add and promotion. ResearchManager runs the same check on its side.
 *
 * <p>Rate model: the active node accrues {@link Settlement#effectiveCulturePerSecond}/20 per tick
 * (x config multiplier); culture is a RATE, not a stockpile - cultureStored still feeds
 * immigration independently. Dormant settlements (all members offline, refreshed at the top of
 * the tick in ResearchEvents) accrue nothing. Era-capped nodes (forceMaxAge) pause with progress
 * preserved. Prerequisites are cross-tree: a culture node may require a science node.
 *
 * <p>Completion runs the SAME unlock-effect pipeline as science (rate deltas, advance_age,
 * unlock_faith_founding, ...), so culture unlocks.features are honoured. Culture unlocks.items
 * ride the SCIENCE state payload's known-item union, so completion and age regression must also
 * rebroadcast ResearchManager state or clients keep items masked until relog. Capacity is shared
 * 1:1 with science (same active/cap counter on both GUI tabs) - change getCapacity alone to
 * diverge. Age regression un-completes nodes above the new age and purges them from the active
 * slot + queue, but applied scalar bonuses from unlocks.features are NOT reversed - same known
 * limitation as the science twin.
 */
public final class CultureManager {
    private static int tickCounter = 0;

    private CultureManager() {
    }

    public static boolean isInsightCompletionEligible(Settlement s, ResearchDefinition def) {
        if (def.minAge().ordinal() > s.age().ordinal()) return false;
        if (def.requiresTribe() && !s.isTribe()) return false;
        if (def.governmentType() != null && def.governmentType() != s.governmentType()) return false;
        for (String prereq : def.prerequisites()) {
            if (!s.hasCompletedResearchEitherTree(prereq)) return false;
        }
        return true;
    }

    public static boolean addInsightProgress(MinecraftServer server, Settlement s,
                                             ResearchDefinition def, double points) {
        if (s.hasCompletedCultureResearch(def.id()) || points <= 0.0) return false;
        s.setCultureResearchProgress(def.id(), s.getCultureResearchProgress(def.id()) + points);
        SettlementData.get(server.overworld()).setDirty();
        if (s.getCultureResearchProgress(def.id()) >= def.cost() && isInsightCompletionEligible(s, def)) {
            completeResearch(server, s, def);
            return true;
        }
        broadcastStateToSettlement(server, s);
        return false;
    }

    public static int getCapacity(Era era) {
        return 1 + era.ordinal();
    }

    public static Set<String> computeUnlockedItems(Settlement settlement) {
        return settlement.computeKnownCultureItems();
    }

    public static void regressResearchAfterAgeChange(MinecraftServer server, Settlement s) {
        int currentAge = s.age().ordinal();
        boolean changed = false;
        java.util.Set<String> toRemove = new java.util.HashSet<>();
        for (String id : s.completedCultureResearches()) {
            ResearchDefinition def = CultureTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > currentAge) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            s.completedCultureResearches().remove(id);
            s.cultureResearchProgress().remove(id);
            changed = true;
        }
        String active = s.activeCultureResearch();
        if (active != null) {
            ResearchDefinition def = CultureTreeLoader.get(active);
            if (def != null && def.minAge().ordinal() > currentAge) {
                s.cultureResearchProgress().remove(active);
                s.setActiveCultureResearch(null);
                changed = true;
            }
        }
        java.util.Iterator<String> qIter = s.cultureResearchQueue().iterator();
        while (qIter.hasNext()) {
            ResearchDefinition def = CultureTreeLoader.get(qIter.next());
            if (def != null && def.minAge().ordinal() > currentAge) {
                qIter.remove();
                changed = true;
            }
        }
        if (changed) {
            s.recomputeKnownItems();
            broadcastStateToSettlement(server, s);
            // Culture unlocks.items ride the SCIENCE payload's known-item union - must refresh it too.
            ResearchManager.broadcastStateToSettlement(server, s);
        }
    }

    public static void initializeAutoUnlocks(MinecraftServer server, Settlement settlement) {
        boolean changed = false;
        for (ResearchDefinition def : CultureTreeLoader.getAll().values()) {
            if (def.autoUnlock() && !settlement.hasCompletedCultureResearch(def.id())) {
                settlement.markCultureResearchComplete(def.id());
                changed = true;
            }
        }
        if (changed && server != null) broadcastStateToSettlement(server, settlement);
    }

    public static void applyAllAutoUnlocks(MinecraftServer server) {
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        for (Settlement s : data.all()) {
            initializeAutoUnlocks(server, s);
        }
        data.setDirty();
    }

    public static ResearchManager.StartResult tryStart(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) return ResearchManager.StartResult.NOT_IN_SETTLEMENT;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return ResearchManager.StartResult.NOT_IN_SETTLEMENT;
        ResearchDefinition def = CultureTreeLoader.get(researchId);
        if (def == null) return ResearchManager.StartResult.UNKNOWN_RESEARCH;
        if (s.hasCompletedCultureResearch(researchId)) return ResearchManager.StartResult.ALREADY_COMPLETE;
        for (String prereq : def.prerequisites()) {
            if (!s.hasCompletedResearchEitherTree(prereq)) return ResearchManager.StartResult.PREREQ_MISSING;
        }
        if (def.minAge().ordinal() > s.age().ordinal()) return ResearchManager.StartResult.AGE_LOCKED;
        if (ResearchManager.isEraCapped(def)) return ResearchManager.StartResult.AGE_LOCKED;
        if (s.activeResearch() != null) {
            s.setActiveResearch(null);
            ResearchManager.broadcastStateToSettlement(server, s);
        }
        s.cultureResearchQueue().remove(researchId);
        s.setActiveCultureResearch(researchId);
        s.clearCultureSuggestions(researchId);
        com.bannerbound.core.api.settlement.SettlementManager.broadcastSuggestionState(server, s);
        data.setDirty();
        broadcastStateToSettlement(server, s);
        return ResearchManager.StartResult.OK;
    }

    public static ResearchManager.EnqueueResult tryEnqueue(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) return ResearchManager.EnqueueResult.NOT_IN_SETTLEMENT;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return ResearchManager.EnqueueResult.NOT_IN_SETTLEMENT;
        ResearchDefinition def = CultureTreeLoader.get(researchId);
        if (def == null) return ResearchManager.EnqueueResult.UNKNOWN_RESEARCH;
        if (s.hasCompletedCultureResearch(researchId)) return ResearchManager.EnqueueResult.ALREADY_COMPLETE;

        if (researchId.equals(s.activeCultureResearch())) {
            s.setActiveCultureResearch(null);
            promoteFromQueue(s);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return ResearchManager.EnqueueResult.OK_REMOVED;
        }
        if (s.cultureResearchQueue().contains(researchId)) {
            s.cultureResearchQueue().remove(researchId);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return ResearchManager.EnqueueResult.OK_REMOVED;
        }
        if (def.minAge().ordinal() > s.age().ordinal()) return ResearchManager.EnqueueResult.AGE_LOCKED;
        if (ResearchManager.isEraCapped(def)) return ResearchManager.EnqueueResult.AGE_LOCKED;

        java.util.List<String> chain = new java.util.ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        if (!buildPrereqChain(researchId, s, chain, visited)) {
            return ResearchManager.EnqueueResult.AGE_LOCKED;
        }
        for (String id : chain) {
            if (s.hasCompletedCultureResearch(id)) continue;
            if (id.equals(s.activeCultureResearch())) continue;
            if (s.cultureResearchQueue().contains(id)) continue;
            s.cultureResearchQueue().add(id);
        }
        if (s.activeCultureResearch() == null && s.activeResearch() == null
                && !s.cultureResearchQueue().isEmpty()) {
            promoteFromQueue(s);
        }
        data.setDirty();
        broadcastStateToSettlement(server, s);
        return ResearchManager.EnqueueResult.OK;
    }

    private static boolean buildPrereqChain(String researchId, Settlement s,
                                             java.util.List<String> out, java.util.Set<String> visited) {
        if (visited.contains(researchId)) return true;
        visited.add(researchId);
        ResearchDefinition def = CultureTreeLoader.get(researchId);
        if (def == null) return true;
        if (def.minAge().ordinal() > s.age().ordinal()) return false;
        if (ResearchManager.isEraCapped(def)) return false;
        for (String prereq : def.prerequisites()) {
            if (s.hasCompletedResearchEitherTree(prereq)) continue;
            if (!buildPrereqChain(prereq, s, out, visited)) return false;
        }
        out.add(researchId);
        return true;
    }

    private static void promoteFromQueue(Settlement s) {
        // Mutual exclusion: a science research may have started since queue-add - hold off.
        if (s.activeResearch() != null) return;
        int i = 0;
        while (i < s.cultureResearchQueue().size()) {
            String next = s.cultureResearchQueue().get(i);
            if (s.hasCompletedCultureResearch(next)) {
                s.cultureResearchQueue().remove(i);
                continue;
            }
            ResearchDefinition d = CultureTreeLoader.get(next);
            if (d == null) {
                s.cultureResearchQueue().remove(i);
                continue;
            }
            boolean ageOk = d.minAge().ordinal() <= s.age().ordinal() && !ResearchManager.isEraCapped(d);
            boolean prereqsOk = true;
            for (String prereq : d.prerequisites()) {
                if (!s.hasCompletedResearchEitherTree(prereq)) { prereqsOk = false; break; }
            }
            if (ageOk && prereqsOk) {
                s.cultureResearchQueue().remove(i);
                s.setActiveCultureResearch(next);
                return;
            }
            i++;
        }
    }

    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        SettlementData data = SettlementData.get(overworld);
        tickCounter++;
        boolean broadcastTick = (tickCounter % 20 == 0);
        boolean anyChange = false;

        for (Settlement s : data.all()) {
            if (s.isDormant()) continue;
            boolean completedBanked = false;
            for (java.util.Map.Entry<String, Double> progress
                    : new java.util.ArrayList<>(s.cultureResearchProgress().entrySet())) {
                ResearchDefinition banked = CultureTreeLoader.get(progress.getKey());
                if (banked != null && progress.getValue() >= banked.cost()
                        && !ResearchManager.isEraCapped(banked)
                        && isInsightCompletionEligible(s, banked)) {
                    completeResearch(server, s, banked);
                    completedBanked = true;
                }
            }
            if (completedBanked) anyChange = true;
            String active = s.activeCultureResearch();
            if (active == null) continue;
            ResearchDefinition def = CultureTreeLoader.get(active);
            if (def == null) {
                s.setActiveCultureResearch(null);
                anyChange = true;
                continue;
            }
            if (ResearchManager.isEraCapped(def)) {
                s.setActiveCultureResearch(null);
                promoteFromQueue(s);
                anyChange = true;
                continue;
            }
            double current = s.getCultureResearchProgress(active);
            double increment = s.effectiveCulturePerSecond(overworld) / 20.0
                * com.bannerbound.core.Config.RESEARCH_SPEED_MULTIPLIER.get();
            current += increment;
            if (current >= def.cost()) {
                completeResearch(server, s, def);
                anyChange = true;
            } else {
                s.setCultureResearchProgress(active, current);
            }
            if (broadcastTick) broadcastStateToSettlement(server, s);
        }
        if (anyChange) data.setDirty();
    }

    public static boolean forceUnresearch(MinecraftServer server, Settlement settlement,
                                          ResearchDefinition def) {
        boolean removed = settlement.completedCultureResearches().remove(def.id());
        boolean clearedProgress = settlement.cultureResearchProgress().remove(def.id()) != null;
        if (def.id().equals(settlement.activeCultureResearch())) {
            settlement.setActiveCultureResearch(null);
        }
        if (removed || clearedProgress) {
            settlement.recomputeKnownItems();
            broadcastStateToSettlement(server, settlement);
            return true;
        }
        return false;
    }

    private static void completeResearch(MinecraftServer server, Settlement s, ResearchDefinition def) {
        s.markCultureResearchComplete(def.id());
        s.setActiveCultureResearch(null);
        BannerboundCore.LOGGER.info("Settlement {} completed culture research {}",
            s.name(), def.id());
        ResearchManager.applyUnlockEffects(server, s, def);
        promoteFromQueue(s);
        com.bannerbound.core.crisis.CrisisManager.onResearchCompleted(server, s, def.id());
        com.bannerbound.core.codex.CodexManager.onResearchCompleted(server, s, def.id(), true);
        broadcastStateToSettlement(server, s);
        // Culture unlocks.items ride the SCIENCE payload's known-item union - must refresh it too.
        ResearchManager.broadcastStateToSettlement(server, s);
    }

    public static void broadcastStateToSettlement(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        CultureStateSyncPayload payload = buildStatePayload(server.overworld(), s);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) PacketDistributor.sendToPlayer(m, payload);
        }
    }

    public static void sendStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        CultureStateSyncPayload payload = s == null
            ? emptyStatePayload()
            : buildStatePayload(server.overworld(), s);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static CultureStateSyncPayload buildStatePayload(ServerLevel level, Settlement s) {
        return new CultureStateSyncPayload(
            new java.util.ArrayList<>(s.completedCultureResearches()),
            s.activeCultureResearch() == null ? "" : s.activeCultureResearch(),
            CultureStateSyncPayload.flattenProgress(s.cultureResearchProgress()),
            s.effectiveCulturePerSecond(level),
            getCapacity(s.age()),
            new java.util.ArrayList<>(s.cultureResearchQueue()),
            InsightManager.counterEntries(s, InsightManager.TreeType.CULTURE,
                CultureTreeLoader.getAll().values()),
            InsightManager.firedNodeIds(s.firedInsights(), InsightManager.TreeType.CULTURE)
        );
    }

    private static CultureStateSyncPayload emptyStatePayload() {
        return new CultureStateSyncPayload(
            java.util.List.of(), "", java.util.List.of(), 0.0,
            getCapacity(Era.ANCIENT), java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
}
