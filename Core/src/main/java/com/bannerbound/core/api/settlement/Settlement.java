package com.bannerbound.core.api.settlement;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.data.CultureTreeLoader;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.social.Conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-owned state aggregate for one settlement -- the mod's central data hub. Holds identity
 * (settlement/faction name, founding color slot, generated-language seed, Heraldry banner design
 * and identity dyes), members, claimed chunks plus outpost working claims, town hall and faction
 * banner positions, era and tool age, BOTH research trees (science and culture: completed +
 * progress + queue + active; the one-active-research-across-both-trees rule is enforced in the
 * managers' tryStart paths, not here), insights, typed policy and palette slots, labor
 * priorities, government (NONE/COUNCIL/CHIEFDOM with votes, chief election, regent and
 * suggestion maps), faith, the food/culture/devotion economy, the citizen roster,
 * homes/workshops/stockpiles/workstations, status effects, journal entries, and crises.
 *
 * <p>Persistence: {@link #save()} / {@link #load(CompoundTag)}, written under SettlementData.
 * When adding a field, update BOTH methods AND the StreamCodec of any payload that ships it to
 * clients (typically ResearchStateSyncPayload). Load is tolerant -- missing tags fall back to
 * defaults so old saves migrate silently; legacy keys (IssuedTablet, ExpansionsInEra,
 * Primary/Secondary/TertiaryDye) are still read, and pre-feature saves seed immigratedCount and
 * peakPopulation from the current roster. All vote/suggestion/election tallies, active
 * conversations, the regent, dormancy, and the food-rate EMAs are transient BY DESIGN: a save
 * mid-vote drops the round and players simply re-vote. Several vote/suggestion maps are Linked*
 * on purpose -- iteration order is cast/click order (earliest proposal names a faith; suggestion
 * badge rows render in click order).
 *
 * <p>Key decisions: expansionsUsed is lifetime-cumulative and never reset on era promotion (eras
 * stack their allowances; see TerritoryService). IMMIGRATION_CAP is only the Antiquity
 * client-side default -- the real gate is the era-keyed {@link #immigrationFloor()} vs
 * population, so a died-off settlement refills instead of soft-locking, while peakPopulation
 * anchors {@link #targetFoodConsumptionPerSecond()} so a die-off cannot shrink the starvation
 * crisis's demand. Anarchy (Government.NONE) consumes no food -- runway to build infrastructure
 * before the first government switches consumption on for good -- and a dormant settlement (no
 * member online; refreshed at the top of each server tick before every ticker) is frozen
 * entirely. populationMaximum counts spare beds only while nobody is homeless: beds gate births,
 * never immigration. Once a banner design is saved, the banner's dominant dyes ARE the
 * settlement color (all rendering goes through identityRgb/identityFormatting; the founding
 * SettlementColor remains only the unique per-server slot). A downed faction banner locks the
 * town-hall menu and halts all citizen labor (see WorkGoal), and outpostBanners exists so the
 * once-a-second sweep catches outpost banners removed with NO break event (explosion, piston,
 * /setblock). canActAsChief (routine authority; the regent qualifies) is deliberately weaker
 * than canActWeighty (seated chief only -- a stand-in can never disband). Policy slots are typed
 * and derived from government layout + research unlock flags, NOT era; palette slots are
 * era-capped. Homes and workshops key by stable id (no anchor block; box geometry lives in
 * BlockSelectionRegistry), while workstations and stockpiles key by pos.
 */
public final class Settlement {
    public static final double DEFAULT_SCIENCE_PER_SECOND = 0.1;
    public static final double SCIENCE_PER_POPULATION = 0.03;

    public static final double DEFAULT_FOOD_PER_SECOND = 0.1;
    public static final double DEFAULT_CULTURE_PER_SECOND = 0.05;
    public static final double BASE_IMMIGRATION_FOOD_COST = 5.0;
    public static final double BASE_IMMIGRATION_CULTURE_COST = 50.0;

    private final UUID id;
    private final String name;
    private final long languageSeed;
    private String factionName;
    private final SettlementColor color;
    private final UUID owner;
    private final Set<UUID> members;
    private final Set<Long> claimedChunks;
    private final Set<Long> workingClaims = new LinkedHashSet<>();
    private final java.util.Map<Long, BlockPos> outpostBanners = new java.util.LinkedHashMap<>();
    private BlockPos townHallPos;
    private BlockPos bannerPos;
    private final List<BannerLayer> bannerDesign = new ArrayList<>();
    private final List<Integer> identityDyeIds = new ArrayList<>();
    private int tabletsIssued;

    public record BannerLayer(String patternId, int colorId) {}
    private Era age;

    private final Set<String> completedResearches;
    private final Set<String> knownItems;

    private final Map<String, Double> researchProgress;
    private final Map<String, Integer> insightCounters = new HashMap<>();
    private final Set<String> firedInsights = new HashSet<>();
    private final List<String> researchQueue;
    private String activeResearch;
    private double sciencePerSecond;

    private final Set<String> completedCultureResearches = new HashSet<>();
    private final Map<String, Double> cultureResearchProgress = new HashMap<>();
    private final List<String> cultureResearchQueue = new ArrayList<>();
    private String activeCultureResearch = null;

    private final List<Citizen> citizens;
    private final transient List<Conversation> activeConversations = new ArrayList<>();
    private double foodPerSecond;
    private double culturePerSecond;
    private double foodStored;
    private double cultureStored;
    private transient double storedFoodValue = 0.0;
    private transient double storedFoodPerSecond = 0.0;
    private double bonusFoodCapacity;
    private double bonusCultureCapacity;
    private double bonusCitizenSpeed;
    private String currentToolAge = "";
    private int expansionsUsed = 0;
    public static final int IMMIGRATION_CAP = 7;
    private int immigratedCount = 0;
    private int peakPopulation = 0;
    private final List<String> cultureStyles = new ArrayList<>();
    private final Set<UUID> disbandVotes = new HashSet<>();
    private long disbandVoteStartedMs = -1L;

    public enum Government { NONE, COUNCIL, CHIEFDOM }

    private Government governmentType = Government.NONE;
    private UUID chiefPlayerId = null;
    private long chiefSinceTick = -1L;
    private boolean codeOfLawsPromptShown = false;
    private SettlementStage lastAnnouncedStage = SettlementStage.HEARTH;
    private final Map<UUID, Government> governmentVotes = new HashMap<>();
    private long governmentVoteStartedMs = -1L;

    private UUID faithId = null;
    private double devotionStored = 0.0;
    private boolean faithFoundingUnlocked = false;
    private final Map<UUID, String> faithVotes = new java.util.LinkedHashMap<>();
    private final Map<UUID, String> faithNameProposals = new java.util.LinkedHashMap<>();
    private long faithRejoinAfterGameTime = 0L;
    private final java.util.Set<UUID> abandonFaithVotes = new HashSet<>();

    public long faithRejoinAfterGameTime() {
        return faithRejoinAfterGameTime;
    }

    public void setFaithRejoinAfterGameTime(long gameTime) {
        this.faithRejoinAfterGameTime = gameTime;
    }

    public java.util.Set<UUID> abandonFaithVotes() {
        return abandonFaithVotes;
    }

    private final transient com.bannerbound.core.api.faith.FaithEffectBundle faithEffects =
        new com.bannerbound.core.api.faith.FaithEffectBundle();

    public com.bannerbound.core.api.faith.FaithEffectBundle faithEffects() {
        return faithEffects;
    }

    public UUID faithId() {
        return faithId;
    }

    public void setFaithId(UUID faithId) {
        this.faithId = faithId;
    }

    public boolean hasFaith() {
        return faithId != null;
    }

    public double devotionStored() {
        return devotionStored;
    }

    public void setDevotionStored(double devotionStored) {
        this.devotionStored = Math.max(0.0, devotionStored);
    }

    public boolean faithFoundingUnlocked() {
        return faithFoundingUnlocked;
    }

    public void setFaithFoundingUnlocked(boolean unlocked) {
        this.faithFoundingUnlocked = unlocked;
    }

    public boolean faithChoiceWindowOpen() {
        return faithFoundingUnlocked && faithId == null;
    }

    public Map<UUID, String> faithVotes() {
        return java.util.Collections.unmodifiableMap(faithVotes);
    }

    public void castFaithVote(UUID player, String optionKey, String proposedName) {
        faithVotes.put(player, optionKey);
        if (proposedName != null && !proposedName.isBlank()) {
            faithNameProposals.put(player, proposedName.trim());
        }
    }

    public void clearFaithVote() {
        faithVotes.clear();
        faithNameProposals.clear();
        abandonFaithVotes.clear();
    }

    public int faithVoteCountFor(String optionKey) {
        int n = 0;
        for (String pick : faithVotes.values()) {
            if (pick.equals(optionKey)) n++;
        }
        return n;
    }

    public String faithNameProposalFor(String optionKey) {
        for (Map.Entry<UUID, String> vote : faithVotes.entrySet()) {
            if (vote.getValue().equals(optionKey)) {
                String name = faithNameProposals.get(vote.getKey());
                if (name != null && !name.isBlank()) return name;
            }
        }
        return null;
    }

    private final Map<UUID, UUID> chiefNominations = new HashMap<>();
    private long chiefElectionStartedMs = -1L;
    private UUID pendingChiefId = null;
    private long pendingChiefEnactTick = 0L;
    private Government pendingGovernmentType = null;
    private long pendingGovernmentEnactTick = 0L;
    private boolean pendingDisband = false;
    private long pendingDisbandEnactTick = 0L;

    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> scienceSuggestions = new java.util.HashMap<>();
    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> cultureSuggestions = new java.util.HashMap<>();
    private final transient java.util.Map<Long, java.util.LinkedHashMap<UUID, Long>> expansionVotes = new java.util.HashMap<>();
    private final transient java.util.Map<Long, java.util.LinkedHashSet<UUID>> expansionSuggestions = new java.util.HashMap<>();
    private final transient java.util.Map<UUID, java.util.LinkedHashSet<UUID>> exileSuggestions = new java.util.HashMap<>();
    private final transient java.util.LinkedHashSet<UUID> tabletSuggestions = new java.util.LinkedHashSet<>();

    private final List<String> activePolicies = new ArrayList<>();
    private transient PolicyChange pendingPolicyChange = null;
    private final transient java.util.Map<UUID, Boolean> policyConfirmVotes = new java.util.HashMap<>();
    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> policySuggestions = new java.util.HashMap<>();
    private long policyOpinionatedBonusExpiry = 0L;

    public record PolicyChange(int slotIndex, String addPolicyId, String removePolicyId) {}

    private final List<String> laborPriority = new ArrayList<>();
    private final java.util.Set<String> laborDisabled = new java.util.HashSet<>();
    private boolean laborAutoAssign = true;
    private final Map<String, Integer> laborCaps = new HashMap<>();
    private BlockPos preferredStoragePos = null;

    private final List<String> activePalettes = new ArrayList<>();
    private transient PaletteChange pendingPaletteChange = null;
    private final transient java.util.Map<UUID, Boolean> paletteConfirmVotes = new java.util.HashMap<>();
    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> paletteSuggestions = new java.util.HashMap<>();

    public record PaletteChange(int slotIndex, String addPaletteId, String removePaletteId) {}
    private transient long lastCoupCheckDay = -1L;
    private transient long lastDuskWarnDay = -1L;
    private transient long lastPolicyHour = -1L;
    private transient boolean coupSuppressed = false;
    private transient UUID regentPlayerId = null;
    private final Map<Long, Workstation> workstations;
    private final Map<UUID, Home> homes = new HashMap<>();
    private final Map<Long, Stockpile> stockpiles = new HashMap<>();
    private final Map<UUID, Workshop> workshops = new HashMap<>();
    private final List<StatusEffect> statusEffects = new ArrayList<>();
    private final Map<UUID, com.bannerbound.core.journal.JournalEntry> journalEntries = new LinkedHashMap<>();
    private final Map<String, Double> passiveFoodSourceRates = new HashMap<>();
    private final List<FoodSourcePulse> foodSourcePulses = new ArrayList<>();
    private final Map<String, Double> foodProducedBySource = new HashMap<>();
    private final Map<String, Integer> outpostAccrued = new HashMap<>();
    private final Map<String, Double> foodProductionRate = new HashMap<>();
    private final Map<String, Double> lastProducedSnapshot = new HashMap<>();
    private transient long netFoodOkSinceTick = -1L;
    private static final double PRODUCTION_RATE_ALPHA = 0.1; // EMA smoothing, ~10s time constant at 1 Hz
    private com.bannerbound.core.crisis.CrisisState activeCrisis = null;
    private final Set<String> completedCrises = new HashSet<>();
    private final Set<String> failedCrises = new HashSet<>();

    private record FoodSourcePulse(UUID instanceId, String source, double amount, int remainingTicks) {
        FoodSourcePulse tickDown() {
            return new FoodSourcePulse(instanceId, source, amount, remainingTicks - 1);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", instanceId);
            tag.putString("Source", source);
            tag.putDouble("Amount", amount);
            tag.putInt("Ticks", remainingTicks);
            return tag;
        }

        static FoodSourcePulse load(CompoundTag tag) {
            return new FoodSourcePulse(
                tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID(),
                tag.getString("Source"),
                tag.getDouble("Amount"),
                tag.getInt("Ticks")
            );
        }
    }

    public Settlement(UUID id, String name, SettlementColor color, UUID owner) {
        this.id = id;
        this.name = name;
        this.languageSeed = com.bannerbound.core.language.SettlementLanguage.deriveSeed(id, name);
        this.factionName = name;
        this.color = color;
        this.owner = owner;
        this.members = new HashSet<>();
        this.members.add(owner);
        this.claimedChunks = new LinkedHashSet<>();
        this.townHallPos = null;
        this.tabletsIssued = 0;
        this.age = Era.ANCIENT;
        this.completedResearches = new HashSet<>();
        this.knownItems = new HashSet<>();
        this.researchProgress = new HashMap<>();
        this.researchQueue = new ArrayList<>();
        this.activeResearch = null;
        this.sciencePerSecond = DEFAULT_SCIENCE_PER_SECOND;
        this.citizens = new ArrayList<>();
        this.foodPerSecond = DEFAULT_FOOD_PER_SECOND;
        this.culturePerSecond = DEFAULT_CULTURE_PER_SECOND;
        this.foodStored = 0.0;
        this.cultureStored = 0.0;
        this.bonusFoodCapacity = 0.0;
        this.bonusCultureCapacity = 0.0;
        this.bonusCitizenSpeed = 0.0;
        this.workstations = new HashMap<>();
    }

    private Settlement(UUID id, String name, SettlementColor color, UUID owner, Set<UUID> members,
                       Set<Long> claimedChunks, BlockPos townHallPos, int tabletsIssued, Era age,
                       Set<String> completedResearches, Set<String> knownItems, Map<String, Double> researchProgress,
                       List<String> researchQueue, String activeResearch, double sciencePerSecond,
                       List<Citizen> citizens, double foodPerSecond, double culturePerSecond,
                       double foodStored, double cultureStored,
                       double bonusFoodCapacity, double bonusCultureCapacity,
                       double bonusCitizenSpeed,
                       Map<Long, Workstation> workstations,
                       long languageSeed) {
        this.id = id;
        this.name = name;
        this.languageSeed = languageSeed;
        this.factionName = name;
        this.color = color;
        this.owner = owner;
        this.members = members;
        this.claimedChunks = claimedChunks;
        this.townHallPos = townHallPos;
        this.tabletsIssued = tabletsIssued;
        this.age = age;
        this.completedResearches = completedResearches;
        this.researchProgress = researchProgress;
        this.researchQueue = researchQueue;
        this.activeResearch = activeResearch;
        this.sciencePerSecond = sciencePerSecond;
        this.citizens = citizens;
        this.foodPerSecond = foodPerSecond;
        this.culturePerSecond = culturePerSecond;
        this.foodStored = foodStored;
        this.cultureStored = cultureStored;
        this.bonusFoodCapacity = bonusFoodCapacity;
        this.bonusCultureCapacity = bonusCultureCapacity;
        this.bonusCitizenSpeed = bonusCitizenSpeed;
        this.workstations = workstations;
        this.knownItems = knownItems;
    }

    public UUID id() { return id; }
    public long languageSeed() { return languageSeed; }
    public String name() { return name; }
    public String settlementName() { return name; }
    public String capitalName() { return name; }
    public String factionName() {
        return (factionName == null || factionName.isBlank()) ? name : factionName;
    }
    public void setFactionName(String factionName) {
        if (factionName != null && !factionName.isBlank()) {
            this.factionName = factionName.trim();
        }
    }
    public boolean matchesName(String query) {
        if (query == null) return false;
        String q = query.trim();
        return name.equalsIgnoreCase(q) || factionName().equalsIgnoreCase(q);
    }
    public SettlementColor color() { return color; }
    public UUID owner() { return owner; }
    public Set<UUID> members() { return members; }

    public boolean removeMember(UUID playerId) { return members.remove(playerId); }
    public boolean isEmpty() { return members.isEmpty(); }

    public Set<Long> claimedChunks() { return claimedChunks; }
    public void addClaim(long packedChunkPos) { claimedChunks.add(packedChunkPos); }
    public void removeClaim(long packedChunkPos) { claimedChunks.remove(packedChunkPos); }

    public Set<Long> workingClaims() { return workingClaims; }

    public BlockPos outpostBannerPos(long packedChunkPos) { return outpostBanners.get(packedChunkPos); }
    public void setOutpostBanner(long packedChunkPos, BlockPos bannerPos) {
        outpostBanners.put(packedChunkPos, bannerPos.immutable());
    }
    public void removeOutpostBanner(long packedChunkPos) { outpostBanners.remove(packedChunkPos); }

    public BlockPos townHallPos() { return townHallPos; }
    public void setTownHallPos(BlockPos pos) { this.townHallPos = pos; }
    public boolean hasTownHall() { return townHallPos != null; }

    public BlockPos bannerPos() { return bannerPos; }
    public void setBannerPos(BlockPos pos) { this.bannerPos = pos; }
    public boolean hasFactionBanner() { return bannerPos != null; }

    public List<BannerLayer> bannerDesign() { return bannerDesign; }
    public void setBannerDesign(List<BannerLayer> layers) {
        bannerDesign.clear();
        bannerDesign.addAll(layers);
    }

    public void setIdentityDyes(List<Integer> dyeIds) {
        identityDyeIds.clear();
        identityDyeIds.addAll(dyeIds);
    }

    public List<Integer> identityRgbList() {
        if (identityDyeIds.isEmpty()) {
            return List.of(color.rgb());
        }
        List<Integer> out = new ArrayList<>(identityDyeIds.size());
        for (int id : identityDyeIds) {
            out.add(net.minecraft.world.item.DyeColor.byId(id).getTextureDiffuseColor() & 0xFFFFFF);
        }
        return out;
    }

    public int identityRgb() {
        return identityDyeIds.isEmpty()
            ? color.rgb()
            : (net.minecraft.world.item.DyeColor.byId(identityDyeIds.get(0)).getTextureDiffuseColor()
                & 0xFFFFFF);
    }

    public net.minecraft.ChatFormatting identityFormatting() {
        return identityDyeIds.isEmpty()
            ? color.formatting()
            : FactionBanner.formattingFor(
                net.minecraft.world.item.DyeColor.byId(identityDyeIds.get(0)));
    }

    public int tabletsIssued() { return tabletsIssued; }
    public int tabletCapacity() { return age.registrationDocumentSlots(); }
    public boolean canIssueTablet() { return tabletsIssued < tabletCapacity(); }
    public void incrementTabletsIssued() { this.tabletsIssued++; }

    public Era age() { return age; }
    public void setAge(Era age) {
        // Do NOT reset expansionsUsed here: eras stack their expansion allowances cumulatively.
        this.age = age;
    }

    public int expansionsUsed() { return expansionsUsed; }
    public void incrementExpansionsUsed() { this.expansionsUsed++; }
    public void setExpansionsUsed(int value) { this.expansionsUsed = value; }

    public List<String> cultureStyles() { return cultureStyles; }
    public void setCultureStyle(String styleId) {
        cultureStyles.clear();
        if (styleId != null && !styleId.isBlank()) cultureStyles.add(styleId);
    }
    public void addCultureStyle(String styleId) {
        if (styleId != null && !styleId.isBlank() && !cultureStyles.contains(styleId)) {
            cultureStyles.add(styleId);
        }
    }

    public Set<UUID> disbandVotes() { return disbandVotes; }
    public int disbandVoteCount() { return disbandVotes.size(); }
    public boolean hasDisbandVoted(UUID id) { return disbandVotes.contains(id); }
    public long disbandVoteStartedMs() { return disbandVoteStartedMs; }
    public boolean isDisbandVoteActive() { return disbandVoteStartedMs > 0L; }
    public void addDisbandVote(UUID id, long startMs) {
        if (disbandVotes.isEmpty()) disbandVoteStartedMs = startMs;
        disbandVotes.add(id);
    }
    public void clearDisbandVote() {
        disbandVotes.clear();
        disbandVoteStartedMs = -1L;
    }

    public Government governmentType() { return governmentType; }
    public void setGovernmentType(Government g) { this.governmentType = g == null ? Government.NONE : g; }
    public UUID chiefPlayerId() { return chiefPlayerId; }
    public void setChiefPlayerId(UUID id) {
        this.chiefPlayerId = id;
        if (id == null) this.chiefSinceTick = -1L;
    }
    public long chiefSinceTick() { return chiefSinceTick; }
    public void setChiefSinceTick(long t) { this.chiefSinceTick = t; }
    public boolean codeOfLawsPromptShown() { return codeOfLawsPromptShown; }
    public void setCodeOfLawsPromptShown(boolean v) { this.codeOfLawsPromptShown = v; }

    public SettlementStage stage() {
        if (population() >= SettlementStage.VILLAGE_THRESHOLD) return SettlementStage.VILLAGE;
        if (governmentType != Government.NONE) return SettlementStage.TRIBE;
        return SettlementStage.HEARTH;
    }

    public SettlementStage lastAnnouncedStage() { return lastAnnouncedStage; }
    public void setLastAnnouncedStage(SettlementStage s) { this.lastAnnouncedStage = s; }

    public Set<UUID> leaderPlayerIds() {
        return switch (governmentType) {
            case NONE -> Collections.emptySet();
            case COUNCIL -> Collections.unmodifiableSet(members);
            case CHIEFDOM -> effectiveChiefId() == null
                ? Collections.emptySet()
                : Collections.singleton(effectiveChiefId());
        };
    }

    public UUID effectiveChiefId() {
        if (regentPlayerId != null) return regentPlayerId;
        return chiefPlayerId;
    }

    public boolean toggleScienceSuggestion(String researchId, UUID player) {
        return toggleSuggestion(scienceSuggestions, researchId, player);
    }
    public boolean toggleCultureSuggestion(String researchId, UUID player) {
        return toggleSuggestion(cultureSuggestions, researchId, player);
    }
    private static boolean toggleSuggestion(java.util.Map<String, java.util.LinkedHashSet<UUID>> map,
                                             String key, UUID player) {
        if (key == null || player == null) return false;
        java.util.LinkedHashSet<UUID> set = map.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
        if (set.contains(player)) {
            set.remove(player);
            if (set.isEmpty()) map.remove(key);
            return false;
        }
        set.add(player);
        return true;
    }

    public java.util.LinkedHashSet<UUID> scienceSuggesters(String researchId) {
        java.util.LinkedHashSet<UUID> s = scienceSuggestions.get(researchId);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    public java.util.LinkedHashSet<UUID> cultureSuggesters(String researchId) {
        java.util.LinkedHashSet<UUID> s = cultureSuggestions.get(researchId);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allScienceSuggestions() {
        return scienceSuggestions;
    }
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allCultureSuggestions() {
        return cultureSuggestions;
    }
    public boolean hasAnySuggestions() {
        return !scienceSuggestions.isEmpty() || !cultureSuggestions.isEmpty()
            || !policySuggestions.isEmpty() || !paletteSuggestions.isEmpty()
            || !exileSuggestions.isEmpty() || !tabletSuggestions.isEmpty();
    }
    public void clearScienceSuggestions(String researchId) {
        if (researchId != null) scienceSuggestions.remove(researchId);
    }
    public void clearCultureSuggestions(String researchId) {
        if (researchId != null) cultureSuggestions.remove(researchId);
    }

    public boolean toggleExileSuggestion(UUID citizenUuid, UUID player) {
        if (citizenUuid == null || player == null) return false;
        java.util.LinkedHashSet<UUID> set =
            exileSuggestions.computeIfAbsent(citizenUuid, k -> new java.util.LinkedHashSet<>());
        if (set.contains(player)) {
            set.remove(player);
            if (set.isEmpty()) exileSuggestions.remove(citizenUuid);
            return false;
        }
        set.add(player);
        return true;
    }
    public java.util.Map<UUID, java.util.LinkedHashSet<UUID>> allExileSuggestions() {
        return exileSuggestions;
    }
    public void clearExileSuggestions(UUID citizenUuid) {
        if (citizenUuid != null) exileSuggestions.remove(citizenUuid);
    }
    public boolean toggleTabletSuggestion(UUID player) {
        if (player == null) return false;
        if (tabletSuggestions.contains(player)) { tabletSuggestions.remove(player); return false; }
        tabletSuggestions.add(player);
        return true;
    }
    public java.util.LinkedHashSet<UUID> tabletSuggesters() {
        return new java.util.LinkedHashSet<>(tabletSuggestions);
    }
    public void clearTabletSuggestions() {
        tabletSuggestions.clear();
    }

    public List<String> laborPriority() { return java.util.Collections.unmodifiableList(laborPriority); }
    public java.util.Set<String> laborDisabled() { return java.util.Collections.unmodifiableSet(laborDisabled); }
    public boolean isLaborJobDisabled(String jobId) { return laborDisabled.contains(jobId); }
    public boolean laborAutoAssign() { return laborAutoAssign; }
    public void setLaborAutoAssign(boolean v) { this.laborAutoAssign = v; }
    public int laborCap(String jobId) { return laborCaps.getOrDefault(jobId, -1); }
    public void setLaborConfig(List<String> order, java.util.Collection<String> disabled) {
        laborPriority.clear();
        if (order != null) laborPriority.addAll(order);
        laborDisabled.clear();
        if (disabled != null) laborDisabled.addAll(disabled);
    }
    public void setLaborCaps(Map<String, Integer> caps) {
        laborCaps.clear();
        if (caps != null) {
            for (Map.Entry<String, Integer> e : caps.entrySet()) {
                if (e.getValue() != null && e.getValue() >= 0) laborCaps.put(e.getKey(), e.getValue());
            }
        }
    }
    @org.jetbrains.annotations.Nullable
    public BlockPos preferredStoragePos() { return preferredStoragePos; }
    public void setPreferredStoragePos(@org.jetbrains.annotations.Nullable BlockPos pos) { this.preferredStoragePos = pos; }

    public List<String> activePolicies() { return activePolicies; }
    public boolean hasPolicy(String policyId) { return activePolicies.contains(policyId); }

    public static List<PolicyType> governmentBaseLayout(Government gov) {
        if (gov == Government.CHIEFDOM) {
            return List.of(PolicyType.ECONOMIC, PolicyType.CULTURAL,
                PolicyType.SCIENTIFIC, PolicyType.MILITARISTIC);
        }
        if (gov == Government.COUNCIL) {
            return List.of(PolicyType.ECONOMIC, PolicyType.CULTURAL,
                PolicyType.CULTURAL, PolicyType.SCIENTIFIC);
        }
        return List.of();
    }

    public List<PolicyType> researchGrantedPolicySlots() {
        List<PolicyType> out = new ArrayList<>();
        collectPolicySlotFlags(completedResearches(), false, out);
        collectPolicySlotFlags(completedCultureResearches(), true, out);
        return out;
    }

    private static void collectPolicySlotFlags(java.util.Collection<String> researchIds,
                                               boolean culture, List<PolicyType> out) {
        for (String id : researchIds) {
            com.bannerbound.core.api.research.ResearchDefinition def = culture
                ? com.bannerbound.core.api.research.data.CultureTreeLoader.get(id)
                : com.bannerbound.core.api.research.data.ResearchTreeLoader.get(id);
            if (def == null) continue;
            for (String flag : def.unlocksFlags()) {
                if (flag.startsWith("unlock.policy_slot.")) {
                    PolicyType t = PolicyType.byName(flag.substring("unlock.policy_slot.".length()));
                    if (t != null) out.add(t);
                }
            }
        }
    }

    public List<PolicyType> policyTypeSlots() {
        List<PolicyType> out = new ArrayList<>(governmentBaseLayout(governmentType));
        out.addAll(researchGrantedPolicySlots());
        return out;
    }

    public boolean hasSignatureSlot() {
        return governmentType != Government.NONE
            && PolicyRegistry.signaturePolicyFor(governmentType) != null;
    }

    public List<String> policySlotTypeNames() {
        List<String> out = new ArrayList<>();
        for (PolicyType t : policyTypeSlots()) out.add(t.name());
        if (hasSignatureSlot()) out.add("SIGNATURE");
        return out;
    }

    public int policySlotCapacity() {
        return policyTypeSlots().size() + (hasSignatureSlot() ? 1 : 0);
    }

    public boolean hasFreeSlotForIn(java.util.Collection<String> active, String policyId) {
        PolicyRegistry.Policy p = PolicyRegistry.get(policyId);
        if (p == null) return false;
        if (PolicyRegistry.isSignature(policyId)) {
            if (p.governmentType() != governmentType || !hasSignatureSlot()) return false;
            for (String a : active) if (PolicyRegistry.isSignature(a)) return false;
            return true;
        }
        PolicyType t = p.type();
        int used = 0;
        for (String a : active) {
            PolicyRegistry.Policy ap = PolicyRegistry.get(a);
            if (ap != null && !PolicyRegistry.isSignature(a) && ap.type() == t) used++;
        }
        int cap = 0;
        for (PolicyType st : policyTypeSlots()) if (st == t) cap++;
        return used < cap;
    }

    public boolean canEnactProposal(String addId, String removeId) {
        java.util.LinkedHashSet<String> hyp = new java.util.LinkedHashSet<>(activePolicies);
        if (removeId != null) hyp.remove(removeId);
        String ex = PolicyRegistry.exclusiveWith(addId);
        if (ex != null) hyp.remove(ex);
        if (addId == null) return true;
        if (hyp.contains(addId)) return false;
        return hasFreeSlotForIn(hyp, addId);
    }

    public boolean addActivePolicy(String policyId) {
        if (policyId == null || activePolicies.contains(policyId)) return false;
        if (!hasFreeSlotForIn(activePolicies, policyId)) return false;
        activePolicies.add(policyId);
        return true;
    }
    public boolean removeActivePolicy(String policyId) { return activePolicies.remove(policyId); }

    public PolicyChange pendingPolicyChange() { return pendingPolicyChange; }
    public void setPendingPolicyChange(PolicyChange change) {
        this.pendingPolicyChange = change;
        this.policyConfirmVotes.clear();
    }
    public java.util.Map<UUID, Boolean> policyConfirmVotes() { return policyConfirmVotes; }
    public void castPolicyConfirmVote(UUID voter, boolean agree) {
        if (voter != null) policyConfirmVotes.put(voter, agree);
    }
    public void clearPolicyChangeState() {
        this.pendingPolicyChange = null;
        this.policyConfirmVotes.clear();
    }

    public boolean togglePolicySuggestion(String policyId, UUID player) {
        return toggleSuggestion(policySuggestions, policyId, player);
    }
    public java.util.LinkedHashSet<UUID> policySuggesters(String policyId) {
        java.util.LinkedHashSet<UUID> s = policySuggestions.get(policyId);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allPolicySuggestions() {
        return policySuggestions;
    }
    public void clearPolicySuggestions(String policyId) {
        if (policyId != null) policySuggestions.remove(policyId);
    }

    public List<String> activePalettes() { return activePalettes; }
    public boolean hasPalette(String paletteId) { return activePalettes.contains(paletteId); }
    public int paletteSlotCapacity() { return age().activePaletteSlots(); }
    public boolean addActivePalette(String paletteId) {
        if (paletteId == null || activePalettes.contains(paletteId)) return false;
        if (activePalettes.size() >= paletteSlotCapacity()) return false;
        activePalettes.add(paletteId);
        return true;
    }
    public boolean removeActivePalette(String paletteId) { return activePalettes.remove(paletteId); }

    public PaletteChange pendingPaletteChange() { return pendingPaletteChange; }
    public void setPendingPaletteChange(PaletteChange change) {
        this.pendingPaletteChange = change;
        this.paletteConfirmVotes.clear();
    }
    public java.util.Map<UUID, Boolean> paletteConfirmVotes() { return paletteConfirmVotes; }
    public void castPaletteConfirmVote(UUID voter, boolean agree) {
        if (voter != null) paletteConfirmVotes.put(voter, agree);
    }
    public void clearPaletteChangeState() {
        this.pendingPaletteChange = null;
        this.paletteConfirmVotes.clear();
    }
    public boolean togglePaletteSuggestion(String paletteId, UUID player) {
        return toggleSuggestion(paletteSuggestions, paletteId, player);
    }
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allPaletteSuggestions() {
        return paletteSuggestions;
    }
    public void clearPaletteSuggestions(String paletteId) {
        if (paletteId != null) paletteSuggestions.remove(paletteId);
    }

    public long policyOpinionatedBonusExpiry() { return policyOpinionatedBonusExpiry; }
    public void setPolicyOpinionatedBonusExpiry(long gameTick) {
        this.policyOpinionatedBonusExpiry = gameTick;
    }
    public boolean isOpinionatedBonusActive(long nowTick) {
        return policyOpinionatedBonusExpiry > nowTick;
    }

    public boolean toggleExpansionVote(long packedChunkPos, UUID player, long nowMs) {
        if (player == null) return false;
        java.util.LinkedHashMap<UUID, Long> votes = expansionVotes.computeIfAbsent(
            packedChunkPos, k -> new java.util.LinkedHashMap<>());
        if (votes.containsKey(player)) {
            votes.remove(player);
            if (votes.isEmpty()) expansionVotes.remove(packedChunkPos);
            return false;
        }
        votes.put(player, nowMs);
        return true;
    }
    public java.util.LinkedHashMap<UUID, Long> expansionVotesFor(long packedChunkPos) {
        java.util.LinkedHashMap<UUID, Long> v = expansionVotes.get(packedChunkPos);
        return v == null
            ? new java.util.LinkedHashMap<>()
            : new java.util.LinkedHashMap<>(v);
    }
    public java.util.Map<Long, java.util.LinkedHashMap<UUID, Long>> allExpansionVotes() {
        return expansionVotes;
    }
    public void clearExpansionVotes(long packedChunkPos) {
        expansionVotes.remove(packedChunkPos);
    }
    public void expireExpansionVotes(long nowMs, long expiryMs) {
        expansionVotes.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(e -> nowMs - e.getValue() > expiryMs);
            return entry.getValue().isEmpty();
        });
    }

    public boolean toggleExpansionSuggestion(long packedChunkPos, UUID player) {
        if (player == null) return false;
        java.util.LinkedHashSet<UUID> s = expansionSuggestions.computeIfAbsent(
            packedChunkPos, k -> new java.util.LinkedHashSet<>());
        if (s.contains(player)) {
            s.remove(player);
            if (s.isEmpty()) expansionSuggestions.remove(packedChunkPos);
            return false;
        }
        s.add(player);
        return true;
    }
    public java.util.LinkedHashSet<UUID> expansionSuggestersFor(long packedChunkPos) {
        java.util.LinkedHashSet<UUID> s = expansionSuggestions.get(packedChunkPos);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    public java.util.Map<Long, java.util.LinkedHashSet<UUID>> allExpansionSuggestions() {
        return expansionSuggestions;
    }
    public void clearExpansionSuggestions(long packedChunkPos) {
        expansionSuggestions.remove(packedChunkPos);
    }

    public long lastCoupCheckDay() { return lastCoupCheckDay; }
    public void setLastCoupCheckDay(long day) { this.lastCoupCheckDay = day; }
    public long lastDuskWarnDay() { return lastDuskWarnDay; }
    public void setLastDuskWarnDay(long day) { this.lastDuskWarnDay = day; }
    public long lastPolicyHour() { return lastPolicyHour; }
    public void setLastPolicyHour(long hour) { this.lastPolicyHour = hour; }
    public boolean isCoupSuppressed() { return coupSuppressed; }
    public void setCoupSuppressed(boolean v) { this.coupSuppressed = v; }

    public boolean canActAsChief(UUID player) {
        if (player == null) return false;
        return switch (governmentType) {
            case NONE -> false;
            case COUNCIL -> members.contains(player);
            case CHIEFDOM -> player.equals(chiefPlayerId) || player.equals(regentPlayerId);
        };
    }

    public boolean canActWeighty(UUID player) {
        if (player == null) return false;
        return switch (governmentType) {
            case NONE -> false;
            case COUNCIL -> members.contains(player);
            case CHIEFDOM -> player.equals(chiefPlayerId);
        };
    }

    public UUID regentPlayerId() { return regentPlayerId; }
    public void setRegentPlayerId(UUID id) { this.regentPlayerId = id; }
    public boolean hasActiveRegent() { return regentPlayerId != null; }

    public boolean governmentChoiceWindowOpen() {
        return governmentType == Government.NONE
            && codeOfLawsPromptShown
            && population() >= immigrationFloor();
    }

    public Map<UUID, Government> governmentVotes() {
        return Collections.unmodifiableMap(governmentVotes);
    }
    public int governmentVoteCountFor(Government g) {
        int n = 0;
        for (Government v : governmentVotes.values()) if (v == g) n++;
        return n;
    }
    public long governmentVoteStartedMs() { return governmentVoteStartedMs; }
    public boolean isGovernmentVoteActive() { return governmentVoteStartedMs > 0L; }
    public void castGovernmentVote(UUID voter, Government pick, long startMs) {
        if (governmentVotes.isEmpty()) governmentVoteStartedMs = startMs;
        governmentVotes.put(voter, pick);
    }
    public void clearGovernmentVote() {
        governmentVotes.clear();
        governmentVoteStartedMs = -1L;
    }

    public boolean chiefdomElectionWindowOpen() {
        return governmentType == Government.CHIEFDOM && chiefPlayerId == null;
    }
    public Map<UUID, UUID> chiefNominations() {
        return Collections.unmodifiableMap(chiefNominations);
    }
    public int chiefNominationCountFor(UUID candidate) {
        int n = 0;
        for (UUID c : chiefNominations.values()) if (candidate.equals(c)) n++;
        return n;
    }
    public long chiefElectionStartedMs() { return chiefElectionStartedMs; }
    public boolean isChiefElectionActive() { return chiefElectionStartedMs > 0L; }
    public void castChiefNomination(UUID voter, UUID candidate, long startMs) {
        if (chiefNominations.isEmpty()) chiefElectionStartedMs = startMs;
        chiefNominations.put(voter, candidate);
    }
    public void clearChiefElection() {
        chiefNominations.clear();
        chiefElectionStartedMs = -1L;
    }

    public void schedulePendingChief(UUID chiefId, long enactTick) {
        this.pendingChiefId = chiefId;
        this.pendingChiefEnactTick = enactTick;
    }
    public UUID pendingChiefId() { return pendingChiefId; }
    public long pendingChiefEnactTick() { return pendingChiefEnactTick; }
    public void clearPendingChief() {
        this.pendingChiefId = null;
        this.pendingChiefEnactTick = 0L;
    }

    public void schedulePendingGovernment(Government type, long enactTick) {
        this.pendingGovernmentType = type;
        this.pendingGovernmentEnactTick = enactTick;
    }
    public Government pendingGovernmentType() { return pendingGovernmentType; }
    public long pendingGovernmentEnactTick() { return pendingGovernmentEnactTick; }
    public void clearPendingGovernment() {
        this.pendingGovernmentType = null;
        this.pendingGovernmentEnactTick = 0L;
    }

    public void schedulePendingDisband(long enactTick) {
        this.pendingDisband = true;
        this.pendingDisbandEnactTick = enactTick;
    }
    public boolean hasPendingDisband() { return pendingDisband; }
    public long pendingDisbandEnactTick() { return pendingDisbandEnactTick; }
    public void clearPendingDisband() {
        this.pendingDisband = false;
        this.pendingDisbandEnactTick = 0L;
    }

    public Set<String> completedResearches() { return completedResearches; }
    public Set<String> knownItems() { return knownItems; }

    public static Set<String> computeKnownItems(Set<String> completedResearches) {
        Set<String> out = new HashSet<>();
        for (String id : completedResearches) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null) {
                out.addAll(def.unlocksItems());
            }
        }
        return out;
    }

    public static Set<String> computeKnownCultureItems(Set<String> completedCultureResearches) {
        Set<String> out = new HashSet<>();
        for (String id : completedCultureResearches) {
            ResearchDefinition def = CultureTreeLoader.get(id);
            if (def != null) {
                out.addAll(def.unlocksItems());
            }
        }
        return out;
    }

    public Set<String> computeKnownItems() {
        return computeKnownItems(completedResearches());
    }

    public Set<String> computeKnownCultureItems() {
        return computeKnownCultureItems(completedCultureResearches());
    }

    public void recomputeKnownItems() {
        Set<String> knownItems = computeKnownItems();
        knownItems.addAll(computeKnownCultureItems());
    }

    public boolean hasCompletedResearch(String id) { return completedResearches.contains(id); }
    public void markResearchComplete(String id) {
        completedResearches.add(id);
        researchProgress.remove(id);

        ResearchDefinition def = ResearchTreeLoader.get(id);
        if (def != null) {
            knownItems.addAll(def.unlocksItems());
        }
    }

    public Map<String, Double> researchProgress() { return researchProgress; }
    public double getResearchProgress(String id) {
        Double v = researchProgress.get(id);
        return v == null ? 0.0 : v;
    }
    public void setResearchProgress(String id, double value) {
        researchProgress.put(id, value);
    }

    public int insightCount(String key) { return insightCounters.getOrDefault(key, 0); }
    public void setInsightCount(String key, int value) { insightCounters.put(key, Math.max(0, value)); }
    public boolean hasFiredInsight(String key) { return firedInsights.contains(key); }
    public void markInsightFired(String key) { firedInsights.add(key); }
    public Set<String> firedInsights() { return java.util.Collections.unmodifiableSet(firedInsights); }

    public List<String> researchQueue() { return researchQueue; }

    public String activeResearch() { return activeResearch; }
    public void setActiveResearch(String id) { this.activeResearch = id; }

    public double sciencePerSecond() { return sciencePerSecond; }
    public void setSciencePerSecond(double v) { this.sciencePerSecond = v; }

    public double effectiveSciencePerSecond() {
        return sciencePerSecond + SCIENCE_PER_POPULATION * population() + faithEffects.science();
    }

    public Set<String> completedCultureResearches() { return completedCultureResearches; }
    public boolean hasCompletedCultureResearch(String id) { return completedCultureResearches.contains(id); }
    public boolean hasCompletedResearchEitherTree(String id) {
        return completedResearches.contains(id) || completedCultureResearches.contains(id);
    }
    public void markCultureResearchComplete(String id) {
        completedCultureResearches.add(id);
        cultureResearchProgress.remove(id);
    }
    public Map<String, Double> cultureResearchProgress() { return cultureResearchProgress; }
    public double getCultureResearchProgress(String id) {
        Double v = cultureResearchProgress.get(id);
        return v == null ? 0.0 : v;
    }
    public void setCultureResearchProgress(String id, double value) {
        cultureResearchProgress.put(id, value);
    }
    public List<String> cultureResearchQueue() { return cultureResearchQueue; }
    public String activeCultureResearch() { return activeCultureResearch; }
    public void setActiveCultureResearch(String id) { this.activeCultureResearch = id; }

    public double effectiveCulturePerSecond(net.minecraft.server.level.ServerLevel level) {
        double statusBonus = statusBonusFor(StatusEffectIcon.CULTURE);
        double appealCulture = level == null ? 0.0
            : com.bannerbound.core.api.settlement.ChunkBeautyManager.cultureBonus(level, this);
        return culturePerSecond + statusBonus + appealCulture + faithEffects.culture();
    }

    public List<Citizen> citizens() { return citizens; }
    public int population() { return citizens.size(); }
    public void addCitizen(Citizen c) { citizens.add(c); }
    public boolean removeCitizen(UUID entityId) {
        return citizens.removeIf(c -> c.entityId().equals(entityId));
    }

    public boolean renameCitizen(UUID entityId, String name) {
        for (int i = 0; i < citizens.size(); i++) {
            Citizen c = citizens.get(i);
            if (c.entityId().equals(entityId)) {
                if (c.name().equals(name)) return false;
                citizens.set(i, new Citizen(entityId, name));
                return true;
            }
        }
        return false;
    }

    public int immigratedCount() { return immigratedCount; }
    public void recordImmigration() { this.immigratedCount++; }

    public int immigrationFloor() {
        return age().immigrationFloor();
    }

    public int populationMaximum() {
        int beds = 0;
        int housed = 0;
        for (Home h : homes.values()) {
            if (!h.valid()) continue;
            beds += h.bedCount();
            housed += h.residents().size();
        }
        int homeless = population() - housed;
        int effective = (homeless > 0) ? housed : beds;
        return Math.max(immigrationFloor(), effective);
    }

    public String titleKey() {
        if (population() >= SettlementStage.VILLAGE_THRESHOLD) {
            return "bannerbound.settlement.title.village";
        }
        return isTribe()
            ? "bannerbound.settlement.title.tribe"
            : "bannerbound.settlement.title.hearth";
    }

    public boolean isTribe() {
        return governmentType != Government.NONE || population() >= 8;
    }

    private static final double SECONDS_PER_GAME_DAY = 1200.0;

    public double foodConsumptionPerSecond() {
        if (dormant) return 0.0;
        if (governmentType == Government.NONE) return 0.0;
        return population() * com.bannerbound.core.Config.FOOD_PER_CITIZEN_PER_DAY.get()
            / SECONDS_PER_GAME_DAY;
    }

    public void notePopulationPeak() {
        if (population() > peakPopulation) peakPopulation = population();
    }

    public int targetPopulation() {
        return Math.max(population(), Math.min(peakPopulation, immigrationFloor()));
    }

    public double targetFoodConsumptionPerSecond() {
        if (governmentType == Government.NONE) return 0.0;
        return targetPopulation() * com.bannerbound.core.Config.FOOD_PER_CITIZEN_PER_DAY.get()
            / SECONDS_PER_GAME_DAY;
    }

    public double effectiveConsumptionPerSecond() {
        double raw = foodConsumptionPerSecond();
        if (raw <= 0.0) return 0.0;
        double bonus = statusBonusFor(StatusEffectIcon.FOOD) + faithEffects.food();
        return Math.max(0.0, raw - bonus);
    }

    public double storedFoodValue() { return storedFoodValue; }
    public void setStoredFoodValue(double v) { this.storedFoodValue = Math.max(0.0, v); }
    public double storedFoodPerSecond() { return storedFoodPerSecond; }
    public void setStoredFoodPerSecond(double v) { this.storedFoodPerSecond = Math.max(0.0, v); }

    public double reserve() { return Math.max(0.0, foodStored); }

    public double reserveSeconds() {
        double net = effectiveFoodPerSecond();
        if (net >= 0.0) return Double.POSITIVE_INFINITY;
        return reserve() / (-net);
    }

    public double reserveDays() {
        double secs = reserveSeconds();
        return Double.isInfinite(secs) ? secs : secs / SECONDS_PER_GAME_DAY;
    }

    public boolean isStarving() {
        return effectiveConsumptionPerSecond() > 0.0 && reserve() <= 0.0;
    }

    public double effectiveFoodPerSecond() {
        if (governmentType == Government.NONE) {
            return foodPerSecond + storedFoodPerSecond
                + statusBonusFor(StatusEffectIcon.FOOD) + faithEffects.food();
        }
        return storedFoodPerSecond - effectiveConsumptionPerSecond();
    }

    public List<Conversation> activeConversations() { return activeConversations; }

    public Conversation findActiveConversationFor(UUID citizenId) {
        for (Conversation c : activeConversations) {
            if (c.isParticipant(citizenId)) return c;
        }
        return null;
    }

    public void startConversation(Conversation c) {
        activeConversations.add(c);
    }

    public void endConversation(Conversation c) {
        activeConversations.remove(c);
    }

    public double foodPerSecond() { return foodPerSecond; }
    public void setFoodPerSecond(double v) { this.foodPerSecond = v; }
    public double culturePerSecond() { return culturePerSecond; }
    public void setCulturePerSecond(double v) { this.culturePerSecond = v; }

    public double foodStored() { return foodStored; }
    public void setFoodStored(double v) { this.foodStored = v; }

    private transient int lastFoodWarningLevel = -1;
    public int lastFoodWarningLevel() { return lastFoodWarningLevel; }
    public void setLastFoodWarningLevel(int level) { this.lastFoodWarningLevel = level; }
    public double cultureStored() { return cultureStored; }
    public void setCultureStored(double v) { this.cultureStored = v; }

    // MUST default to 0, not Long.MIN_VALUE: now - MIN_VALUE overflows negative and freezes immigration forever.
    private transient long lastImmigrationTick = 0L;
    public long lastImmigrationTick() { return lastImmigrationTick; }
    public void setLastImmigrationTick(long tick) { this.lastImmigrationTick = tick; }

    private transient boolean dormant = false;
    public boolean isDormant() { return dormant; }
    public void setDormant(boolean v) { this.dormant = v; }

    public double nextFoodCost() {
        return BASE_IMMIGRATION_FOOD_COST * (population() + 1);
    }

    public double nextCultureCost() {
        return BASE_IMMIGRATION_CULTURE_COST * (population() + 1);
    }

    public double bonusFoodCapacity() { return bonusFoodCapacity; }
    public void addBonusFoodCapacity(double delta) {
        this.bonusFoodCapacity = Math.max(0.0, this.bonusFoodCapacity + delta);
    }

    public double bonusCultureCapacity() { return bonusCultureCapacity; }
    public void addBonusCultureCapacity(double delta) {
        this.bonusCultureCapacity = Math.max(0.0, this.bonusCultureCapacity + delta);
    }

    public double bonusCitizenSpeed() { return bonusCitizenSpeed; }
    public void addBonusCitizenSpeed(double delta) {
        this.bonusCitizenSpeed = Math.max(0.0, this.bonusCitizenSpeed + delta);
    }

    public String getCurrentToolAge() { return currentToolAge; }
    public void setCurrentToolAge(String id) { this.currentToolAge = id == null ? "" : id; }

    public net.minecraft.world.item.Item getToolForRole(String role) {
        if (currentToolAge.isEmpty()) return net.minecraft.world.item.Items.AIR;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return net.minecraft.world.item.Items.AIR;
        return age.tools().getOrDefault(role, net.minecraft.world.item.Items.AIR);
    }

    public int getChopTicksOrDefault(int defaultTicks) {
        if (currentToolAge.isEmpty()) return defaultTicks;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return defaultTicks;
        return age.chopTicks().orElse(defaultTicks);
    }

    public int getMineTicksOrDefault(int defaultTicks) {
        if (currentToolAge.isEmpty()) return defaultTicks;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return defaultTicks;
        return age.mineTicks().orElse(defaultTicks);
    }

    public int getHarvestTicksOrDefault(int defaultTicks) {
        if (currentToolAge.isEmpty()) return defaultTicks;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return defaultTicks;
        return age.harvestTicks().orElse(defaultTicks);
    }

    public double getWeaponDamageOrDefault(double defaultDamage) {
        if (currentToolAge.isEmpty()) return defaultDamage;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        return age == null ? defaultDamage : age.weaponDamage();
    }

    public double getWeaponAttackSpeedOrDefault(double defaultAttackSpeed) {
        if (currentToolAge.isEmpty()) return defaultAttackSpeed;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        return age == null ? defaultAttackSpeed : age.weaponAttackSpeed();
    }

    public double foodCap() {
        return nextFoodCost() + bonusFoodCapacity;
    }

    public double cultureCap() {
        return nextCultureCost() + bonusCultureCapacity;
    }

    public Map<Long, Workstation> workstations() { return workstations; }
    public Workstation getWorkstation(BlockPos pos) { return workstations.get(pos.asLong()); }
    public void putWorkstation(Workstation ws) { workstations.put(ws.pos().asLong(), ws); }
    public Workstation removeWorkstation(BlockPos pos) { return workstations.remove(pos.asLong()); }

    public Workstation getWorkstationFor(UUID citizenId) {
        if (citizenId == null) return null;
        for (Workstation ws : workstations.values()) {
            if (citizenId.equals(ws.assignedCitizenId())) return ws;
        }
        return null;
    }

    public List<Citizen> unemployedCitizens() {
        Set<UUID> employed = new HashSet<>();
        for (Workstation ws : workstations.values()) {
            if (ws.assignedCitizenId() != null) employed.add(ws.assignedCitizenId());
        }
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : citizens) {
            if (!employed.contains(c.entityId())) out.add(c);
        }
        return out;
    }

    public Map<UUID, Home> homes() { return homes; }
    public void putHome(Home h) { homes.put(h.id(), h); }
    public Home removeHome(UUID homeId) { return homeId == null ? null : homes.remove(homeId); }

    public Home getHomeById(UUID homeId) {
        return homeId == null ? null : homes.get(homeId);
    }

    public Map<UUID, Workshop> workshops() { return workshops; }
    public Workshop getWorkshop(UUID workshopId) { return workshopId == null ? null : workshops.get(workshopId); }
    public void putWorkshop(Workshop w) { workshops.put(w.id(), w); }
    public Workshop removeWorkshop(UUID workshopId) { return workshops.remove(workshopId); }

    public Home getHomeFor(UUID citizenId) {
        if (citizenId == null) return null;
        for (Home h : homes.values()) {
            if (h.residents().contains(citizenId)) return h;
        }
        return null;
    }

    public List<Home> homesWithVacancy() {
        List<Home> out = new ArrayList<>();
        for (Home h : homes.values()) {
            if (h.hasVacancy()) out.add(h);
        }
        return out;
    }

    public List<Citizen> homelessCitizens() {
        Set<UUID> housed = new HashSet<>();
        for (Home h : homes.values()) {
            housed.addAll(h.residents());
        }
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : citizens) {
            if (!housed.contains(c.entityId())) out.add(c);
        }
        return out;
    }

    public Map<Long, Stockpile> stockpiles() { return stockpiles; }
    public Stockpile getStockpile(BlockPos pos) { return stockpiles.get(pos.asLong()); }
    public void putStockpile(Stockpile s) { stockpiles.put(s.pos().asLong(), s); }
    public Stockpile removeStockpile(BlockPos pos) { return stockpiles.remove(pos.asLong()); }

    public Stockpile getStockpileById(UUID stockpileId) {
        if (stockpileId == null) return null;
        for (Stockpile s : stockpiles.values()) {
            if (stockpileId.equals(s.id())) return s;
        }
        return null;
    }

    public String teamName() { return "civ_" + id.toString().substring(0, 8); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putLong("LanguageSeed", languageSeed);
        tag.putString("FactionName", factionName());
        tag.putInt("Color", color.ordinal());
        tag.putUUID("Owner", owner);
        ListTag memberList = new ListTag();
        for (UUID member : members) {
            memberList.add(NbtUtils.createUUID(member));
        }
        tag.put("Members", memberList);
        long[] claimsArr = new long[claimedChunks.size()];
        int i = 0;
        for (long c : claimedChunks) {
            claimsArr[i++] = c;
        }
        tag.putLongArray("Claims", claimsArr);
        if (!workingClaims.isEmpty()) {
            long[] workArr = new long[workingClaims.size()];
            int wi = 0;
            for (long c : workingClaims) workArr[wi++] = c;
            tag.putLongArray("WorkingClaims", workArr);
        }
        if (!outpostBanners.isEmpty()) {
            ListTag bannerList = new ListTag();
            for (java.util.Map.Entry<Long, BlockPos> e : outpostBanners.entrySet()) {
                CompoundTag ob = new CompoundTag();
                ob.putLong("C", e.getKey());
                ob.putLong("B", e.getValue().asLong());
                bannerList.add(ob);
            }
            tag.put("OutpostBanners", bannerList);
        }
        if (townHallPos != null) {
            CompoundTag th = new CompoundTag();
            th.putInt("X", townHallPos.getX());
            th.putInt("Y", townHallPos.getY());
            th.putInt("Z", townHallPos.getZ());
            tag.put("TownHall", th);
        }
        if (bannerPos != null) {
            CompoundTag fb = new CompoundTag();
            fb.putInt("X", bannerPos.getX());
            fb.putInt("Y", bannerPos.getY());
            fb.putInt("Z", bannerPos.getZ());
            tag.put("FactionBanner", fb);
        }
        if (!bannerDesign.isEmpty()) {
            ListTag designList = new ListTag();
            for (BannerLayer layer : bannerDesign) {
                CompoundTag l = new CompoundTag();
                l.putString("Pattern", layer.patternId());
                l.putInt("Color", layer.colorId());
                designList.add(l);
            }
            tag.put("BannerDesign", designList);
        }
        if (!identityDyeIds.isEmpty()) {
            int[] dyeArr = new int[identityDyeIds.size()];
            for (int di = 0; di < dyeArr.length; di++) dyeArr[di] = identityDyeIds.get(di);
            tag.putIntArray("IdentityDyes", dyeArr);
        }
        tag.putInt("TabletsIssued", tabletsIssued);
        tag.putInt("Age", age.ordinal());
        tag.putInt("ExpansionsUsed", expansionsUsed);
        tag.putInt("ImmigratedCount", immigratedCount);
        tag.putInt("PeakPopulation", peakPopulation);

        if (governmentType != Government.NONE) {
            tag.putInt("Government", governmentType.ordinal());
        }
        if (chiefPlayerId != null) {
            tag.putUUID("ChiefPlayer", chiefPlayerId);
            tag.putLong("ChiefSinceTick", chiefSinceTick);
        }
        if (codeOfLawsPromptShown) {
            tag.putBoolean("CodeOfLawsPromptShown", true);
        }
        if (faithId != null) {
            tag.putUUID("FaithId", faithId);
        }
        if (devotionStored > 0.0) {
            tag.putDouble("DevotionStored", devotionStored);
        }
        if (faithFoundingUnlocked) {
            tag.putBoolean("FaithFoundingUnlocked", true);
        }
        if (faithRejoinAfterGameTime > 0L) {
            tag.putLong("FaithRejoinAfterGameTime", faithRejoinAfterGameTime);
        }
        if (lastAnnouncedStage != SettlementStage.HEARTH) {
            tag.putInt("AnnouncedStage", lastAnnouncedStage.ordinal());
        }

        ListTag completed = new ListTag();
        for (String r : completedResearches) {
            completed.add(StringTag.valueOf(r));
        }
        tag.put("CompletedResearches", completed);

        CompoundTag progress = new CompoundTag();
        for (Map.Entry<String, Double> e : researchProgress.entrySet()) {
            progress.putDouble(e.getKey(), e.getValue());
        }
        tag.put("ResearchProgress", progress);

        CompoundTag insightCounts = new CompoundTag();
        for (Map.Entry<String, Integer> e : insightCounters.entrySet()) {
            insightCounts.putInt(e.getKey(), e.getValue());
        }
        tag.put("InsightCounters", insightCounts);
        ListTag firedInsightList = new ListTag();
        for (String key : firedInsights) firedInsightList.add(StringTag.valueOf(key));
        tag.put("FiredInsights", firedInsightList);

        ListTag queueList = new ListTag();
        for (String r : researchQueue) {
            queueList.add(StringTag.valueOf(r));
        }
        tag.put("ResearchQueue", queueList);

        if (activeResearch != null) {
            tag.putString("ActiveResearch", activeResearch);
        }
        tag.putDouble("SciencePerSecond", sciencePerSecond);

        ListTag completedCulture = new ListTag();
        for (String r : completedCultureResearches) completedCulture.add(StringTag.valueOf(r));
        tag.put("CompletedCultureResearches", completedCulture);
        CompoundTag cultureProgress = new CompoundTag();
        for (Map.Entry<String, Double> e : cultureResearchProgress.entrySet()) {
            cultureProgress.putDouble(e.getKey(), e.getValue());
        }
        tag.put("CultureResearchProgress", cultureProgress);
        ListTag cultureQueueList = new ListTag();
        for (String r : cultureResearchQueue) cultureQueueList.add(StringTag.valueOf(r));
        tag.put("CultureResearchQueue", cultureQueueList);
        if (activeCultureResearch != null) {
            tag.putString("ActiveCultureResearch", activeCultureResearch);
        }

        ListTag policyList = new ListTag();
        for (String p : activePolicies) policyList.add(StringTag.valueOf(p));
        tag.put("ActivePolicies", policyList);
        if (policyOpinionatedBonusExpiry > 0L) {
            tag.putLong("PolicyOpinionatedBonusExpiry", policyOpinionatedBonusExpiry);
        }
        ListTag paletteList = new ListTag();
        for (String p : activePalettes) paletteList.add(StringTag.valueOf(p));
        tag.put("ActivePalettes", paletteList);

        ListTag laborList = new ListTag();
        for (String j : laborPriority) laborList.add(StringTag.valueOf(j));
        tag.put("LaborPriority", laborList);
        ListTag laborDisabledList = new ListTag();
        for (String j : laborDisabled) laborDisabledList.add(StringTag.valueOf(j));
        tag.put("LaborDisabled", laborDisabledList);
        tag.putBoolean("LaborAutoAssign", laborAutoAssign);
        CompoundTag laborCapsTag = new CompoundTag();
        for (Map.Entry<String, Integer> e : laborCaps.entrySet()) laborCapsTag.putInt(e.getKey(), e.getValue());
        tag.put("LaborCaps", laborCapsTag);
        if (preferredStoragePos != null) tag.putLong("PreferredStorage", preferredStoragePos.asLong());

        ListTag citizensList = new ListTag();
        for (Citizen c : citizens) {
            citizensList.add(c.save());
        }
        tag.put("Citizens", citizensList);
        tag.putDouble("FoodPerSecond", foodPerSecond);
        tag.putDouble("CulturePerSecond", culturePerSecond);
        tag.putDouble("FoodStored", foodStored);
        tag.putDouble("CultureStored", cultureStored);
        tag.putDouble("BonusFoodCapacity", bonusFoodCapacity);
        tag.putDouble("BonusCultureCapacity", bonusCultureCapacity);
        tag.putDouble("BonusCitizenSpeed", bonusCitizenSpeed);
        if (!currentToolAge.isEmpty()) {
            tag.putString("CurrentToolAge", currentToolAge);
        }
        if (!cultureStyles.isEmpty()) {
            ListTag styleList = new ListTag();
            for (String style : cultureStyles) {
                styleList.add(StringTag.valueOf(style));
            }
            tag.put("CultureStyles", styleList);
        }

        ListTag wsList = new ListTag();
        for (Workstation ws : workstations.values()) {
            wsList.add(ws.save());
        }
        tag.put("Workstations", wsList);

        if (!homes.isEmpty()) {
            ListTag homeList = new ListTag();
            for (Home h : homes.values()) {
                homeList.add(h.save());
            }
            tag.put("Homes", homeList);
        }

        if (!workshops.isEmpty()) {
            ListTag wkList = new ListTag();
            for (Workshop w : workshops.values()) {
                wkList.add(w.save());
            }
            tag.put("Workshops", wkList);
        }

        if (!stockpiles.isEmpty()) {
            ListTag spList = new ListTag();
            for (Stockpile s : stockpiles.values()) {
                spList.add(s.save());
            }
            tag.put("Stockpiles", spList);
        }

        if (!journalEntries.isEmpty()) {
            ListTag journalList = new ListTag();
            for (com.bannerbound.core.journal.JournalEntry entry : journalEntries.values()) {
                journalList.add(entry.save());
            }
            tag.put("JournalEntries", journalList);
        }
        if (activeCrisis != null) {
            tag.put("ActiveCrisis", activeCrisis.save());
        }
        if (!completedCrises.isEmpty()) {
            ListTag list = new ListTag();
            for (String id : completedCrises) list.add(StringTag.valueOf(id));
            tag.put("CompletedCrises", list);
        }
        if (!failedCrises.isEmpty()) {
            ListTag list = new ListTag();
            for (String id : failedCrises) list.add(StringTag.valueOf(id));
            tag.put("FailedCrises", list);
        }
        if (!foodSourcePulses.isEmpty()) {
            ListTag sourceList = new ListTag();
            for (FoodSourcePulse pulse : foodSourcePulses) sourceList.add(pulse.save());
            tag.put("FoodSourcePulses", sourceList);
        }
        if (!foodProducedBySource.isEmpty()) {
            CompoundTag producedTag = new CompoundTag();
            for (Map.Entry<String, Double> e : foodProducedBySource.entrySet()) {
                producedTag.putDouble(e.getKey(), e.getValue());
            }
            tag.put("FoodProducedBySource", producedTag);
        }
        if (!outpostAccrued.isEmpty()) {
            CompoundTag oa = new CompoundTag();
            for (Map.Entry<String, Integer> e : outpostAccrued.entrySet()) {
                oa.putInt(e.getKey(), e.getValue());
            }
            tag.put("OutpostAccrued", oa);
        }

        ListTag statusList = new ListTag();
        for (StatusEffect e : statusEffects) {
            statusList.add(e.save());
        }
        tag.put("StatusEffects", statusList);
        return tag;
    }

    public static Settlement load(CompoundTag tag) {
        UUID id = tag.getUUID("Id");
        String name = tag.getString("Name");
        String factionName = tag.contains("FactionName") ? tag.getString("FactionName") : name;
        SettlementColor color = SettlementColor.byIndex(tag.getInt("Color"));
        UUID owner = tag.getUUID("Owner");
        Set<UUID> members = new HashSet<>();
        ListTag memberList = tag.getList("Members", 11);
        for (int i = 0; i < memberList.size(); i++) {
            members.add(NbtUtils.loadUUID(memberList.get(i)));
        }
        if (members.isEmpty()) {
            members.add(owner);
        }
        Set<Long> claims = new LinkedHashSet<>();
        long[] claimsArr = tag.getLongArray("Claims");
        for (long c : claimsArr) {
            claims.add(c);
        }
        BlockPos townHallPos = null;
        if (tag.contains("TownHall")) {
            CompoundTag th = tag.getCompound("TownHall");
            townHallPos = new BlockPos(th.getInt("X"), th.getInt("Y"), th.getInt("Z"));
        }
        int tabletsIssued = tag.contains("TabletsIssued")
            ? tag.getInt("TabletsIssued")
            : (tag.getBoolean("IssuedTablet") ? 1 : 0);
        Era age = tag.contains("Age")
            ? Era.fromOrdinalOrDefault(tag.getInt("Age"))
            : Era.ANCIENT;

        Set<String> completed = new HashSet<>();
        ListTag completedList = tag.getList("CompletedResearches", Tag.TAG_STRING);
        for (int i = 0; i < completedList.size(); i++) {
            completed.add(completedList.getString(i));
        }

        Set<String> knownItems = computeKnownItems(completed);

        Map<String, Double> progress = new HashMap<>();
        if (tag.contains("ResearchProgress")) {
            CompoundTag p = tag.getCompound("ResearchProgress");
            for (String key : p.getAllKeys()) {
                progress.put(key, p.getDouble(key));
            }
        }
        List<String> queue = new ArrayList<>();
        if (tag.contains("ResearchQueue")) {
            ListTag q = tag.getList("ResearchQueue", Tag.TAG_STRING);
            for (int i = 0; i < q.size(); i++) {
                queue.add(q.getString(i));
            }
        }
        String active = tag.contains("ActiveResearch") ? tag.getString("ActiveResearch") : null;
        if (active != null && active.isEmpty()) {
            active = null;
        }
        double sciPerSec = tag.contains("SciencePerSecond")
            ? tag.getDouble("SciencePerSecond")
            : DEFAULT_SCIENCE_PER_SECOND;

        List<Citizen> citizens = new ArrayList<>();
        if (tag.contains("Citizens")) {
            ListTag cList = tag.getList("Citizens", Tag.TAG_COMPOUND);
            for (int i = 0; i < cList.size(); i++) {
                citizens.add(Citizen.load(cList.getCompound(i)));
            }
        }
        double foodPerSec = tag.contains("FoodPerSecond")
            ? tag.getDouble("FoodPerSecond") : DEFAULT_FOOD_PER_SECOND;
        double culturePerSec = tag.contains("CulturePerSecond")
            ? tag.getDouble("CulturePerSecond") : DEFAULT_CULTURE_PER_SECOND;
        double foodStored = tag.contains("FoodStored") ? tag.getDouble("FoodStored") : 0.0;
        double cultureStored = tag.contains("CultureStored") ? tag.getDouble("CultureStored") : 0.0;
        double bonusFoodCap = tag.contains("BonusFoodCapacity") ? tag.getDouble("BonusFoodCapacity") : 0.0;
        double bonusCultureCap = tag.contains("BonusCultureCapacity") ? tag.getDouble("BonusCultureCapacity") : 0.0;
        double bonusCitizenSpd = tag.contains("BonusCitizenSpeed") ? tag.getDouble("BonusCitizenSpeed") : 0.0;
        long languageSeed = tag.contains("LanguageSeed")
            ? tag.getLong("LanguageSeed")
            : com.bannerbound.core.language.SettlementLanguage.deriveSeed(id, name);

        Map<Long, Workstation> workstations = new HashMap<>();
        if (tag.contains("Workstations")) {
            ListTag wsList = tag.getList("Workstations", Tag.TAG_COMPOUND);
            for (int i = 0; i < wsList.size(); i++) {
                Workstation ws = Workstation.load(wsList.getCompound(i));
                workstations.put(ws.pos().asLong(), ws);
            }
        }

        Settlement settlement = new Settlement(id, name, color, owner, members, claims, townHallPos, tabletsIssued, age,
            completed, knownItems, progress, queue, active, sciPerSec,
            citizens, foodPerSec, culturePerSec, foodStored, cultureStored,
            bonusFoodCap, bonusCultureCap, bonusCitizenSpd, workstations, languageSeed);

        settlement.setFactionName(factionName);

        if (tag.contains("InsightCounters")) {
            CompoundTag counts = tag.getCompound("InsightCounters");
            for (String key : counts.getAllKeys()) settlement.insightCounters.put(key, counts.getInt(key));
        }
        ListTag firedInsightList = tag.getList("FiredInsights", Tag.TAG_STRING);
        for (int i = 0; i < firedInsightList.size(); i++) {
            settlement.firedInsights.add(firedInsightList.getString(i));
        }
        if (tag.contains("CurrentToolAge")) {
            settlement.currentToolAge = tag.getString("CurrentToolAge");
        }
        for (long c : tag.getLongArray("WorkingClaims")) {
            settlement.workingClaims.add(c);
        }
        if (tag.contains("OutpostBanners")) {
            ListTag bannerList = tag.getList("OutpostBanners", Tag.TAG_COMPOUND);
            for (int i = 0; i < bannerList.size(); i++) {
                CompoundTag ob = bannerList.getCompound(i);
                settlement.outpostBanners.put(ob.getLong("C"), BlockPos.of(ob.getLong("B")));
            }
        }
        if (tag.contains("FactionBanner")) {
            CompoundTag fb = tag.getCompound("FactionBanner");
            settlement.bannerPos = new BlockPos(fb.getInt("X"), fb.getInt("Y"), fb.getInt("Z"));
        }
        if (tag.contains("BannerDesign")) {
            ListTag designList = tag.getList("BannerDesign", Tag.TAG_COMPOUND);
            for (int i = 0; i < designList.size(); i++) {
                CompoundTag l = designList.getCompound(i);
                settlement.bannerDesign.add(new BannerLayer(l.getString("Pattern"), l.getInt("Color")));
            }
        }
        if (tag.contains("IdentityDyes")) {
            for (int dyeId : tag.getIntArray("IdentityDyes")) settlement.identityDyeIds.add(dyeId);
        } else if (tag.contains("PrimaryDye")) {
            settlement.identityDyeIds.add(tag.getInt("PrimaryDye"));
            if (tag.contains("SecondaryDye")
                    && tag.getInt("SecondaryDye") != tag.getInt("PrimaryDye")) {
                settlement.identityDyeIds.add(tag.getInt("SecondaryDye"));
            }
            if (tag.contains("TertiaryDye")
                    && !settlement.identityDyeIds.contains(tag.getInt("TertiaryDye"))) {
                settlement.identityDyeIds.add(tag.getInt("TertiaryDye"));
            }
        }
        if (tag.contains("ExpansionsUsed")) {
            settlement.expansionsUsed = tag.getInt("ExpansionsUsed");
        } else if (tag.contains("ExpansionsInEra")) {
            settlement.expansionsUsed = tag.getInt("ExpansionsInEra");
        }
        if (tag.contains("ImmigratedCount")) {
            settlement.immigratedCount = tag.getInt("ImmigratedCount");
        } else {
            // Pre-cap saves: count the roster as already immigrated, else old worlds get 7 free immigrants.
            settlement.immigratedCount = settlement.citizens.size();
        }
        if (tag.contains("PeakPopulation")) {
            settlement.peakPopulation = tag.getInt("PeakPopulation");
        } else {
            settlement.peakPopulation = settlement.citizens.size();
        }
        if (tag.contains("CultureStyles")) {
            ListTag styleList = tag.getList("CultureStyles", Tag.TAG_STRING);
            for (int i = 0; i < styleList.size(); i++) {
                settlement.cultureStyles.add(styleList.getString(i));
            }
        }
        if (tag.contains("Government")) {
            int ord = tag.getInt("Government");
            Government[] vals = Government.values();
            if (ord >= 0 && ord < vals.length) {
                settlement.governmentType = vals[ord];
            }
        }
        if (tag.hasUUID("ChiefPlayer")) {
            settlement.chiefPlayerId = tag.getUUID("ChiefPlayer");
            settlement.chiefSinceTick = tag.contains("ChiefSinceTick") ? tag.getLong("ChiefSinceTick") : -1L;
        }
        if (tag.contains("AnnouncedStage")) {
            int ord = tag.getInt("AnnouncedStage");
            SettlementStage[] v = SettlementStage.values();
            if (ord >= 0 && ord < v.length) settlement.lastAnnouncedStage = v[ord];
        }
        if (tag.contains("CodeOfLawsPromptShown")) {
            settlement.codeOfLawsPromptShown = tag.getBoolean("CodeOfLawsPromptShown");
        }
        if (tag.hasUUID("FaithId")) {
            settlement.faithId = tag.getUUID("FaithId");
        }
        if (tag.contains("DevotionStored")) {
            settlement.devotionStored = tag.getDouble("DevotionStored");
        }
        if (tag.contains("FaithFoundingUnlocked")) {
            settlement.faithFoundingUnlocked = tag.getBoolean("FaithFoundingUnlocked");
        }
        if (tag.contains("FaithRejoinAfterGameTime")) {
            settlement.faithRejoinAfterGameTime = tag.getLong("FaithRejoinAfterGameTime");
        }
        if (tag.contains("CompletedCultureResearches")) {
            ListTag cList = tag.getList("CompletedCultureResearches", Tag.TAG_STRING);
            for (int i = 0; i < cList.size(); i++) {
                settlement.completedCultureResearches.add(cList.getString(i));
            }

            // add to knownItems
            for (String cultureResearchID : settlement.completedCultureResearches()) {
                ResearchDefinition def = CultureTreeLoader.get(cultureResearchID);
                if (def != null) settlement.knownItems.addAll(def.unlocksItems());
            }
        }
        if (tag.contains("CultureResearchProgress")) {
            CompoundTag p = tag.getCompound("CultureResearchProgress");
            for (String key : p.getAllKeys()) {
                settlement.cultureResearchProgress.put(key, p.getDouble(key));
            }
        }
        if (tag.contains("CultureResearchQueue")) {
            ListTag q = tag.getList("CultureResearchQueue", Tag.TAG_STRING);
            for (int i = 0; i < q.size(); i++) {
                settlement.cultureResearchQueue.add(q.getString(i));
            }
        }
        if (tag.contains("ActiveCultureResearch")) {
            String ac = tag.getString("ActiveCultureResearch");
            if (ac != null && !ac.isEmpty()) settlement.activeCultureResearch = ac;
        }
        if (tag.contains("ActivePalettes")) {
            ListTag pal = tag.getList("ActivePalettes", Tag.TAG_STRING);
            for (int i = 0; i < pal.size(); i++) {
                settlement.activePalettes.add(pal.getString(i));
            }
        }
        if (tag.contains("ActivePolicies")) {
            ListTag pl = tag.getList("ActivePolicies", Tag.TAG_STRING);
            for (int i = 0; i < pl.size(); i++) {
                settlement.activePolicies.add(pl.getString(i));
            }
        }
        if (tag.contains("LaborPriority")) {
            ListTag ll = tag.getList("LaborPriority", Tag.TAG_STRING);
            for (int i = 0; i < ll.size(); i++) settlement.laborPriority.add(ll.getString(i));
        }
        if (tag.contains("LaborDisabled")) {
            ListTag ld = tag.getList("LaborDisabled", Tag.TAG_STRING);
            for (int i = 0; i < ld.size(); i++) settlement.laborDisabled.add(ld.getString(i));
        }
        settlement.laborAutoAssign = !tag.contains("LaborAutoAssign") || tag.getBoolean("LaborAutoAssign");
        if (tag.contains("LaborCaps")) {
            CompoundTag lc = tag.getCompound("LaborCaps");
            for (String key : lc.getAllKeys()) settlement.laborCaps.put(key, lc.getInt(key));
        }
        if (tag.contains("PreferredStorage")) {
            settlement.preferredStoragePos = BlockPos.of(tag.getLong("PreferredStorage"));
        }
        if (tag.contains("PolicyOpinionatedBonusExpiry")) {
            settlement.policyOpinionatedBonusExpiry = tag.getLong("PolicyOpinionatedBonusExpiry");
        }
        if (tag.contains("StatusEffects")) {
            ListTag statusList = tag.getList("StatusEffects", Tag.TAG_COMPOUND);
            for (int i = 0; i < statusList.size(); i++) {
                settlement.statusEffects.add(StatusEffect.load(statusList.getCompound(i)));
            }
        }
        if (tag.contains("JournalEntries")) {
            ListTag journalList = tag.getList("JournalEntries", Tag.TAG_COMPOUND);
            for (int i = 0; i < journalList.size(); i++) {
                com.bannerbound.core.journal.JournalEntry entry =
                    com.bannerbound.core.journal.JournalEntry.load(journalList.getCompound(i));
                settlement.journalEntries.put(entry.instanceId(), entry);
            }
        }
        if (tag.contains("ActiveCrisis")) {
            settlement.activeCrisis =
                com.bannerbound.core.crisis.CrisisState.load(tag.getCompound("ActiveCrisis"));
        }
        if (tag.contains("CompletedCrises")) {
            ListTag list = tag.getList("CompletedCrises", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) settlement.completedCrises.add(list.getString(i));
        }
        if (tag.contains("FailedCrises")) {
            ListTag list = tag.getList("FailedCrises", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) settlement.failedCrises.add(list.getString(i));
        }
        if (tag.contains("FoodSourcePulses")) {
            ListTag sourceList = tag.getList("FoodSourcePulses", Tag.TAG_COMPOUND);
            for (int i = 0; i < sourceList.size(); i++) {
                FoodSourcePulse pulse = FoodSourcePulse.load(sourceList.getCompound(i));
                if (pulse.remainingTicks() > 0) settlement.foodSourcePulses.add(pulse);
            }
        }
        if (tag.contains("FoodProducedBySource")) {
            CompoundTag producedTag = tag.getCompound("FoodProducedBySource");
            for (String key : producedTag.getAllKeys()) {
                settlement.foodProducedBySource.put(key, producedTag.getDouble(key));
            }
        }
        if (tag.contains("OutpostAccrued")) {
            CompoundTag oa = tag.getCompound("OutpostAccrued");
            for (String key : oa.getAllKeys()) {
                settlement.outpostAccrued.put(key, oa.getInt(key));
            }
        }
        if (tag.contains("Homes")) {
            ListTag homeList = tag.getList("Homes", Tag.TAG_COMPOUND);
            for (int i = 0; i < homeList.size(); i++) {
                Home h = Home.load(homeList.getCompound(i));
                settlement.homes.put(h.id(), h);
            }
        }
        if (tag.contains("Workshops")) {
            ListTag wkList = tag.getList("Workshops", Tag.TAG_COMPOUND);
            for (int i = 0; i < wkList.size(); i++) {
                Workshop w = Workshop.load(wkList.getCompound(i));
                settlement.workshops.put(w.id(), w);
            }
        }
        if (tag.contains("Stockpiles")) {
            ListTag spList = tag.getList("Stockpiles", Tag.TAG_COMPOUND);
            for (int i = 0; i < spList.size(); i++) {
                Stockpile s = Stockpile.load(spList.getCompound(i));
                settlement.stockpiles.put(s.pos().asLong(), s);
            }
        }
        return settlement;
    }

    public void addStatusEffect(StatusEffect effect) {
        statusEffects.add(effect);
    }

    public List<com.bannerbound.core.journal.JournalEntry> journalEntries() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(journalEntries.values()));
    }

    public void putJournalEntry(com.bannerbound.core.journal.JournalEntry entry) {
        if (entry != null) journalEntries.put(entry.instanceId(), entry);
    }

    public boolean removeJournalEntry(UUID instanceId) {
        return journalEntries.remove(instanceId) != null;
    }

    public com.bannerbound.core.journal.JournalEntry journalEntry(UUID instanceId) {
        return journalEntries.get(instanceId);
    }

    public com.bannerbound.core.journal.JournalEntry findJournalEntry(String sourceType, String sourceId) {
        for (com.bannerbound.core.journal.JournalEntry entry : journalEntries.values()) {
            if (entry.sourceType().equals(sourceType) && entry.sourceId().equals(sourceId)) return entry;
        }
        return null;
    }

    public com.bannerbound.core.crisis.CrisisState activeCrisis() {
        return activeCrisis;
    }

    public void setActiveCrisis(com.bannerbound.core.crisis.CrisisState activeCrisis) {
        this.activeCrisis = activeCrisis;
    }

    public Set<String> completedCrises() {
        return java.util.Collections.unmodifiableSet(completedCrises);
    }

    public Set<String> failedCrises() {
        return java.util.Collections.unmodifiableSet(failedCrises);
    }

    public void markCrisisResolved(String crisisId, boolean failed) {
        if (crisisId == null || crisisId.isBlank()) return;
        completedCrises.add(crisisId);
        if (failed) failedCrises.add(crisisId);
    }

    public void setPassiveFoodSourceRate(String source, double rate) {
        if (source == null || source.isBlank()) return;
        if (Math.abs(rate) < 0.000_001) passiveFoodSourceRates.remove(source);
        else passiveFoodSourceRates.put(source, rate);
    }

    public double foodSourceRate(String source) {
        double total = passiveFoodSourceRates.getOrDefault(source, 0.0);
        for (FoodSourcePulse pulse : foodSourcePulses) {
            if (pulse.source().equals(source)) total += pulse.amount();
        }
        return total;
    }

    public Map<String, Double> foodSourceRates() {
        Map<String, Double> rates = new HashMap<>(passiveFoodSourceRates);
        for (FoodSourcePulse pulse : foodSourcePulses) {
            rates.merge(pulse.source(), pulse.amount(), Double::sum);
        }
        return java.util.Collections.unmodifiableMap(rates);
    }

    public boolean tickFoodSourcePulses() {
        if (foodSourcePulses.isEmpty()) return false;
        boolean changed = false;
        for (int i = foodSourcePulses.size() - 1; i >= 0; i--) {
            FoodSourcePulse pulse = foodSourcePulses.get(i);
            FoodSourcePulse next = pulse.tickDown();
            if (next.remainingTicks() <= 0) {
                foodSourcePulses.remove(i);
                changed = true;
            } else {
                foodSourcePulses.set(i, next);
            }
        }
        return changed;
    }

    public void addFoodProduced(String source, double amount) {
        if (source == null || source.isBlank() || amount <= 0.0) return;
        foodProducedBySource.merge(source, amount, Double::sum);
    }

    public double foodProducedFrom(String source) {
        return foodProducedBySource.getOrDefault(source, 0.0);
    }

    public void addOutpostAccrued(long chunk, int amount, int cap) {
        if (amount <= 0) return;
        outpostAccrued.merge(Long.toString(chunk), amount, (a, b) -> Math.min(cap, a + b));
    }

    public int outpostAccrued(long chunk) {
        return outpostAccrued.getOrDefault(Long.toString(chunk), 0);
    }

    public void takeOutpostAccrued(long chunk, int amount) {
        if (amount <= 0) return;
        String k = Long.toString(chunk);
        int next = outpostAccrued.getOrDefault(k, 0) - amount;
        if (next <= 0) outpostAccrued.remove(k); else outpostAccrued.put(k, next);
    }

    public Map<String, Integer> outpostAccruedAll() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(outpostAccrued));
    }

    public Map<String, Double> foodProducedTotals() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(foodProducedBySource));
    }

    public double foodProductionRate(String source) {
        return source == null ? 0.0 : foodProductionRate.getOrDefault(source, 0.0);
    }

    public Map<String, Double> foodProductionRates() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(foodProductionRate));
    }

    public double totalFoodProductionRate() {
        double sum = 0.0;
        for (double r : foodProductionRate.values()) sum += r;
        return sum;
    }

    public long netFoodStableTicks(long gameTick) {
        return netFoodOkSinceTick < 0 ? 0L : Math.max(0L, gameTick - netFoodOkSinceTick);
    }

    public void tickFoodEconomyStats(long gameTick) {
        java.util.Set<String> sources = new HashSet<>(foodProducedBySource.keySet());
        sources.addAll(foodProductionRate.keySet());
        for (String src : sources) {
            double total = foodProducedBySource.getOrDefault(src, 0.0);
            double inst = Math.max(0.0, total - lastProducedSnapshot.getOrDefault(src, total));
            double ema = foodProductionRate.getOrDefault(src, 0.0);
            ema += PRODUCTION_RATE_ALPHA * (inst - ema);
            if (ema < 1.0e-4) foodProductionRate.remove(src); else foodProductionRate.put(src, ema);
            lastProducedSnapshot.put(src, total);
        }
        // Do NOT sum storedFoodPerSecond here: it is passive stock income, not a production flow (a one-time stockpile must never pass the food_sustained gate).
        if (effectiveFoodPerSecond() >= 0.0) {
            if (netFoodOkSinceTick < 0) netFoodOkSinceTick = gameTick;
        } else {
            netFoodOkSinceTick = -1L;
        }
    }

    private void addOrRenewFoodSourcePulse(UUID instanceId, String source, double amount, int ticks) {
        if (source == null || source.isBlank() || ticks <= 0 || Math.abs(amount) < 0.000_001) return;
        foodSourcePulses.removeIf(p -> p.instanceId().equals(instanceId));
        foodSourcePulses.add(new FoodSourcePulse(instanceId, source, amount, ticks));
    }

    public void addOrRenewStatusEffect(StatusEffect effect) {
        statusEffects.removeIf(e -> e.instanceId().equals(effect.instanceId()));
        statusEffects.add(effect);
    }

    public boolean removeStatusEffectsByKey(String translationKey) {
        return statusEffects.removeIf(e -> e.translationKey().equals(translationKey));
    }

    public List<StatusEffect> statusEffects() {
        return java.util.Collections.unmodifiableList(statusEffects);
    }

    public boolean tickStatusEffects() {
        if (statusEffects.isEmpty()) return false;
        boolean changed = false;
        java.util.Iterator<StatusEffect> it = statusEffects.iterator();
        while (it.hasNext()) {
            StatusEffect e = it.next();
            if (e.tickDown()) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    public double statusBonusFor(StatusEffectIcon icon) {
        double sum = 0.0;
        for (StatusEffect e : statusEffects) {
            if (e.icon() == icon) sum += e.iconValue();
        }
        return sum;
    }
}
