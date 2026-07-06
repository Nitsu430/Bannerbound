package com.bannerbound.core.api.territory;

import com.bannerbound.core.territory.ChunkClaimCostFile;
import com.bannerbound.core.territory.InventoryItemHelper;
import com.bannerbound.core.territory.TerritoryBiomeResolver;

import com.bannerbound.core.api.territory.data.ChunkClaimCostLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bannerbound.core.faction.ChunkForceLoader;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.network.OpenExpandTerritoryScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Server-side logic for the chunk-claim expansion flow; all validation lives here, NEVER in the
 * client, because the ExpandTerritoryScreen is allowed to lie about what is purchasable -- the
 * server is authoritative. buildScreenPayload assembles the screen snapshot: own + foreign chunks
 * in a WINDOW_RADIUS window (4 -> 9x9 centered on the town hall), per-chunk beauty (base +
 * adjacency + effective), council-vote / chiefdom-suggestion markers, the resolved current tier
 * cost, and a server-evaluated canAfford (items + population + cap). tryClaim re-validates on
 * click, consumes items, claims, force-loads the chunk, increments the per-era expansion counter,
 * broadcasts the global claim sync, and announces in chat.
 *
 * <p>Cost ladder: expansion progress is a single global index. totalExpansionCap sums
 * maxExpansions for every era reached (a settlement advances one era at a time, so advancing only
 * adds the new era's allowance and unused expansions carry forward), and resolveEraTier maps a
 * global index onto the era whose ladder it falls in -- earlier eras are consumed first, so
 * leftover antiquity expansions still cost antiquity prices -- plus the tier within that era; both
 * return null at or past the cap.
 *
 * <p>tryClaim dispatches by government: NONE claims directly from the player's inventory; a
 * Chiefdom chief claims directly (inventory + settlement chests); a Chiefdom non-chief toggles a
 * suggestion marker (no chat -- the marker IS the feedback); Council toggles a vote and claims
 * once the online-member threshold is met, sourcing resources from voters + settlement. There is
 * no chat on vote cast/withdraw (the N/X marker is the feedback), but the "vote retracted, lacking
 * resources" broadcast is kept because it is actionable. executeClaim is the shared path: it
 * re-checks population and cost, consumes via SettlementInventoryHelper (stockpile -> workstation
 * -> voter inventories), claims, and returns false when resources are missing so a council vote
 * retracts. Council votes auto-expire after COUNCIL_VOTE_EXPIRY_MS (5 min, matching
 * SettlementManager). isAdjacentToClaim is inlined to avoid dragging in command-package imports.
 */
public final class TerritoryService {
    private static final int WINDOW_RADIUS = 4;

    private TerritoryService() {}

    private record EraTier(Era era, int tier) {}

    private static int totalExpansionCap(Settlement s) {
        int cap = 0;
        for (Era era : Era.values()) {
            ChunkClaimCostFile f = ChunkClaimCostLoader.get(era.key());
            if (f != null) cap += f.maxExpansions();
            if (era == s.age()) break;
        }
        return cap;
    }

    private static EraTier resolveEraTier(Settlement s, int globalIdx) {
        int acc = 0;
        for (Era era : Era.values()) {
            ChunkClaimCostFile f = ChunkClaimCostLoader.get(era.key());
            int cap = f == null ? 0 : f.maxExpansions();
            if (globalIdx < acc + cap) {
                return new EraTier(era, globalIdx - acc);
            }
            acc += cap;
            if (era == s.age()) break;
        }
        return null;
    }

    private static ChunkClaimCost resolveCost(Settlement s, int globalIdx, ResourceLocation biome) {
        EraTier et = resolveEraTier(s, globalIdx);
        if (et == null) return null;
        ChunkClaimCostFile f = ChunkClaimCostLoader.get(et.era().key());
        if (f == null) return null;
        List<ChunkClaimCost> tiers = f.tiersFor(biome);
        return et.tier() < tiers.size() ? tiers.get(et.tier()) : null;
    }

    public static OpenExpandTerritoryScreenPayload buildScreenPayload(ServerLevel overworld,
                                                                       Settlement settlement,
                                                                       ServerPlayer requester) {
        ChunkPos thChunk = new ChunkPos(settlement.townHallPos());
        long thPacked = thChunk.toLong();

        List<Long> own = new ArrayList<>(settlement.claimedChunks());
        List<Long> foreign = new ArrayList<>();
        SettlementData data = SettlementData.get(overworld);
        for (int dx = -WINDOW_RADIUS; dx <= WINDOW_RADIUS; dx++) {
            for (int dz = -WINDOW_RADIUS; dz <= WINDOW_RADIUS; dz++) {
                long packed = new ChunkPos(thChunk.x + dx, thChunk.z + dz).toLong();
                if (settlement.claimedChunks().contains(packed)) continue;
                Settlement owner = data.getByChunk(packed);
                if (owner != null) foreign.add(packed);
            }
        }

        ResourceLocation biome = TerritoryBiomeResolver.majorityBiome(overworld, settlement);
        int used = settlement.expansionsUsed();
        int totalCap = totalExpansionCap(settlement);
        ChunkClaimCost cur = resolveCost(settlement, used, biome);

        List<String> ids = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int reqPop = 0;
        boolean canAfford = false;
        if (cur != null) {
            reqPop = cur.populationRequired();
            for (ChunkClaimCost.ItemCost ic : cur.items()) {
                ResourceLocation itemRl = BuiltInRegistries.ITEM.getKey(ic.item());
                ids.add(itemRl.toString());
                counts.add(ic.count());
            }
            canAfford = settlement.population() >= reqPop
                && InventoryItemHelper.hasAll(requester, cur.items());
        }

        List<Long> beautyChunks = new ArrayList<>();
        List<Integer> beautyTagIds = new ArrayList<>();
        List<Integer> beautyAdjacency = new ArrayList<>();
        List<Integer> beautyEffective = new ArrayList<>();
        for (int dx = -WINDOW_RADIUS; dx <= WINDOW_RADIUS; dx++) {
            for (int dz = -WINDOW_RADIUS; dz <= WINDOW_RADIUS; dz++) {
                long packed = new ChunkPos(thChunk.x + dx, thChunk.z + dz).toLong();
                com.bannerbound.core.api.settlement.ChunkBeauty beauty =
                    com.bannerbound.core.api.settlement.ChunkBeautyManager.beautyOf(overworld, packed);
                if (beauty != null) {
                    com.bannerbound.core.api.settlement.ChunkBeauty effective =
                        com.bannerbound.core.api.settlement.ChunkBeautyManager
                            .effectiveBeautyOf(overworld, packed);
                    beautyChunks.add(packed);
                    beautyTagIds.add((int) beauty.networkId());
                    beautyAdjacency.add(com.bannerbound.core.api.settlement.ChunkBeautyManager
                        .adjacencyBonus(overworld, packed));
                    beautyEffective.add((int) (effective != null ? effective : beauty).networkId());
                }
            }
        }

        java.util.List<OpenExpandTerritoryScreenPayload.ChunkMarker> votes = new java.util.ArrayList<>();
        java.util.List<OpenExpandTerritoryScreenPayload.ChunkMarker> suggestions = new java.util.ArrayList<>();
        int threshold = 0;
        if (settlement.governmentType() == Settlement.Government.COUNCIL) {
            int onlineNow = SettlementManager.countOnlineMembers(
                overworld.getServer(), settlement);
            threshold = onlineNow <= 1 ? 1 : (onlineNow == 2 ? 2 : (onlineNow + 1) / 2);
            for (java.util.Map.Entry<Long, java.util.LinkedHashMap<java.util.UUID, Long>> e
                    : settlement.allExpansionVotes().entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                votes.add(new OpenExpandTerritoryScreenPayload.ChunkMarker(
                    e.getKey(), new java.util.ArrayList<>(e.getValue().keySet())));
            }
        } else if (settlement.governmentType() == Settlement.Government.CHIEFDOM) {
            for (java.util.Map.Entry<Long, java.util.LinkedHashSet<java.util.UUID>> e
                    : settlement.allExpansionSuggestions().entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                suggestions.add(new OpenExpandTerritoryScreenPayload.ChunkMarker(
                    e.getKey(), new java.util.ArrayList<>(e.getValue())));
            }
        }

        return new OpenExpandTerritoryScreenPayload(
            own, foreign, thPacked,
            settlement.color().ordinal(),
            used,
            totalCap,
            reqPop,
            settlement.population(),
            ids, counts,
            biome == null ? "" : biome.toString(),
            canAfford,
            beautyChunks, beautyTagIds, beautyAdjacency, beautyEffective,
            votes, suggestions, threshold);
    }

    public static void tryClaim(ServerPlayer player, long packedTarget) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        SettlementData data = SettlementData.get(overworld);
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            err(player, "bannerbound.territory.error.no_settlement");
            return;
        }
        ChunkPos target = new ChunkPos(packedTarget);

        if (settlement.claimedChunks().contains(packedTarget)) {
            err(player, "bannerbound.chunkclaim.error.already_yours");
            return;
        }
        Settlement otherOwner = data.getByChunk(packedTarget);
        if (otherOwner != null) {
            player.sendSystemMessage(Component.translatable(
                "bannerbound.chunkclaim.error.owned_by_other", otherOwner.name())
                .withStyle(ChatFormatting.RED));
            return;
        }
        if (!isAdjacentToClaim(settlement, target)) {
            err(player, "bannerbound.chunkclaim.error.too_far");
            return;
        }

        switch (settlement.governmentType()) {
            case CHIEFDOM -> {
                if (settlement.canActWeighty(player.getUUID())) {
                    executeClaim(server, overworld, data, settlement, player, target, packedTarget,
                        com.bannerbound.core.territory.SettlementInventoryHelper.singletonVoters(player));
                } else {
                    toggleChunkSuggestion(server, settlement, player, packedTarget);
                }
            }
            case COUNCIL -> tryCouncilVote(server, overworld, data, settlement, player, target, packedTarget);
            case NONE -> executeClaim(server, overworld, data, settlement, player, target, packedTarget,
                com.bannerbound.core.territory.SettlementInventoryHelper.singletonVoters(player));
        }
    }

    private static void toggleChunkSuggestion(MinecraftServer server, Settlement settlement,
                                                ServerPlayer player, long packedTarget) {
        settlement.toggleExpansionSuggestion(packedTarget, player.getUUID());
        broadcastTerritoryRefresh(server, settlement);
    }

    private static void tryCouncilVote(MinecraftServer server, ServerLevel overworld,
                                        SettlementData data, Settlement settlement,
                                        ServerPlayer player, ChunkPos target, long packedTarget) {
        long now = System.currentTimeMillis();
        settlement.expireExpansionVotes(now, COUNCIL_VOTE_EXPIRY_MS);
        settlement.toggleExpansionVote(packedTarget, player.getUUID(), now);

        java.util.LinkedHashMap<java.util.UUID, Long> votes = settlement.expansionVotesFor(packedTarget);
        int onlineMembers = SettlementManager.countOnlineMembers(server, settlement);
        int needed = SettlementManager.councilExpandThreshold(onlineMembers);

        if (votes.size() < needed) {
            broadcastTerritoryRefresh(server, settlement);
            return;
        }
        java.util.List<ServerPlayer> voters = new java.util.ArrayList<>(votes.size());
        for (java.util.UUID id : votes.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) voters.add(p);
        }
        if (voters.isEmpty()) {
            settlement.clearExpansionVotes(packedTarget);
            broadcastTerritoryRefresh(server, settlement);
            return;
        }
        boolean claimed = executeClaim(server, overworld, data, settlement, player, target, packedTarget, voters);
        if (!claimed) {
            settlement.clearExpansionVotes(packedTarget);
            com.bannerbound.core.api.settlement.SettlementManager.broadcastToSettlement(
                server, settlement,
                Component.translatable("bannerbound.territory.vote.no_resources",
                    target.x, target.z).withStyle(ChatFormatting.RED));
            broadcastTerritoryRefresh(server, settlement);
        }
    }

    private static boolean executeClaim(MinecraftServer server, ServerLevel overworld,
                                         SettlementData data, Settlement settlement,
                                         ServerPlayer triggeringPlayer, ChunkPos target,
                                         long packedTarget, java.util.List<ServerPlayer> voters) {
        int used = settlement.expansionsUsed();
        if (used >= totalExpansionCap(settlement)) {
            err(triggeringPlayer, "bannerbound.territory.error.cap_reached");
            return false;
        }
        EraTier et = resolveEraTier(settlement, used);
        if (et == null) {
            err(triggeringPlayer, "bannerbound.territory.error.cap_reached");
            return false;
        }
        ChunkClaimCostFile costFile = ChunkClaimCostLoader.get(et.era().key());
        if (costFile == null) {
            err(triggeringPlayer, "bannerbound.territory.error.no_cost_data");
            return false;
        }
        ResourceLocation biome = TerritoryBiomeResolver.majorityBiome(overworld, settlement);
        java.util.List<ChunkClaimCost> tiers = costFile.tiersFor(biome);
        if (et.tier() >= tiers.size()) {
            err(triggeringPlayer, "bannerbound.territory.error.cap_reached");
            return false;
        }
        ChunkClaimCost cost = tiers.get(et.tier());
        if (settlement.population() < cost.populationRequired()) {
            triggeringPlayer.sendSystemMessage(Component.translatable(
                "bannerbound.territory.error.not_enough_population",
                settlement.population(), cost.populationRequired())
                .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!com.bannerbound.core.territory.SettlementInventoryHelper.hasAll(
                overworld, settlement, voters, cost.items())) {
            return false;
        }
        if (!com.bannerbound.core.territory.SettlementInventoryHelper.consume(
                overworld, settlement, voters, cost.items())) {
            err(triggeringPlayer, "bannerbound.territory.error.consume_failed");
            return false;
        }
        if (data.claimChunk(settlement, target)) {
            ChunkForceLoader.force(overworld, packedTarget);
            String chunkType = com.bannerbound.core.territory.ChunkResources.typeAt(overworld, target)
                .name().toLowerCase(java.util.Locale.ROOT);
            com.bannerbound.core.api.research.InsightManager.recordEvent(
                server, settlement, "claim_chunk",
                authored -> authored.isEmpty() || authored.equals(chunkType), 1);
        }
        settlement.incrementExpansionsUsed();
        settlement.clearExpansionVotes(packedTarget);
        settlement.clearExpansionSuggestions(packedTarget);
        data.setDirty();
        SettlementManager.broadcastClaims(server);

        overworld.playSound(null,
            triggeringPlayer.getX(), triggeringPlayer.getY(), triggeringPlayer.getZ(),
            net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
            net.minecraft.sounds.SoundSource.PLAYERS,
            0.8f, 1.0f);

        Component announcement = Component.translatable(
            "bannerbound.territory.announce",
            triggeringPlayer.getName(), target.x, target.z, settlement.name())
            .withStyle(settlement.identityFormatting());
        server.getPlayerList().broadcastSystemMessage(announcement, false);

        broadcastTerritoryRefresh(server, settlement);
        return true;
    }

    private static void broadcastTerritoryRefresh(MinecraftServer server, Settlement settlement) {
        ServerLevel overworld = server.overworld();
        for (java.util.UUID memberId : settlement.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m == null) continue;
            OpenExpandTerritoryScreenPayload refreshed = buildScreenPayload(overworld, settlement, m);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(m, refreshed);
        }
    }

    private static final long COUNCIL_VOTE_EXPIRY_MS = SettlementManager.VOTE_EXPIRY_MS;

    private static boolean isAdjacentToClaim(Settlement settlement, ChunkPos target) {
        int cx = target.x;
        int cz = target.z;
        Set<Long> claims = settlement.claimedChunks();
        return claims.contains(new ChunkPos(cx - 1, cz).toLong())
            || claims.contains(new ChunkPos(cx + 1, cz).toLong())
            || claims.contains(new ChunkPos(cx, cz - 1).toLong())
            || claims.contains(new ChunkPos(cx, cz + 1).toLong());
    }

    private static void err(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.RED));
    }

    @SuppressWarnings("unused")
    private static void _keepImports() { new HashSet<Long>(); }
}
