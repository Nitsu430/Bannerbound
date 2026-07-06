package com.bannerbound.core.citystate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * One AI city-state -- a discovered vanilla village repurposed as a diplomatic actor (see the
 * CITY_STATES plan). Mutable record-style class persisted inside {@link CityStateData}, mirroring the
 * {@code BarbarianCamp} save/load pattern.
 *
 * <p>We DO NOT touch the village's villagers or buildings (mod compatibility). The only block we add
 * is a faction banner beside the village centre -- the future capture objective. The city-state's
 * economy is an abstract, grounded trade-stock ledger ({@link #ledger}); it never fills physical
 * chests off-screen (CITY_STATES anti-cheat). Grounding scans (job POIs, resource chunks, counted
 * homes) are persisted so {@link CityStateEconomy} can run the whole economy off-screen without ever
 * loading a chunk; prosperity breathes population/tech/garrison around the bed-counted baseline.
 *
 * <p>War state lives in {@link #wars}, keyed by the attacking settlement -- more than one settlement
 * can war one city-state at once, and capture goes to whoever scores the carryable banner standard
 * first (mirrors {@code SettlementData.StolenStandard}). {@link #atWar} is a dead legacy flag kept
 * only so old saves still deserialize.
 *
 * <p>Save-format invariants: {@link #realized} and the transient economy caches are never saved
 * (realized is forced false on load, since no entities persist). {@link #ECON_VERSION} gates a
 * one-time migration -- saves below 2 (the pre-living-economy placeholder) get {@link #ledger}
 * cleared on load and reseeded from the current catalog on the next economy tick, so old worlds
 * converge in one tick with no stale sand/stick stock.
 */
public final class CityState {
    public final UUID id;
    public BlockPos center;
    public BlockPos bannerPos;
    public ResourceLocation biome;
    public long languageSeed;
    public String name = "";
    public CityStateDifficulty difficulty = CityStateDifficulty.MEDIUM;

    public final Map<String, Integer> ledger = new LinkedHashMap<>();
    public final Set<String> knownTech = new HashSet<>();
    public double techProgress;
    public int believedPop = BASE_POP;
    public long lastEconomyTick;

    public int countedHomes = BASE_POP;
    public int popDrift;
    public double prosperity = 0.5;
    public double fedRatio = 1.0;
    public double tradeVolume;
    public long dayIndex;
    public final Map<String, Integer> jobPois = new LinkedHashMap<>();
    public final Map<String, Integer> resourceChunks = new LinkedHashMap<>();
    public final Set<Long> scannedChunks = new HashSet<>();
    public final Map<String, Integer> imports = new LinkedHashMap<>();
    public final java.util.List<Demand> demands = new java.util.ArrayList<>();
    public final Map<String, Long> demandCooldowns = new LinkedHashMap<>();

    public static final class Demand {
        public String item;
        public int qtyRemaining;
        public long createdDay;

        public Demand(String item, int qtyRemaining, long createdDay) {
            this.item = item;
            this.qtyRemaining = qtyRemaining;
            this.createdDay = createdDay;
        }
    }

    public transient CityStateEconomy.ActiveGoods activeGoodsCache;
    public transient Map<String, Double> prodRemainder = new HashMap<>();
    public transient double eatenValueToday;
    public transient double neededValueToday;
    public transient double foodDebt;
    public transient double wantDebt;

    public boolean bannerStamped;
    public boolean bannerRazed;
    public boolean atWar;
    public UUID vassalOf;

    public final Set<Long> claimedChunks = new HashSet<>();

    public transient boolean realized;

    public final Map<UUID, Integer> relScore = new HashMap<>();
    public final Set<UUID> discoveredBy = new HashSet<>();

    public final Map<UUID, CityStateWar> wars = new HashMap<>();

    public static final class CityStateWar {
        public int pendingTicks;
        public boolean active;
        public long startedAt;
        public boolean peaceOffered;
        public long redeclareAfter;
        public long capturedAt;
    }

    public boolean standardInPlay;
    public UUID standardCarrier;
    public BlockPos standardDroppedPos;
    public long standardDroppedAt;
    public long standardAutoReturnAt;

    public CityStateWar warWith(UUID settlementId) {
        return settlementId == null ? null : wars.get(settlementId);
    }

    public CityStateWar getOrCreateWar(UUID settlementId) {
        return wars.computeIfAbsent(settlementId, k -> new CityStateWar());
    }

    public boolean isActiveEnemy(UUID settlementId) {
        CityStateWar w = warWith(settlementId);
        return w != null && w.active && w.capturedAt == 0;
    }

    public boolean isFrozen() {
        for (CityStateWar w : wars.values()) {
            if (w.pendingTicks > 0 || w.active || w.capturedAt > 0) return true;
        }
        return false;
    }

    public boolean standardInPlayOrCaptured() {
        if (standardInPlay) return true;
        for (CityStateWar w : wars.values()) {
            if (w.capturedAt > 0) return true;
        }
        return false;
    }

    public UUID capturedBySettlement() {
        for (Map.Entry<UUID, CityStateWar> e : wars.entrySet()) {
            if (e.getValue().capturedAt > 0) return e.getKey();
        }
        return null;
    }

    public static final int BASE_POP = 6;

    public static final int ECON_VERSION = 2;

    public CityState(UUID id, BlockPos center, ResourceLocation biome) {
        this.id = id;
        this.center = center;
        this.bannerPos = center;
        this.biome = biome;
    }

    private CityState(UUID id) {
        this.id = id;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putLong("Center", center.asLong());
        if (bannerPos != null) tag.putLong("BannerPos", bannerPos.asLong());
        if (biome != null) tag.putString("Biome", biome.toString());
        tag.putLong("LanguageSeed", languageSeed);
        tag.putString("Name", name);
        tag.putString("Difficulty", difficulty.name());
        tag.putDouble("TechProgress", techProgress);
        tag.putInt("BelievedPop", believedPop);
        tag.putLong("LastEconomyTick", lastEconomyTick);
        tag.putBoolean("BannerStamped", bannerStamped);
        tag.putBoolean("BannerRazed", bannerRazed);
        tag.putBoolean("AtWar", atWar);
        if (vassalOf != null) tag.putUUID("VassalOf", vassalOf);

        long[] claims = new long[claimedChunks.size()];
        int ci = 0;
        for (long c : claimedChunks) claims[ci++] = c;
        tag.putLongArray("ClaimedChunks", claims);

        CompoundTag led = new CompoundTag();
        for (Map.Entry<String, Integer> e : ledger.entrySet()) led.putInt(e.getKey(), e.getValue());
        tag.put("Ledger", led);

        ListTag tech = new ListTag();
        for (String t : knownTech) {
            CompoundTag c = new CompoundTag();
            c.putString("T", t);
            tech.add(c);
        }
        tag.put("KnownTech", tech);

        ListTag rel = new ListTag();
        for (Map.Entry<UUID, Integer> e : relScore.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", e.getKey());
            c.putInt("V", e.getValue());
            rel.add(c);
        }
        tag.put("Relations", rel);

        ListTag disc = new ListTag();
        for (UUID u : discoveredBy) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", u);
            disc.add(c);
        }
        tag.put("DiscoveredBy", disc);

        ListTag warList = new ListTag();
        for (Map.Entry<UUID, CityStateWar> e : wars.entrySet()) {
            CityStateWar w = e.getValue();
            CompoundTag c = new CompoundTag();
            c.putUUID("S", e.getKey());
            c.putInt("Pending", w.pendingTicks);
            c.putBoolean("Active", w.active);
            c.putLong("Started", w.startedAt);
            c.putBoolean("Peace", w.peaceOffered);
            c.putLong("Redeclare", w.redeclareAfter);
            c.putLong("Captured", w.capturedAt);
            warList.add(c);
        }
        tag.put("Wars", warList);

        tag.putBoolean("StdInPlay", standardInPlay);
        if (standardCarrier != null) tag.putUUID("StdCarrier", standardCarrier);
        if (standardDroppedPos != null) tag.putLong("StdDroppedPos", standardDroppedPos.asLong());
        tag.putLong("StdDroppedAt", standardDroppedAt);
        tag.putLong("StdAutoReturn", standardAutoReturnAt);

        tag.putInt("EconVersion", ECON_VERSION);
        tag.putInt("CountedHomes", countedHomes);
        tag.putInt("PopDrift", popDrift);
        tag.putDouble("Prosperity", prosperity);
        tag.putDouble("FedRatio", fedRatio);
        tag.putDouble("TradeVolume", tradeVolume);
        tag.putLong("DayIndex", dayIndex);
        tag.put("JobPois", saveStringIntMap(jobPois));
        tag.put("ResourceChunks", saveStringIntMap(resourceChunks));
        tag.put("Imports", saveStringIntMap(imports));
        long[] scanned = new long[scannedChunks.size()];
        int si = 0;
        for (long c : scannedChunks) scanned[si++] = c;
        tag.putLongArray("ScannedChunks", scanned);
        ListTag demandList = new ListTag();
        for (Demand d : demands) {
            CompoundTag c = new CompoundTag();
            c.putString("Item", d.item);
            c.putInt("Qty", d.qtyRemaining);
            c.putLong("Day", d.createdDay);
            demandList.add(c);
        }
        tag.put("Demands", demandList);
        CompoundTag cds = new CompoundTag();
        for (Map.Entry<String, Long> e : demandCooldowns.entrySet()) cds.putLong(e.getKey(), e.getValue());
        tag.put("DemandCooldowns", cds);
        return tag;
    }

    private static CompoundTag saveStringIntMap(Map<String, Integer> map) {
        CompoundTag out = new CompoundTag();
        for (Map.Entry<String, Integer> e : map.entrySet()) out.putInt(e.getKey(), e.getValue());
        return out;
    }

    private static void loadStringIntMap(CompoundTag tag, Map<String, Integer> into) {
        for (String key : tag.getAllKeys()) into.put(key, tag.getInt(key));
    }

    static CityState load(CompoundTag tag) {
        if (!tag.hasUUID("Id")) return null;
        CityState cs = new CityState(tag.getUUID("Id"));
        cs.center = BlockPos.of(tag.getLong("Center"));
        cs.bannerPos = tag.contains("BannerPos") ? BlockPos.of(tag.getLong("BannerPos")) : cs.center;
        if (tag.contains("Biome")) cs.biome = ResourceLocation.tryParse(tag.getString("Biome"));
        cs.languageSeed = tag.getLong("LanguageSeed");
        cs.name = tag.getString("Name");
        if (cs.name.isBlank()) cs.name = CityStateNames.generate(cs.languageSeed);
        cs.difficulty = CityStateDifficulty.fromName(tag.getString("Difficulty"));
        cs.techProgress = tag.getDouble("TechProgress");
        cs.believedPop = tag.contains("BelievedPop") ? tag.getInt("BelievedPop") : BASE_POP;
        cs.lastEconomyTick = tag.getLong("LastEconomyTick");
        cs.bannerStamped = tag.getBoolean("BannerStamped");
        cs.bannerRazed = tag.getBoolean("BannerRazed");
        cs.atWar = tag.getBoolean("AtWar");
        if (tag.hasUUID("VassalOf")) cs.vassalOf = tag.getUUID("VassalOf");
        for (long c : tag.getLongArray("ClaimedChunks")) cs.claimedChunks.add(c);
        cs.realized = false;

        CompoundTag led = tag.getCompound("Ledger");
        for (String key : led.getAllKeys()) cs.ledger.put(key, led.getInt(key));

        ListTag tech = tag.getList("KnownTech", Tag.TAG_COMPOUND);
        for (int i = 0; i < tech.size(); i++) cs.knownTech.add(tech.getCompound(i).getString("T"));

        ListTag rel = tag.getList("Relations", Tag.TAG_COMPOUND);
        for (int i = 0; i < rel.size(); i++) {
            CompoundTag c = rel.getCompound(i);
            if (c.hasUUID("S")) cs.relScore.put(c.getUUID("S"), c.getInt("V"));
        }
        ListTag disc = tag.getList("DiscoveredBy", Tag.TAG_COMPOUND);
        for (int i = 0; i < disc.size(); i++) {
            CompoundTag c = disc.getCompound(i);
            if (c.hasUUID("S")) cs.discoveredBy.add(c.getUUID("S"));
        }
        ListTag warList = tag.getList("Wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < warList.size(); i++) {
            CompoundTag c = warList.getCompound(i);
            if (!c.hasUUID("S")) continue;
            CityStateWar w = new CityStateWar();
            w.pendingTicks = c.getInt("Pending");
            w.active = c.getBoolean("Active");
            w.startedAt = c.getLong("Started");
            w.peaceOffered = c.getBoolean("Peace");
            w.redeclareAfter = c.getLong("Redeclare");
            w.capturedAt = c.getLong("Captured");
            cs.wars.put(c.getUUID("S"), w);
        }
        cs.standardInPlay = tag.getBoolean("StdInPlay");
        if (tag.hasUUID("StdCarrier")) cs.standardCarrier = tag.getUUID("StdCarrier");
        if (tag.contains("StdDroppedPos")) cs.standardDroppedPos = BlockPos.of(tag.getLong("StdDroppedPos"));
        cs.standardDroppedAt = tag.getLong("StdDroppedAt");
        cs.standardAutoReturnAt = tag.getLong("StdAutoReturn");

        if (tag.getInt("EconVersion") < ECON_VERSION) {
            cs.ledger.clear(); // pre-catalog placeholder stock (sand/stick/cobble) reseeds next tick
        }
        cs.countedHomes = tag.contains("CountedHomes") ? tag.getInt("CountedHomes") : cs.believedPop;
        cs.popDrift = tag.getInt("PopDrift");
        cs.prosperity = tag.contains("Prosperity") ? tag.getDouble("Prosperity") : 0.5;
        cs.fedRatio = tag.contains("FedRatio") ? tag.getDouble("FedRatio") : 1.0;
        cs.tradeVolume = tag.getDouble("TradeVolume");
        cs.dayIndex = tag.getLong("DayIndex");
        loadStringIntMap(tag.getCompound("JobPois"), cs.jobPois);
        loadStringIntMap(tag.getCompound("ResourceChunks"), cs.resourceChunks);
        loadStringIntMap(tag.getCompound("Imports"), cs.imports);
        for (long c : tag.getLongArray("ScannedChunks")) cs.scannedChunks.add(c);
        ListTag demandList = tag.getList("Demands", Tag.TAG_COMPOUND);
        for (int i = 0; i < demandList.size(); i++) {
            CompoundTag c = demandList.getCompound(i);
            cs.demands.add(new Demand(c.getString("Item"), c.getInt("Qty"), c.getLong("Day")));
        }
        CompoundTag cds = tag.getCompound("DemandCooldowns");
        for (String key : cds.getAllKeys()) cs.demandCooldowns.put(key, cds.getLong(key));
        return cs;
    }
}
