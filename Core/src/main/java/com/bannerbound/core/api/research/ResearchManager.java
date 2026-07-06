package com.bannerbound.core.api.research;

import com.bannerbound.core.entity.CitizenEntity;

import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.ImmigrationManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.network.ResearchStateSyncPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-settlement research engine (server side): ticks science into the active research,
 * handles start/enqueue/complete, fires unlock effects, and regresses completed research when
 * a settlement's age drops below a node's min_age. Every mutation broadcasts a
 * {@link ResearchStateSyncPayload} to settlement members so the client mirror
 * ({@link com.bannerbound.core.client.ClientResearchState}) updates immediately; the payload's
 * known-item list is one flat union of science AND culture unlocks.
 * <p>
 * Core rules: there is ONE research slot shared across the science and culture trees --
 * starting either side pauses the other with progress preserved in its progress map (a
 * right-click toggle-off is the same pause). Right-click enqueues a node plus its missing
 * prerequisites in dependency order; un-queueing a prerequisite cascade-removes orphaned
 * dependents (repeated until stable), while temporarily age-locked entries stay queued.
 * Prerequisites and {@link #hasFlag} queries are satisfied by EITHER tree -- culture unlocks
 * are first-class (CULTURE_PLAN.md). Insight points bank while a node is still illegal and
 * complete it the moment it becomes eligible. Dormant settlements (all members offline) accrue
 * nothing. The /gamerule forceMaxAge cap ({@link #isEraCapped}) gates start, enqueue and queue
 * promotion, pauses an in-progress capped node, and is hard-backstopped inside the advance_age
 * handler.
 * <p>
 * {@link #applyUnlockEffects} is the shared effect pipeline for science, culture
 * (CultureManager) and faith completions. set_tool_age is honoured in both the features and
 * flags JSON arrays so authors need not memorize which array a key belongs in; tool age and
 * era only ever move forward. {@link #applyAllAutoUnlocks} is idempotent (safe on every
 * datapack sync) and retroactively recomputes tool age from all completed research. A
 * completion also re-skills loaded citizens whose registry job declares obsoletedByUnit once
 * the successor unit's flag is researched (e.g. spear fishers -> rod fishers).
 * <p>
 * Heraldry points are EARNED totals across both trees and are never spent away: available =
 * earned - banner layers in the current design (see FactionBanner). {@link #forceUnresearch}
 * is debug-only -- cumulative rate/tool-age/era effects are NOT reverted. New one-shot
 * features extend {@link #applyFeature}'s dispatch; new persistent flags just appear in a
 * node's unlocks.flags and are queried via {@link #hasFlag}.
 */
public final class ResearchManager {
    private static int tickCounter = 0;

    private ResearchManager() {
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
        if (s.hasCompletedResearch(def.id()) || points <= 0.0) return false;
        s.setResearchProgress(def.id(), s.getResearchProgress(def.id()) + points);
        SettlementData.get(server.overworld()).setDirty();
        if (s.getResearchProgress(def.id()) >= def.cost() && isInsightCompletionEligible(s, def)) {
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
        return settlement.computeKnownItems();
    }

    public static boolean hasFlag(Settlement settlement, String flag) {
        if (settlement == null) return false;
        for (String id : settlement.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.unlocksFlags().contains(flag)) {
                return true;
            }
        }
        for (String id : settlement.completedCultureResearches()) {
            ResearchDefinition def =
                com.bannerbound.core.api.research.data.CultureTreeLoader.get(id);
            if (def != null && def.unlocksFlags().contains(flag)) {
                return true;
            }
        }
        return false;
    }

    @Deprecated
    public static boolean hasFlagEitherTree(Settlement settlement, String flag) {
        return hasFlag(settlement, flag);
    }

    public static int heraldryPointsEarned(Settlement settlement) {
        if (settlement == null) return 0;
        int total = 0;
        for (String id : settlement.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null) total += def.heraldryPoints();
        }
        for (String id : settlement.completedCultureResearches()) {
            ResearchDefinition def =
                com.bannerbound.core.api.research.data.CultureTreeLoader.get(id);
            if (def != null) total += def.heraldryPoints();
        }
        return total;
    }

    public static void initializeAutoUnlocks(MinecraftServer server, Settlement settlement) {
        boolean changed = false;
        SettlementData data = server == null ? null : SettlementData.get(server.overworld());
        for (ResearchDefinition def : ResearchTreeLoader.getAll().values()) {
            if (def.autoUnlock() && !settlement.hasCompletedResearch(def.id())) {
                settlement.markResearchComplete(def.id());
                if (data != null) data.markGloballyResearched(def.id());
                applyUnlockEffects(server, settlement, def);
                changed = true;
            }
        }
        if (changed && server != null) {
            broadcastStateToSettlement(server, settlement);
        }
    }

    public static void applyAllAutoUnlocks(MinecraftServer server) {
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        for (Settlement s : data.all()) {
            initializeAutoUnlocks(server, s);
            recomputeToolAge(s);
        }
    }

    private static void recomputeToolAge(Settlement s) {
        for (String id : s.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def == null) continue;
            for (String feat : def.unlocksFeatures()) {
                if (feat.startsWith(SET_TOOL_AGE_PREFIX)) {
                    applySetToolAge(s, feat);
                }
            }
            for (String flag : def.unlocksFlags()) {
                if (flag.startsWith(SET_TOOL_AGE_PREFIX)) {
                    applySetToolAge(s, flag);
                }
            }
        }
    }

    private static void applySetToolAge(Settlement s, String key) {
        String ageId = key.substring(SET_TOOL_AGE_PREFIX.length()).trim();
        ToolAge incoming = ToolAgeLoader.get(ageId);
        if (incoming == null) return;
        ToolAge current = s.getCurrentToolAge().isEmpty()
            ? null : ToolAgeLoader.get(s.getCurrentToolAge());
        if (current == null || incoming.order() > current.order()) {
            s.setCurrentToolAge(ageId);
        }
    }

    public static void forceComplete(MinecraftServer server, Settlement settlement, ResearchDefinition def) {
        if (settlement.hasCompletedResearch(def.id())) {
            return;
        }
        completeResearch(server, settlement, def);
    }

    public static void forceCompleteWithPrereqs(MinecraftServer server, Settlement settlement,
                                                  ResearchDefinition def) {
        forceCompleteWithPrereqs(server, settlement, def, new java.util.HashSet<>());
    }

    private static void forceCompleteWithPrereqs(MinecraftServer server, Settlement settlement,
                                                   ResearchDefinition def,
                                                   java.util.Set<String> visited) {
        if (settlement.hasCompletedResearch(def.id())) return;
        if (!visited.add(def.id())) return;
        for (String prereqId : def.prerequisites()) {
            if (settlement.hasCompletedResearch(prereqId)) continue;
            ResearchDefinition prereq = ResearchTreeLoader.get(prereqId);
            if (prereq != null) {
                forceCompleteWithPrereqs(server, settlement, prereq, visited);
            }
        }
        completeResearch(server, settlement, def);
    }

    public static boolean forceUnresearch(MinecraftServer server, Settlement settlement,
                                            ResearchDefinition def) {
        boolean removed = settlement.completedResearches().remove(def.id());
        boolean clearedProgress = settlement.researchProgress().remove(def.id()) != null;
        if (def.id().equals(settlement.activeResearch())) {
            settlement.setActiveResearch(null);
        }
        if (removed || clearedProgress) {
            settlement.recomputeKnownItems();
            broadcastStateToSettlement(server, settlement);
            return true;
        }

        return false;
    }

    public enum StartResult {
        OK,
        UNKNOWN_RESEARCH,
        ALREADY_COMPLETE,
        PREREQ_MISSING,
        AGE_LOCKED,
        NOT_IN_SETTLEMENT
    }

    public enum EnqueueResult {
        OK,
        OK_REMOVED,
        UNKNOWN_RESEARCH,
        ALREADY_COMPLETE,
        AGE_LOCKED,
        NOT_IN_SETTLEMENT
    }

    public static StartResult tryStart(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return StartResult.NOT_IN_SETTLEMENT;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) {
            return StartResult.NOT_IN_SETTLEMENT;
        }
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        if (def == null) {
            return StartResult.UNKNOWN_RESEARCH;
        }
        if (s.hasCompletedResearch(researchId)) {
            return StartResult.ALREADY_COMPLETE;
        }
        for (String prereq : def.prerequisites()) {
            if (!s.hasCompletedResearchEitherTree(prereq)) {
                return StartResult.PREREQ_MISSING;
            }
        }
        if (def.requiresTribe() && !s.isTribe()) {
            return StartResult.AGE_LOCKED;
        }
        if (def.minAge().ordinal() > s.age().ordinal()) {
            return StartResult.AGE_LOCKED;
        }
        if (isEraCapped(def)) {
            return StartResult.AGE_LOCKED;
        }
        if (s.activeCultureResearch() != null) {
            s.setActiveCultureResearch(null);
            com.bannerbound.core.api.research.CultureManager.broadcastStateToSettlement(server, s);
        }
        s.researchQueue().remove(researchId);
        s.setActiveResearch(researchId);
        s.clearScienceSuggestions(researchId);
        SettlementManager.broadcastSuggestionState(server, s);
        data.setDirty();
        broadcastStateToSettlement(server, s);
        return StartResult.OK;
    }

    public static EnqueueResult tryEnqueue(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return EnqueueResult.NOT_IN_SETTLEMENT;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) {
            return EnqueueResult.NOT_IN_SETTLEMENT;
        }
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        if (def == null) {
            return EnqueueResult.UNKNOWN_RESEARCH;
        }
        if (s.hasCompletedResearch(researchId)) {
            return EnqueueResult.ALREADY_COMPLETE;
        }
        if (researchId.equals(s.activeResearch())) {
            s.setActiveResearch(null);
            pruneOrphanedQueueEntries(s);
            promoteFromQueue(s);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return EnqueueResult.OK_REMOVED;
        }
        if (s.researchQueue().contains(researchId)) {
            s.researchQueue().remove(researchId);
            pruneOrphanedQueueEntries(s);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return EnqueueResult.OK_REMOVED;
        }
        if (def.minAge().ordinal() > s.age().ordinal()) {
            return EnqueueResult.AGE_LOCKED;
        }
        if (isEraCapped(def)) {
            return EnqueueResult.AGE_LOCKED;
        }

        java.util.List<String> chain = new java.util.ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        if (!buildPrereqChain(researchId, s, chain, visited)) {
            return EnqueueResult.AGE_LOCKED;
        }

        for (String id : chain) {
            if (s.hasCompletedResearch(id)) continue;
            if (id.equals(s.activeResearch())) continue;
            if (s.researchQueue().contains(id)) continue;
            s.researchQueue().add(id);
        }

        if (s.activeResearch() == null && s.activeCultureResearch() == null
                && !s.researchQueue().isEmpty()) {
            promoteFromQueue(s);
        }

        data.setDirty();
        broadcastStateToSettlement(server, s);
        return EnqueueResult.OK;
    }

    private static boolean buildPrereqChain(String researchId, Settlement s,
                                             java.util.List<String> out, java.util.Set<String> visited) {
        if (visited.contains(researchId)) return true;
        visited.add(researchId);
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        if (def == null) return true;
        if (def.minAge().ordinal() > s.age().ordinal()) return false;
        if (isEraCapped(def)) return false;
        if (def.requiresTribe() && !s.isTribe()) return false;
        for (String prereq : def.prerequisites()) {
            if (s.hasCompletedResearchEitherTree(prereq)) continue;
            if (!buildPrereqChain(prereq, s, out, visited)) return false;
        }
        out.add(researchId);
        return true;
    }

    private static void pruneOrphanedQueueEntries(Settlement s) {
        boolean changed;
        do {
            changed = false;
            java.util.List<String> queue = s.researchQueue();
            for (int i = 0; i < queue.size(); ) {
                String id = queue.get(i);
                ResearchDefinition d = ResearchTreeLoader.get(id);
                if (d == null) { queue.remove(i); changed = true; continue; }
                boolean prereqsReachable = true;
                for (String prereq : d.prerequisites()) {
                    if (s.hasCompletedResearchEitherTree(prereq)) continue;
                    if (prereq.equals(s.activeResearch())) continue;
                    if (queue.contains(prereq)) continue;
                    prereqsReachable = false;
                    break;
                }
                if (!prereqsReachable) { queue.remove(i); changed = true; continue; }
                i++;
            }
        } while (changed);
    }

    private static void promoteFromQueue(Settlement s) {
        // One research slot across science+culture: never promote while a culture research is active.
        if (s.activeCultureResearch() != null) return;
        int i = 0;
        while (i < s.researchQueue().size()) {
            String next = s.researchQueue().get(i);
            if (s.hasCompletedResearch(next)) {
                s.researchQueue().remove(i);
                continue;
            }
            ResearchDefinition d = ResearchTreeLoader.get(next);
            if (d == null) {
                s.researchQueue().remove(i);
                continue;
            }
            boolean ageOk = d.minAge().ordinal() <= s.age().ordinal() && !isEraCapped(d);
            boolean prereqsOk = true;
            for (String prereq : d.prerequisites()) {
                if (!s.hasCompletedResearchEitherTree(prereq)) { prereqsOk = false; break; }
            }
            if (ageOk && prereqsOk) {
                s.researchQueue().remove(i);
                s.setActiveResearch(next);
                return;
            }
            i++;
        }
    }

    public static void regressResearchAfterAgeChange(MinecraftServer server, Settlement s) {
        int currentAge = s.age().ordinal();
        boolean changed = false;
        java.util.Set<String> toRemove = new java.util.HashSet<>();
        for (String id : s.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > currentAge) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            s.completedResearches().remove(id);
            s.researchProgress().remove(id);
            changed = true;
        }
        String active = s.activeResearch();
        if (active != null) {
            ResearchDefinition def = ResearchTreeLoader.get(active);
            if (def != null && def.minAge().ordinal() > currentAge) {
                s.researchProgress().remove(active);
                s.setActiveResearch(null);
                changed = true;
            }
        }
        java.util.Iterator<String> qIter = s.researchQueue().iterator();
        while (qIter.hasNext()) {
            String id = qIter.next();
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > currentAge) {
                qIter.remove();
                changed = true;
            }
        }
        if (changed) {
            s.recomputeKnownItems();
            broadcastStateToSettlement(server, s);
            SettlementManager.broadcastEraState(server);
            for (UUID memberId : s.members()) {
                ServerPlayer m = server.getPlayerList().getPlayer(memberId);
                if (m != null) {
                    com.bannerbound.core.event.UnknownItemBlocker.unequipUnknownGear(m);
                }
            }
        }
    }

    public static void tickAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        SettlementData data = SettlementData.get(overworld);
        tickCounter++;
        boolean broadcastTick = (tickCounter % 20 == 0);
        boolean anyChange = false;

        for (Settlement s : data.all()) {
            if (s.isDormant()) {
                continue;
            }
            boolean completedBanked = false;
            for (java.util.Map.Entry<String, Double> progress
                    : new java.util.ArrayList<>(s.researchProgress().entrySet())) {
                ResearchDefinition banked = ResearchTreeLoader.get(progress.getKey());
                if (banked != null && progress.getValue() >= banked.cost()
                        && !isEraCapped(banked)
                        && isInsightCompletionEligible(s, banked)) {
                    completeResearch(server, s, banked);
                    completedBanked = true;
                }
            }
            if (completedBanked) anyChange = true;
            String active = s.activeResearch();
            if (active == null) {
                continue;
            }
            ResearchDefinition def = ResearchTreeLoader.get(active);
            if (def == null) {
                s.setActiveResearch(null);
                anyChange = true;
                continue;
            }
            if (isEraCapped(def)) {
                s.setActiveResearch(null);
                promoteFromQueue(s);
                anyChange = true;
                continue;
            }
            double current = s.getResearchProgress(active);
            double increment = s.effectiveSciencePerSecond() / 20.0
                * com.bannerbound.core.Config.RESEARCH_SPEED_MULTIPLIER.get();
            current += increment;
            if (current >= def.cost()) {
                completeResearch(server, s, def);
                anyChange = true;
            } else {
                s.setResearchProgress(active, current);
            }
            if (broadcastTick) {
                broadcastStateToSettlement(server, s);
            }
        }
        if (anyChange) {
            data.setDirty();
        }
    }

    private static void completeResearch(MinecraftServer server, Settlement s, ResearchDefinition def) {
        s.markResearchComplete(def.id());
        SettlementData.get(server.overworld()).markGloballyResearched(def.id());
        s.setActiveResearch(null);
        applyUnlockEffects(server, s, def);
        migrateObsoletedJobs(server, s);
        for (UUID memberId : s.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.sendSystemMessage(Component.translatable("bannerbound.research.completed_personal", def.name())
                    .withStyle(ChatFormatting.GREEN));
                member.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
                    net.minecraft.sounds.SoundSource.MASTER, 1.0f, 1.0f);
            }
        }
        applyProgressedRecentlyThought(server, s);
        promoteFromQueue(s);
        com.bannerbound.core.crisis.CrisisManager.onResearchCompleted(server, s, def.id());
        com.bannerbound.core.codex.CodexManager.onResearchCompleted(server, s, def.id(), false);
        broadcastStateToSettlement(server, s);
    }

    private static void applyProgressedRecentlyThought(MinecraftServer server, Settlement s) {
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
            net.minecraft.world.entity.Entity raw = overworld.getEntity(c.entityId());
            if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) continue;
            citizen.getThoughts().add(
                com.bannerbound.core.social.ThoughtKind.PROGRESSED_RECENTLY,
                null, now, overworld.random);
            citizen.recomputeHappiness();
        }
    }

    private static void migrateObsoletedJobs(MinecraftServer server, Settlement s) {
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
            net.minecraft.world.entity.Entity raw = overworld.getEntity(c.entityId());
            if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) continue;
            String job = citizen.getJobType();
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef d =
                com.bannerbound.core.api.job.CitizenJobRegistry.byId(job);
            if (d == null || d.obsoletedByUnit() == null) continue;
            if (!hasFlag(s, com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForUnit(d.obsoletedByUnit()))) {
                continue;
            }
            String successor = com.bannerbound.core.api.settlement.WorkstationUnlocks
                .workstationForUnit(d.obsoletedByUnit());
            if (successor != null && !successor.equals(job)) {
                citizen.setJobType(successor);
            }
        }
    }

    public static void applyUnlockEffects(MinecraftServer server, Settlement s, ResearchDefinition def) {
        for (String feature : def.unlocksFeatures()) {
            applyFeature(server, s, feature);
        }
        for (String flag : def.unlocksFlags()) {
            if (flag.startsWith(SET_TOOL_AGE_PREFIX)) {
                applyFeature(server, s, flag);
            }
            com.bannerbound.core.codex.CodexManager.onFlagGained(server, s, flag);
        }
        SettlementManager.broadcastEraState(server);
    }

    private static final String SCIENCE_DELTA_PREFIX = "bannerbound.science_per_second_delta:";
    private static final String FOOD_RATE_DELTA_PREFIX = "bannerbound.food_per_second_delta:";
    private static final String CULTURE_RATE_DELTA_PREFIX = "bannerbound.culture_per_second_delta:";
    private static final String FOOD_CAPACITY_DELTA_PREFIX = "bannerbound.food_capacity_delta:";
    private static final String CULTURE_CAPACITY_DELTA_PREFIX = "bannerbound.culture_capacity_delta:";
    private static final String CITIZEN_SPEED_DELTA_PREFIX = "bannerbound.citizen_speed_delta:";
    private static final String SET_TOOL_AGE_PREFIX = "bannerbound.set_tool_age:";
    private static final String ADVANCE_AGE_PREFIX = "bannerbound.advance_age:";
    private static final String UNLOCK_FAITH_FOUNDING = "bannerbound.unlock_faith_founding";

    public static Era forceMaxAge(MinecraftServer server) {
        Era[] vals = Era.values();
        Era last = vals[vals.length - 1];
        if (server == null) {
            return last;
        }
        return server.getGameRules()
            .getRule(com.bannerbound.core.chat.BannerboundGameRules.FORCE_MAX_AGE)
            .era();
    }

    public static Era forceMaxAge() {
        return forceMaxAge(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
    }

    public static boolean isEraCapped(ResearchDefinition def) {
        if (def == null) {
            return false;
        }
        Era cap = forceMaxAge();
        if (def.minAge().ordinal() > cap.ordinal()) {
            return true;
        }
        for (String feature : def.unlocksFeatures()) {
            if (feature.startsWith(ADVANCE_AGE_PREFIX)) {
                Era target = Era.fromName(feature.substring(ADVANCE_AGE_PREFIX.length()).trim());
                if (target != null && target.ordinal() > cap.ordinal()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void applyFeature(MinecraftServer server, Settlement s, String featureKey) {
        if (featureKey.equals(UNLOCK_FAITH_FOUNDING)) {
            com.bannerbound.core.api.faith.FaithManager.unlockFounding(server, s);
            return;
        }
        if (featureKey.startsWith(SCIENCE_DELTA_PREFIX)) {
            String valueStr = featureKey.substring(SCIENCE_DELTA_PREFIX.length()).trim();
            try {
                double delta = Double.parseDouble(valueStr);
                double newRate = Math.max(0.0, s.sciencePerSecond() + delta);
                s.setSciencePerSecond(newRate);
                for (UUID memberId : s.members()) {
                    ServerPlayer m = server.getPlayerList().getPlayer(memberId);
                    if (m != null) {
                        m.sendSystemMessage(Component.translatable(
                                "bannerbound.research.science_changed",
                                String.format("%+.2f", delta), String.format("%.2f", newRate))
                            .withStyle(ChatFormatting.AQUA));
                    }
                }
            } catch (NumberFormatException ex) {
                BannerboundCore.LOGGER.warn("Bad science delta value '{}' in feature {}", valueStr, featureKey);
            }
            return;
        }
        if (featureKey.startsWith(FOOD_CAPACITY_DELTA_PREFIX)) {
            applyCapacityDelta(server, s, featureKey, FOOD_CAPACITY_DELTA_PREFIX, true);
            return;
        }
        if (featureKey.startsWith(CULTURE_CAPACITY_DELTA_PREFIX)) {
            applyCapacityDelta(server, s, featureKey, CULTURE_CAPACITY_DELTA_PREFIX, false);
            return;
        }
        if (featureKey.startsWith(FOOD_RATE_DELTA_PREFIX)) {
            applyRateDelta(s, featureKey, FOOD_RATE_DELTA_PREFIX, true);
            return;
        }
        if (featureKey.startsWith(CULTURE_RATE_DELTA_PREFIX)) {
            applyRateDelta(s, featureKey, CULTURE_RATE_DELTA_PREFIX, false);
            return;
        }
        if (featureKey.startsWith(CITIZEN_SPEED_DELTA_PREFIX)) {
            String valueStr = featureKey.substring(CITIZEN_SPEED_DELTA_PREFIX.length()).trim();
            try {
                double delta = Double.parseDouble(valueStr);
                s.addBonusCitizenSpeed(delta);
            } catch (NumberFormatException ex) {
                BannerboundCore.LOGGER.warn("Bad citizen speed delta value '{}' in feature {}", valueStr, featureKey);
            }
            return;
        }
        if (featureKey.startsWith(SET_TOOL_AGE_PREFIX)) {
            String ageId = featureKey.substring(SET_TOOL_AGE_PREFIX.length()).trim();
            ToolAge incoming = ToolAgeLoader.get(ageId);
            if (incoming == null) {
                BannerboundCore.LOGGER.warn("Unknown tool age '{}' in feature {}", ageId, featureKey);
                return;
            }
            // Upgrade only -- completing a lower-tier node later must not roll the tool age back.
            ToolAge current = s.getCurrentToolAge().isEmpty()
                ? null : ToolAgeLoader.get(s.getCurrentToolAge());
            if (current == null || incoming.order() > current.order()) {
                s.setCurrentToolAge(ageId);
            }
            return;
        }
        if (featureKey.startsWith(ADVANCE_AGE_PREFIX)) {
            String eraKey = featureKey.substring(ADVANCE_AGE_PREFIX.length()).trim();
            Era target = Era.fromName(eraKey);
            if (target == null) {
                BannerboundCore.LOGGER.warn("Unknown era '{}' in feature {}", eraKey, featureKey);
                return;
            }
            // Hard backstop: auto-unlock/command completions bypass the UI gates; never pass the forceMaxAge cap.
            Era cap = forceMaxAge(server);
            if (target.ordinal() > cap.ordinal()) {
                BannerboundCore.LOGGER.info("Era advance to '{}' blocked by forceMaxAge cap '{}'",
                    target.key(), cap.key());
                return;
            }
            // Advance only -- a target at or below the current age is a no-op, never a regression.
            if (target.ordinal() <= s.age().ordinal()) {
                return;
            }
            s.setAge(target);
            SettlementData data = SettlementData.get(server.overworld());
            if (target.ordinal() > data.getWorldAge().ordinal()) {
                data.setWorldAge(target);
            }
            data.setDirty();
            SettlementManager.broadcastEraState(server);

            if (s.townHallPos() != null) {
                SettlementManager.launchCelebrationFireworks(server.overworld(), s.townHallPos(), s, 5,
                    BannerboundCore.getAgeAdvanceSound(target));
            }

            Component announcement = Component.translatable(
                "bannerbound.settlement.advanced_age", s.name(), target.displayName())
                .withStyle(s.identityFormatting());
            server.getPlayerList().broadcastSystemMessage(announcement, false);
            com.bannerbound.core.codex.CodexManager.onEraReached(server, s);
            return;
        }
        BannerboundCore.LOGGER.warn("Unknown research feature: {}", featureKey);
    }

    private static void applyRateDelta(Settlement s, String featureKey, String prefix, boolean isFood) {
        String valueStr = featureKey.substring(prefix.length()).trim();
        try {
            double delta = Double.parseDouble(valueStr);
            if (isFood) {
                double newRate = Math.max(0.0, s.foodPerSecond() + delta);
                s.setFoodPerSecond(newRate);
            } else {
                double newRate = Math.max(0.0, s.culturePerSecond() + delta);
                s.setCulturePerSecond(newRate);
            }
        } catch (NumberFormatException ex) {
            BannerboundCore.LOGGER.warn("Bad rate delta value '{}' in feature {}", valueStr, featureKey);
        }
    }

    private static void applyCapacityDelta(MinecraftServer server, Settlement s, String featureKey,
                                            String prefix, boolean isFood) {
        String valueStr = featureKey.substring(prefix.length()).trim();
        try {
            double delta = Double.parseDouble(valueStr);
            if (isFood) {
                s.addBonusFoodCapacity(delta);
            } else {
                s.addBonusCultureCapacity(delta);
            }
            com.bannerbound.core.api.settlement.ImmigrationManager.broadcastState(server, s);
        } catch (NumberFormatException ex) {
            BannerboundCore.LOGGER.warn("Bad capacity delta value '{}' in feature {}", valueStr, featureKey);
        }
    }

    public static void broadcastStateToSettlement(MinecraftServer server, Settlement s) {
        if (server == null || s == null) {
            return;
        }
        ResearchStateSyncPayload payload = buildStatePayload(s);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                PacketDistributor.sendToPlayer(m, payload);
            }
        }
    }

    public static void sendStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        ResearchStateSyncPayload payload = s == null ? emptyStatePayload() : buildStatePayload(s);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static ResearchStateSyncPayload buildStatePayload(Settlement s) {
        java.util.Set<String> knownItems = computeUnlockedItems(s);
        knownItems.addAll(CultureManager.computeUnlockedItems(s));
        return new ResearchStateSyncPayload(
            new java.util.ArrayList<>(s.completedResearches()),
            s.activeResearch() == null ? "" : s.activeResearch(),
            ResearchStateSyncPayload.flattenProgress(s.researchProgress()),
            s.effectiveSciencePerSecond(),
            getCapacity(s.age()),
            new java.util.ArrayList<>(knownItems),
            new java.util.ArrayList<>(s.researchQueue()),
            InsightManager.counterEntries(s, InsightManager.TreeType.SCIENCE,
                ResearchTreeLoader.getAll().values()),
            InsightManager.firedNodeIds(s.firedInsights(), InsightManager.TreeType.SCIENCE)
        );
    }

    private static ResearchStateSyncPayload emptyStatePayload() {
        return new ResearchStateSyncPayload(
            java.util.List.of(),
            "",
            java.util.List.of(),
            0.0,
            getCapacity(Era.ANCIENT),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of()
        );
    }
}
