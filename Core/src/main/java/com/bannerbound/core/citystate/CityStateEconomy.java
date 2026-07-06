package com.bannerbound.core.citystate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.citystate.data.CityStateGoodsLoader;
import com.bannerbound.core.api.citystate.data.CityStateGoodsLoader.GoodDef;
import com.bannerbound.core.api.citystate.data.CityStateWantsLoader;
import com.bannerbound.core.api.settlement.data.FoodValueLoader;
import com.bannerbound.core.barbarian.ItemValue;
import com.bannerbound.core.territory.ChunkResource;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The city-state <b>living economy</b> (CITY_STATES plan Phase 3): production, consumption, demand
 * slots, and the prosperity score that drives tech / population / garrison. Replaces the shipped
 * placeholder (flat accrual toward a pop cap + flat tech timer).
 *
 * <p>Everything is priced in {@link ItemValue} units and runs on {@code CityStateManager}'s 600-tick
 * economy clock (40 ticks/day) with a once-a-day pass on the day rollover -- pure map arithmetic over
 * numbers persisted by the {@code realizePass} grounding scans, so it never loads a chunk. The caller
 * has already skipped frozen city-states before {@link #tick} runs.
 *
 * <p>What a city-state produces comes from the data-driven catalog ({@link CityStateGoodsLoader}),
 * gated three ways: biome family x its own adopted tech x specialized resource chunks in its
 * territory, weighted by the village's real job-site POIs. What it DEMANDS (and pays a premium for --
 * the market for player surplus, consumed by trade in P4) is ranked: tech-gap (knows the tech, lacks
 * the chunk) > biome complement > the authored wants ladder, deterministic per (city-state, day) so
 * the picks don't flicker. The hard cap -- a city-state is never ahead of players on tech -- is
 * enforced by the {@code techCap} set passed in, never here. Tuning constants are ItemValue units and
 * in-game days; see the plan's calibration section.
 */
@ApiStatus.Internal
public final class CityStateEconomy {
    private static final double GROSS_VALUE_PER_POP_DAY = 2.0;
    private static final double EAT_VALUE_PER_POP_DAY = 1.0;
    private static final double WANT_DRAIN_PER_POP_DAY = 0.25;
    private static final int STOCK_DAYS = 8;
    private static final double TECH_RATE = 0.009;
    private static final int DEMAND_SLOTS = 3;
    private static final double DEMAND_VALUE_PER_POP = 1.5;
    private static final int DEMAND_EXPIRE_DAYS = 5;
    private static final int DEMAND_COOLDOWN_DAYS = 3;
    public static final double DEMAND_PREMIUM = 1.30;
    private static final int ECONOMY_TICKS_PER_DAY = 40; // 24000 / ECONOMY_INTERVAL_TICKS(600)
    private static final int MAX_POP = 64;

    private CityStateEconomy() {
    }

    public static void tick(CityState cs, long gameTime, Set<String> techCap) {
        long today = gameTime / 24000L;
        produce(cs);
        consume(cs);
        evolve(cs, techCap);
        if (cs.demands.isEmpty()) refillDemands(cs, today);
        if (today > cs.dayIndex) dailyPass(cs, today);
    }

    private static void produce(CityState cs) {
        ActiveGoods ag = activeGoods(cs);
        if (ag.food.isEmpty() && ag.nonFood.isEmpty()) return;
        double prosperityMult = 0.6 + 0.5 * cs.prosperity;
        double structureMult = Math.min(1.5, 1.0 + 0.05 * ag.totalJobPois);
        double grossPerDay = cs.believedPop * GROSS_VALUE_PER_POP_DAY
            * cs.difficulty.factor() * prosperityMult * structureMult;
        produceCategory(cs, ag.food, grossPerDay * 0.5);
        produceCategory(cs, ag.nonFood, grossPerDay * 0.5);
    }

    private static void produceCategory(CityState cs, List<ActiveGood> goods, double budgetPerDay) {
        if (goods.isEmpty()) return;
        double sumW = 0;
        for (ActiveGood g : goods) sumW += g.effectiveWeight;
        if (sumW <= 0) return;
        for (ActiveGood g : goods) {
            double itemsPerDay = budgetPerDay * (g.effectiveWeight / sumW) / g.unitValue;
            int cap = Math.max(4, (int) Math.ceil(STOCK_DAYS * itemsPerDay));
            Integer cur = cs.ledger.get(g.item);
            if (cur == null) {
                cs.ledger.put(g.item, cap / 2);
                continue;
            }
            if (cur >= cap) continue;
            double rem = cs.prodRemainder.getOrDefault(g.item, 0.0) + itemsPerDay / ECONOMY_TICKS_PER_DAY;
            int whole = (int) rem;
            if (whole > 0) cs.ledger.put(g.item, Math.min(cap, cur + whole));
            cs.prodRemainder.put(g.item, rem - whole);
        }
    }

    private static void consume(CityState cs) {
        double foodNeed = cs.believedPop * EAT_VALUE_PER_POP_DAY / ECONOMY_TICKS_PER_DAY;
        cs.neededValueToday += foodNeed;
        cs.foodDebt += foodNeed;
        while (cs.foodDebt > 0) {
            String cheapest = null;
            int cheapestValue = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : cs.ledger.entrySet()) {
                if (e.getValue() <= 0 || !isFood(e.getKey())) continue;
                int v = unitValue(e.getKey());
                if (v < cheapestValue) {
                    cheapestValue = v;
                    cheapest = e.getKey();
                }
            }
            if (cheapest == null || cs.foodDebt < cheapestValue) break;
            cs.ledger.put(cheapest, cs.ledger.get(cheapest) - 1);
            cs.foodDebt -= cheapestValue;
            cs.eatenValueToday += cheapestValue;
        }

        if (cs.imports.isEmpty()) return;
        cs.wantDebt += cs.believedPop * WANT_DRAIN_PER_POP_DAY / ECONOMY_TICKS_PER_DAY;
        Iterator<Map.Entry<String, Integer>> it = cs.imports.entrySet().iterator();
        while (it.hasNext() && cs.wantDebt > 0) {
            Map.Entry<String, Integer> e = it.next();
            int held = Math.min(e.getValue(), cs.ledger.getOrDefault(e.getKey(), 0));
            int v = unitValue(e.getKey());
            if (held <= 0 || v <= 0) {
                it.remove();
                continue;
            }
            int used = (int) Math.min(held, cs.wantDebt / v);
            if (used <= 0) break;
            cs.wantDebt -= (double) used * v;
            cs.ledger.put(e.getKey(), cs.ledger.getOrDefault(e.getKey(), 0) - used);
            if (e.getValue() - used <= 0) it.remove();
            else e.setValue(e.getValue() - used);
        }
    }

    private static void evolve(CityState cs, Set<String> techCap) {
        double p = cs.prosperity;
        cs.techProgress += TECH_RATE * (p * p / 1.5)
            * (0.75 + 0.25 * cs.believedPop / 24.0) * cs.difficulty.factor();
        if (cs.techProgress < 1.0) return;
        for (String id : techCap) {
            if (cs.knownTech.add(id)) {
                cs.techProgress = 0.0;
                cs.activeGoodsCache = null;
                return;
            }
        }
        cs.techProgress = 1.0;
    }

    private static void dailyPass(CityState cs, long today) {
        long elapsed = Math.max(1, today - cs.dayIndex);

        double dayFed = cs.neededValueToday <= 0 ? 1.0
            : Math.min(1.0, cs.eatenValueToday / cs.neededValueToday);
        cs.fedRatio = 0.7 * cs.fedRatio + 0.3 * dayFed;
        cs.eatenValueToday = 0;
        cs.neededValueToday = 0;

        cs.tradeVolume *= Math.pow(0.95, elapsed);

        cs.demands.removeIf(d -> d.qtyRemaining <= 0 || today - d.createdDay >= DEMAND_EXPIRE_DAYS);
        cs.demandCooldowns.values().removeIf(until -> until <= today);
        refillDemands(cs, today);

        double demandScore = Math.min(1.0, cs.demandCooldowns.size() / (double) DEMAND_SLOTS);
        double tradeScore = Math.min(1.0, cs.tradeVolume / (cs.believedPop * 10.0));
        double target = 0.8 * cs.fedRatio + 0.8 * tradeScore + 0.4 * demandScore;
        target = Math.max(0.0, Math.min(2.0, target));
        double blend = Math.min(0.65, 0.10 * elapsed);
        cs.prosperity += (target - cs.prosperity) * blend;
        cs.prosperity = Math.max(0.0, Math.min(2.0, cs.prosperity));

        if (cs.prosperity >= 1.0) cs.popDrift++;
        else if (cs.prosperity < 0.4) cs.popDrift--;
        int maxDrift = Math.max(1, cs.countedHomes / 2);
        cs.popDrift = Math.max(-maxDrift, Math.min(maxDrift, cs.popDrift));
        cs.believedPop = Math.max(CityState.BASE_POP,
            Math.min(MAX_POP, cs.countedHomes + cs.popDrift));

        cs.dayIndex = today;
    }

    private static void refillDemands(CityState cs, long today) {
        if (cs.demands.size() >= DEMAND_SLOTS) return;
        ActiveGoods ag = activeGoods(cs);
        Set<String> excluded = new HashSet<>(ag.producedItems);
        for (CityState.Demand d : cs.demands) excluded.add(d.item);
        excluded.addAll(cs.demandCooldowns.keySet());

        record Candidate(String item, int rank, long order) {}
        List<Candidate> pool = new ArrayList<>();
        String biomePath = cs.biome == null ? "" : cs.biome.getPath();
        for (GoodDef def : CityStateGoodsLoader.goods()) {
            if (excluded.contains(def.item()) || !knowsItem(cs, def.item())) continue;
            boolean techOk = def.requiresTech() == null || cs.knownTech.contains(def.requiresTech());
            if (!techOk) continue;
            if (def.requiresTech() != null && !chunksSatisfied(cs, def)) {
                pool.add(new Candidate(def.item(), 3, order(cs, today, def.item())));
            } else if (!def.biomes().isEmpty() && !biomeMatches(biomePath, def.biomes())) {
                pool.add(new Candidate(def.item(), 2, order(cs, today, def.item())));
            }
        }
        for (CityStateWantsLoader.WantDef w : CityStateWantsLoader.wants()) {
            if (excluded.contains(w.item()) || !knowsItem(cs, w.item())) continue;
            if (w.requiresTech() != null && !cs.knownTech.contains(w.requiresTech())) continue;
            pool.add(new Candidate(w.item(), 1, order(cs, today, w.item())));
        }
        pool.sort((a, b) -> a.rank != b.rank ? Integer.compare(b.rank, a.rank)
            : Long.compare(a.order, b.order));
        Set<String> taken = new HashSet<>();
        for (Candidate c : pool) {
            if (cs.demands.size() >= DEMAND_SLOTS) break;
            if (!taken.add(c.item)) continue;
            int qty = Math.max(1, (int) Math.ceil(cs.believedPop * DEMAND_VALUE_PER_POP
                / Math.max(1, unitValue(c.item))));
            cs.demands.add(new CityState.Demand(c.item, qty, today));
        }
    }

    private static long order(CityState cs, long today, String item) {
        long h = cs.id.getLeastSignificantBits() ^ (today * 0x9E3779B97F4A7C15L) ^ item.hashCode();
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        return h ^ (h >>> 33);
    }

    public static void onGoodsDelivered(CityState cs, UUID fromSettlement, String itemId, int count) {
        if (count <= 0) return;
        cs.ledger.merge(itemId, count, Integer::sum);
        if (!isFood(itemId)) cs.imports.merge(itemId, count, Integer::sum);
        cs.tradeVolume += ItemValue.value(itemId, count);
        for (CityState.Demand d : cs.demands) {
            if (!d.item.equals(itemId) || d.qtyRemaining <= 0) continue;
            d.qtyRemaining = Math.max(0, d.qtyRemaining - count);
            if (d.qtyRemaining == 0) {
                cs.demandCooldowns.put(itemId, cs.dayIndex + DEMAND_COOLDOWN_DAYS);
                if (fromSettlement != null) cs.relScore.merge(fromSettlement, 5, Integer::sum);
            }
            break;
        }
    }

    public record ActiveGood(String item, double effectiveWeight, int unitValue) {}

    public static final class ActiveGoods {
        final List<ActiveGood> food = new ArrayList<>();
        final List<ActiveGood> nonFood = new ArrayList<>();
        final Set<String> producedItems = new HashSet<>();
        int totalJobPois;
        private int generation;
        private int techCount;
        private int scanStamp;
    }

    public static ActiveGoods activeGoods(CityState cs) {
        ActiveGoods cached = cs.activeGoodsCache;
        int gen = CityStateGoodsLoader.generation();
        int scanStamp = cs.resourceChunks.size() * 31 + cs.jobPois.size();
        if (cached != null && cached.generation == gen && cached.techCount == cs.knownTech.size()
                && cached.scanStamp == scanStamp) {
            return cached;
        }
        ActiveGoods out = new ActiveGoods();
        out.generation = gen;
        out.techCount = cs.knownTech.size();
        out.scanStamp = scanStamp;
        for (int n : cs.jobPois.values()) out.totalJobPois += n;
        String biomePath = cs.biome == null ? "" : cs.biome.getPath();
        for (GoodDef def : CityStateGoodsLoader.goods()) {
            if (!def.biomes().isEmpty() && !biomeMatches(biomePath, def.biomes())) continue;
            if (def.requiresTech() != null && !cs.knownTech.contains(def.requiresTech())) continue;
            boolean chunkMatched = chunksSatisfied(cs, def);
            if (!def.requiresChunks().isEmpty() && !chunkMatched) continue;
            int unit = unitValue(def.item());
            if (unit <= 0) continue;
            double poiMult = def.poi() == null ? 1.0
                : Math.min(3.0, Math.pow(1.5, cs.jobPois.getOrDefault(def.poi(), 0)));
            double w = def.weight() * poiMult
                * (!def.requiresChunks().isEmpty() && chunkMatched ? 2.0 : 1.0);
            ActiveGood good = new ActiveGood(def.item(), w, unit);
            (isFood(def.item()) ? out.food : out.nonFood).add(good);
            out.producedItems.add(def.item());
        }
        cs.activeGoodsCache = out;
        return out;
    }

    private static boolean biomeMatches(String biomePath, List<String> patterns) {
        for (String p : patterns) {
            if (biomePath.contains(p)) return true;
        }
        return false;
    }

    private static boolean chunksSatisfied(CityState cs, GoodDef def) {
        for (ChunkResource r : def.requiresChunks()) {
            if (cs.resourceChunks.getOrDefault(r.name(), 0) <= 0) return false;
        }
        return true;
    }

    private static final Map<String, Integer> VALUE_CACHE = new HashMap<>();
    private static int valueCacheGeneration = -1;

    private static int unitValue(String itemId) {
        int gen = CityStateGoodsLoader.generation();
        if (gen != valueCacheGeneration) {
            VALUE_CACHE.clear();
            valueCacheGeneration = gen;
        }
        return VALUE_CACHE.computeIfAbsent(itemId, id -> ItemValue.value(id, 1));
    }

    private static boolean isFood(String itemId) {
        Item item = resolve(itemId);
        return item != null && FoodValueLoader.base(item) > 0;
    }

    private static boolean knowsItem(CityState cs, String itemId) {
        Item item = resolve(itemId);
        if (item == null) return false;
        if (com.bannerbound.core.api.research.data.StartingItemsLoader.contains(item)) return true;
        for (String id : cs.knownTech) {
            com.bannerbound.core.api.research.ResearchDefinition def =
                com.bannerbound.core.api.research.data.ResearchTreeLoader.get(id);
            if (def != null && def.unlocksItems().contains(itemId)) return true;
        }
        return false;
    }

    private static Item resolve(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? null : item;
    }

    public static List<String> topGoods(CityState cs, int limit) {
        List<Map.Entry<String, Integer>> held = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cs.ledger.entrySet()) {
            if (e.getValue() > 0) held.add(e);
        }
        held.sort((a, b) -> Integer.compare(
            unitValue(b.getKey()) * b.getValue(), unitValue(a.getKey()) * a.getValue()));
        List<String> out = new ArrayList<>(Math.min(limit, held.size()));
        for (int i = 0; i < held.size() && i < limit; i++) out.add(held.get(i).getKey());
        return out;
    }

    public static int totalStockValue(CityState cs) {
        int total = 0;
        for (Map.Entry<String, Integer> e : cs.ledger.entrySet()) {
            if (e.getValue() > 0) total += unitValue(e.getKey()) * e.getValue();
        }
        return total;
    }

    public static String stockSummary(CityState cs, int limit) {
        List<String> top = topGoods(cs, limit);
        StringBuilder sb = new StringBuilder();
        for (String id : top) {
            if (sb.length() > 0) sb.append(", ");
            int slash = id.indexOf(':');
            sb.append(slash >= 0 ? id.substring(slash + 1) : id)
                .append("×").append(cs.ledger.getOrDefault(id, 0));
        }
        return sb.toString();
    }

    public static List<String> demandItems(CityState cs) {
        List<String> out = new ArrayList<>(cs.demands.size());
        for (CityState.Demand d : cs.demands) {
            if (d.qtyRemaining > 0) out.add(d.item);
        }
        return out;
    }
}
