package com.bannerbound.core.api.settlement;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Top-level {@link SavedData} for all bannerbound server state: the {@link Settlement} table,
 * the player->settlement and chunk->settlement reverse indices, the world-wide era (max of all
 * settlement ages, or admin-set via /bannerbound world set_age), global research history,
 * diplomacy relations, stolen standards, and war/leave cooldowns. Attached to the overworld's
 * data storage -- call {@link #get(ServerLevel)} from anywhere server-side; every mutator calls
 * {@link #setDirty()} so changes persist on the next save. The reverse indices (including
 * workingChunkToSettlement, the index of outpost WORKING claims) are never saved -- they are
 * rebuilt from each settlement's own lists in {@link #load}.
 * <p>
 * Working claims (outposts) are exclusive but unprotected and never territory: a chunk has at
 * most ONE holder of any claim kind, so a foreign working claim blocks full claims and vice
 * versa, while fully claiming your own outpost chunk upgrades it (the redundant working claim
 * is dropped).
 * <p>
 * Global research state is monotonic by design: globalResearchedIds holds every research id any
 * settlement has ever completed and is never shrunk by gameplay (disband, era regression, even
 * unresearch commands) so the world-year HUD only moves forward. globalResearchOrder is its
 * append-only, duplicate-free first-completion order; the last entry is the world's tech
 * frontier, and barbarian camps derive "everything but the last" from it (see
 * com.bannerbound.core.barbarian.BarbarianData / campKnownTech). Only {@link #resetWorldAge}
 * (the /bannerbound reset_world_age command) clears both, and its caller must broadcast the new
 * era to clients afterwards. markGloballyResearched returns true only on a genuinely new global
 * discovery so callers can gate HUD updates on it.
 * <p>
 * leaveCooldownUntil maps player -> game-time tick before which they may not leave their
 * settlement; set on join/found (SettlementManager.LEAVE_COOLDOWN_TICKS) to stop rapid
 * join/leave cycling, cleared when they actually leave. DiplomacyRelation keys are canonical
 * via {@link #diplomacyKey} (lower UUID string first); removeRelationsInvolving prunes dead
 * relations on disband/raze so unresolvable endpoints don't accumulate. New world-level state
 * (active wars, world events, ...) belongs on this class next to worldAge with save/load and
 * accessors; per-settlement state belongs on {@link Settlement}.
 */
public class SettlementData extends SavedData {
    private static final String DATA_NAME = "bannerbound_settlements";

    private final Map<UUID, Settlement> settlements = new HashMap<>();
    private final Map<UUID, UUID> playerToSettlement = new HashMap<>();
    private final Map<Long, UUID> chunkToSettlement = new HashMap<>();
    private final Map<Long, UUID> workingChunkToSettlement = new HashMap<>();
    private Era worldAge = Era.ANCIENT;
    private final Set<String> globalResearchedIds = new HashSet<>();
    private final java.util.List<String> globalResearchOrder = new java.util.ArrayList<>();
    private final Map<String, DiplomacyRelation> diplomacyRelations = new HashMap<>();
    private final Map<UUID, StolenStandard> stolenStandards = new HashMap<>();
    private final Map<UUID, Long> winnerNoNewWarUntil = new HashMap<>();
    private final Set<UUID> rallyingSettlements = new HashSet<>();
    private final Map<UUID, Long> leaveCooldownUntil = new HashMap<>();

    public SettlementData() {
    }

    public static SettlementData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<SettlementData> factory() {
        return new Factory<>(SettlementData::new, SettlementData::load);
    }

    public Settlement getByPlayer(UUID playerId) {
        UUID settlementId = playerToSettlement.get(playerId);
        return settlementId == null ? null : settlements.get(settlementId);
    }

    public Settlement getById(UUID id) {
        return settlements.get(id);
    }

    public Collection<Settlement> all() {
        return Collections.unmodifiableCollection(settlements.values());
    }

    public Settlement getByChunk(long packedChunkPos) {
        UUID id = chunkToSettlement.get(packedChunkPos);
        return id == null ? null : settlements.get(id);
    }

    public boolean claimChunk(Settlement settlement, ChunkPos pos) {
        long packed = pos.toLong();
        if (chunkToSettlement.containsKey(packed)) {
            return false;
        }
        // Foreign WORKING claim blocks a full claim (exclusivity); own outpost chunk upgrades, dropping the working claim.
        UUID workOwner = workingChunkToSettlement.get(packed);
        if (workOwner != null) {
            if (!workOwner.equals(settlement.id())) return false;
            unclaimWorkingChunk(settlement, pos);
        }
        chunkToSettlement.put(packed, settlement.id());
        settlement.addClaim(packed);
        setDirty();
        return true;
    }

    public void unclaimAllOf(Settlement settlement) {
        for (long packed : settlement.claimedChunks()) {
            chunkToSettlement.remove(packed);
        }
        settlement.claimedChunks().clear();
        for (long packed : settlement.workingClaims()) {
            workingChunkToSettlement.remove(packed);
        }
        settlement.workingClaims().clear();
        setDirty();
    }

    public Settlement getByWorkingClaim(long packedChunkPos) {
        UUID id = workingChunkToSettlement.get(packedChunkPos);
        return id == null ? null : settlements.get(id);
    }

    public boolean claimWorkingChunk(Settlement settlement, ChunkPos pos) {
        long packed = pos.toLong();
        if (chunkToSettlement.containsKey(packed)) return false;
        UUID workOwner = workingChunkToSettlement.get(packed);
        if (workOwner != null && !workOwner.equals(settlement.id())) return false;
        workingChunkToSettlement.put(packed, settlement.id());
        settlement.workingClaims().add(packed);
        setDirty();
        return true;
    }

    public void unclaimWorkingChunk(Settlement settlement, ChunkPos pos) {
        long packed = pos.toLong();
        workingChunkToSettlement.remove(packed, settlement.id());
        settlement.workingClaims().remove(packed);
        settlement.removeOutpostBanner(packed);
        setDirty();
    }

    public boolean nameTaken(String name) {
        for (Settlement s : settlements.values()) {
            if (s.matchesName(name)) {
                return true;
            }
        }
        return false;
    }

    public void addSettlement(Settlement settlement) {
        settlements.put(settlement.id(), settlement);
        for (UUID member : settlement.members()) {
            playerToSettlement.put(member, settlement.id());
        }
        setDirty();
    }

    public void removeMember(Settlement settlement, UUID playerId) {
        settlement.removeMember(playerId);
        playerToSettlement.remove(playerId);
        leaveCooldownUntil.remove(playerId);
        setDirty();
    }

    public long leaveCooldownUntil(UUID playerId) {
        return leaveCooldownUntil.getOrDefault(playerId, 0L);
    }

    public void setLeaveCooldownUntil(UUID playerId, long untilGameTime) {
        leaveCooldownUntil.put(playerId, untilGameTime);
        setDirty();
    }

    public void addMember(Settlement settlement, UUID playerId) {
        settlement.members().add(playerId);
        playerToSettlement.put(playerId, settlement.id());
        setDirty();
    }

    public Era getWorldAge() {
        return worldAge;
    }

    public void setWorldAge(Era era) {
        this.worldAge = era;
        setDirty();
    }

    public boolean markGloballyResearched(String researchId) {
        if (globalResearchedIds.add(researchId)) {
            globalResearchOrder.add(researchId); // append ONLY inside first-add guard: order list must stay dup-free
            setDirty();
            return true;
        }
        return false;
    }

    public Set<String> getGlobalResearchedIds() {
        return Collections.unmodifiableSet(globalResearchedIds);
    }

    public java.util.List<String> getGlobalResearchOrder() {
        return Collections.unmodifiableList(globalResearchOrder);
    }

    public Collection<DiplomacyRelation> diplomacyRelations() {
        return Collections.unmodifiableCollection(diplomacyRelations.values());
    }

    public DiplomacyRelation relation(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) return null;
        return diplomacyRelations.computeIfAbsent(diplomacyKey(first, second),
            key -> new DiplomacyRelation(first, second));
    }

    public DiplomacyRelation existingRelation(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) return null;
        return diplomacyRelations.get(diplomacyKey(first, second));
    }

    public void removeRelationsInvolving(UUID settlementId) {
        if (settlementId == null) return;
        if (diplomacyRelations.values().removeIf(r -> r.involves(settlementId))) {
            setDirty();
        }
    }

    public static String diplomacyKey(UUID first, UUID second) {
        String a = first.toString();
        String b = second.toString();
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public Map<UUID, StolenStandard> stolenStandards() {
        return stolenStandards;
    }

    public Map<UUID, Long> winnerNoNewWarUntil() {
        return winnerNoNewWarUntil;
    }

    public Set<UUID> rallyingSettlements() {
        return rallyingSettlements;
    }

    public boolean isRallying(UUID settlementId) {
        return rallyingSettlements.contains(settlementId);
    }

    public void setRallying(UUID settlementId, boolean rally) {
        if (settlementId == null) return;
        boolean changed = rally ? rallyingSettlements.add(settlementId)
            : rallyingSettlements.remove(settlementId);
        if (changed) setDirty();
    }

    public void resetWorldAge() {
        globalResearchedIds.clear();
        globalResearchOrder.clear();
        worldAge = Era.ANCIENT;
        setDirty();
    }

    public boolean hasClaimsWithin(ChunkPos center, int radius, int minDistance) {
        for (Settlement s : settlements.values()) {
            for (long claim : s.claimedChunks()) {
                ChunkPos cp = new ChunkPos(claim);
                int chebyshev = Math.max(Math.abs(cp.x - center.x), Math.abs(cp.z - center.z));
                if (chebyshev - radius < minDistance) {
                    return true;
                }
            }
        }
        return false;
    }

    public void removeSettlement(Settlement settlement) {
        for (UUID member : settlement.members()) {
            playerToSettlement.remove(member);
        }
        settlements.remove(settlement.id());
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Settlement settlement : settlements.values()) {
            list.add(settlement.save());
        }
        tag.put("Settlements", list);
        tag.putInt("WorldAge", worldAge.ordinal());
        ListTag discovered = new ListTag();
        for (String id : globalResearchedIds) {
            discovered.add(StringTag.valueOf(id));
        }
        tag.put("GlobalResearchedIds", discovered);
        ListTag discoveredOrder = new ListTag();
        for (String id : globalResearchOrder) {
            discoveredOrder.add(StringTag.valueOf(id));
        }
        tag.put("GlobalResearchOrder", discoveredOrder);
        ListTag relations = new ListTag();
        for (DiplomacyRelation relation : diplomacyRelations.values()) {
            relations.add(relation.save());
        }
        tag.put("DiplomacyRelations", relations);
        ListTag stolen = new ListTag();
        for (StolenStandard standard : stolenStandards.values()) {
            stolen.add(standard.save());
        }
        tag.put("StolenStandards", stolen);
        ListTag winnerCooldowns = new ListTag();
        for (Map.Entry<UUID, Long> e : winnerNoNewWarUntil.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Settlement", e.getKey());
            c.putLong("Until", e.getValue());
            winnerCooldowns.add(c);
        }
        tag.put("WinnerNoNewWarUntil", winnerCooldowns);
        ListTag leaveCooldowns = new ListTag();
        for (Map.Entry<UUID, Long> e : leaveCooldownUntil.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Player", e.getKey());
            c.putLong("Until", e.getValue());
            leaveCooldowns.add(c);
        }
        tag.put("LeaveCooldownUntil", leaveCooldowns);
        ListTag rally = new ListTag();
        for (UUID id : rallyingSettlements) {
            rally.add(StringTag.valueOf(id.toString()));
        }
        tag.put("RallyingSettlements", rally);
        return tag;
    }

    public static SettlementData load(CompoundTag tag, HolderLookup.Provider provider) {
        SettlementData data = new SettlementData();
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Settlement settlement = Settlement.load(list.getCompound(i));
            data.settlements.put(settlement.id(), settlement);
            for (UUID member : settlement.members()) {
                data.playerToSettlement.put(member, settlement.id());
            }
            for (long packed : settlement.claimedChunks()) {
                data.chunkToSettlement.put(packed, settlement.id());
            }
            for (long packed : settlement.workingClaims()) {
                data.workingChunkToSettlement.put(packed, settlement.id());
            }
        }
        if (tag.contains("WorldAge")) {
            data.worldAge = Era.fromOrdinalOrDefault(tag.getInt("WorldAge"));
        }
        if (tag.contains("GlobalResearchedIds")) {
            ListTag discovered = tag.getList("GlobalResearchedIds", Tag.TAG_STRING);
            for (int i = 0; i < discovered.size(); i++) {
                data.globalResearchedIds.add(discovered.getString(i));
            }
        }
        if (tag.contains("GlobalResearchOrder")) {
            ListTag order = tag.getList("GlobalResearchOrder", Tag.TAG_STRING);
            for (int i = 0; i < order.size(); i++) {
                data.globalResearchOrder.add(order.getString(i));
            }
        } else {
            // Save-format migration: pre-order-log worlds seed the order from the set (order unknown, set complete).
            data.globalResearchOrder.addAll(data.globalResearchedIds);
        }
        if (tag.contains("DiplomacyRelations")) {
            ListTag relations = tag.getList("DiplomacyRelations", Tag.TAG_COMPOUND);
            for (int i = 0; i < relations.size(); i++) {
                DiplomacyRelation relation = DiplomacyRelation.load(relations.getCompound(i));
                if (relation != null) {
                    data.diplomacyRelations.put(diplomacyKey(relation.first(), relation.second()), relation);
                }
            }
        }
        if (tag.contains("StolenStandards")) {
            ListTag stolen = tag.getList("StolenStandards", Tag.TAG_COMPOUND);
            for (int i = 0; i < stolen.size(); i++) {
                StolenStandard standard = StolenStandard.load(stolen.getCompound(i));
                if (standard != null) data.stolenStandards.put(standard.targetSettlementId(), standard);
            }
        }
        if (tag.contains("WinnerNoNewWarUntil")) {
            ListTag cooldowns = tag.getList("WinnerNoNewWarUntil", Tag.TAG_COMPOUND);
            for (int i = 0; i < cooldowns.size(); i++) {
                CompoundTag c = cooldowns.getCompound(i);
                if (c.hasUUID("Settlement")) {
                    data.winnerNoNewWarUntil.put(c.getUUID("Settlement"), c.getLong("Until"));
                }
            }
        }
        if (tag.contains("LeaveCooldownUntil")) {
            ListTag leaveCooldowns = tag.getList("LeaveCooldownUntil", Tag.TAG_COMPOUND);
            for (int i = 0; i < leaveCooldowns.size(); i++) {
                CompoundTag c = leaveCooldowns.getCompound(i);
                if (c.hasUUID("Player")) {
                    data.leaveCooldownUntil.put(c.getUUID("Player"), c.getLong("Until"));
                }
            }
        }
        if (tag.contains("RallyingSettlements")) {
            ListTag rally = tag.getList("RallyingSettlements", Tag.TAG_STRING);
            for (int i = 0; i < rally.size(); i++) {
                try {
                    data.rallyingSettlements.add(UUID.fromString(rally.getString(i)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return data;
    }

    public static final class DiplomacyRelation {
        private final UUID first;
        private final UUID second;
        public boolean discovered;
        public boolean warActive;
        public long warStartedAt;
        public UUID pendingDeclarer;
        public UUID pendingTarget;
        public int pendingTicksRemaining;
        public boolean peaceOfferedByFirst;
        public boolean peaceOfferedBySecond;
        public long redeclareAfter;
        public UUID capturedTarget;
        public UUID capturedBy;
        public long capturedAt;
        public boolean gloryUsedByFirst;
        public boolean gloryUsedBySecond;

        public DiplomacyRelation(UUID first, UUID second) {
            String a = first.toString();
            String b = second.toString();
            if (a.compareTo(b) <= 0) {
                this.first = first;
                this.second = second;
            } else {
                this.first = second;
                this.second = first;
            }
        }

        public UUID first() { return first; }
        public UUID second() { return second; }

        public boolean involves(UUID id) {
            return first.equals(id) || second.equals(id);
        }

        public UUID other(UUID id) {
            if (first.equals(id)) return second;
            if (second.equals(id)) return first;
            return null;
        }

        public boolean peaceOfferedBy(UUID id) {
            if (first.equals(id)) return peaceOfferedByFirst;
            if (second.equals(id)) return peaceOfferedBySecond;
            return false;
        }

        public void setPeaceOfferedBy(UUID id, boolean value) {
            if (first.equals(id)) peaceOfferedByFirst = value;
            if (second.equals(id)) peaceOfferedBySecond = value;
        }

        public boolean gloryUsedBy(UUID id) {
            return first.equals(id) ? gloryUsedByFirst : second.equals(id) && gloryUsedBySecond;
        }

        public void setGloryUsedBy(UUID id) {
            if (first.equals(id)) gloryUsedByFirst = true;
            if (second.equals(id)) gloryUsedBySecond = true;
        }

        public boolean pending() {
            return pendingDeclarer != null && pendingTarget != null && pendingTicksRemaining > 0;
        }

        public boolean capturedFinal() {
            return capturedTarget != null && capturedBy != null;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("First", first);
            tag.putUUID("Second", second);
            tag.putBoolean("Discovered", discovered);
            tag.putBoolean("WarActive", warActive);
            tag.putLong("WarStartedAt", warStartedAt);
            if (pendingDeclarer != null) tag.putUUID("PendingDeclarer", pendingDeclarer);
            if (pendingTarget != null) tag.putUUID("PendingTarget", pendingTarget);
            tag.putInt("PendingTicksRemaining", pendingTicksRemaining);
            tag.putBoolean("PeaceOfferedByFirst", peaceOfferedByFirst);
            tag.putBoolean("PeaceOfferedBySecond", peaceOfferedBySecond);
            tag.putLong("RedeclareAfter", redeclareAfter);
            if (capturedTarget != null) tag.putUUID("CapturedTarget", capturedTarget);
            if (capturedBy != null) tag.putUUID("CapturedBy", capturedBy);
            tag.putLong("CapturedAt", capturedAt);
            tag.putBoolean("GloryUsedByFirst", gloryUsedByFirst);
            tag.putBoolean("GloryUsedBySecond", gloryUsedBySecond);
            return tag;
        }

        static DiplomacyRelation load(CompoundTag tag) {
            if (!tag.hasUUID("First") || !tag.hasUUID("Second")) return null;
            DiplomacyRelation relation = new DiplomacyRelation(tag.getUUID("First"), tag.getUUID("Second"));
            relation.discovered = tag.getBoolean("Discovered");
            relation.warActive = tag.getBoolean("WarActive");
            relation.warStartedAt = tag.getLong("WarStartedAt");
            if (tag.hasUUID("PendingDeclarer")) relation.pendingDeclarer = tag.getUUID("PendingDeclarer");
            if (tag.hasUUID("PendingTarget")) relation.pendingTarget = tag.getUUID("PendingTarget");
            relation.pendingTicksRemaining = tag.getInt("PendingTicksRemaining");
            relation.peaceOfferedByFirst = tag.getBoolean("PeaceOfferedByFirst");
            relation.peaceOfferedBySecond = tag.getBoolean("PeaceOfferedBySecond");
            relation.redeclareAfter = tag.getLong("RedeclareAfter");
            if (tag.hasUUID("CapturedTarget")) relation.capturedTarget = tag.getUUID("CapturedTarget");
            if (tag.hasUUID("CapturedBy")) relation.capturedBy = tag.getUUID("CapturedBy");
            relation.capturedAt = tag.getLong("CapturedAt");
            relation.gloryUsedByFirst = tag.getBoolean("GloryUsedByFirst");
            relation.gloryUsedBySecond = tag.getBoolean("GloryUsedBySecond");
            return relation;
        }
    }

    public static final class StolenStandard {
        private final UUID targetSettlementId;
        public UUID carrierPlayerId;
        public UUID carrierSettlementId;
        public BlockPos droppedPos;
        public long droppedAt;
        public long autoReturnAt;

        public StolenStandard(UUID targetSettlementId) {
            this.targetSettlementId = targetSettlementId;
        }

        public UUID targetSettlementId() { return targetSettlementId; }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("TargetSettlement", targetSettlementId);
            if (carrierPlayerId != null) tag.putUUID("CarrierPlayer", carrierPlayerId);
            if (carrierSettlementId != null) tag.putUUID("CarrierSettlement", carrierSettlementId);
            if (droppedPos != null) tag.putLong("DroppedPos", droppedPos.asLong());
            tag.putLong("DroppedAt", droppedAt);
            tag.putLong("AutoReturnAt", autoReturnAt);
            return tag;
        }

        static StolenStandard load(CompoundTag tag) {
            if (!tag.hasUUID("TargetSettlement")) return null;
            StolenStandard standard = new StolenStandard(tag.getUUID("TargetSettlement"));
            if (tag.hasUUID("CarrierPlayer")) standard.carrierPlayerId = tag.getUUID("CarrierPlayer");
            if (tag.hasUUID("CarrierSettlement")) standard.carrierSettlementId = tag.getUUID("CarrierSettlement");
            if (tag.contains("DroppedPos")) standard.droppedPos = BlockPos.of(tag.getLong("DroppedPos"));
            standard.droppedAt = tag.getLong("DroppedAt");
            standard.autoReturnAt = tag.getLong("AutoReturnAt");
            return standard;
        }
    }
}
