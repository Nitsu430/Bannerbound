package com.bannerbound.core.barbarian;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

/**
 * The goods a camp can put on the table - generated procedurally from its biome, type and (via the
 * relationship) how far your dealings with it have come, NOT a persisted inventory. Camps still don't
 * run a worker economy; this is a derived "what they'd plausibly have to trade" pool whose VARIETY and
 * QUANTITY widen as your standing improves ("they get more things as they progress with you").
 *
 * <p>Deterministic per camp (seeded by {@code languageSeed}) so the stock reads as stable, only
 * expanding when relations warm. Item ids are registry strings (resolved late, like {@link ParleyLoader})
 * so Core needn't depend on Antiquity's item classes.
 */
@ApiStatus.Internal
public final class CampGoods {
    public record Stock(String itemId, int count) {}

    private CampGoods() {}

    public static int tierOf(int relScore) {
        if (relScore >= 40) return 3;
        if (relScore >= 10) return 2;
        if (relScore >= -15) return 1;
        return 0;
    }

    public static List<Stock> available(BarbarianCamp camp, UUID settlementId) {
        int rel = camp.relScore.getOrDefault(settlementId, 0);
        int tier = tierOf(rel);
        String biome = camp.biome == null ? "" : camp.biome.getPath();

        List<Cand> cands = new ArrayList<>();

        boolean desert = biome.contains("desert") || biome.contains("badlands");
        if (desert) {
            cand(cands, "minecraft:cactus", 0);
            cand(cands, "minecraft:sand", 0);
        } else {
            cand(cands, "minecraft:wheat", 0);
            cand(cands, "minecraft:oak_log", 0);
        }
        if (biome.contains("forest") || biome.contains("jungle") || biome.contains("taiga")) {
            cand(cands, "minecraft:stick", 0);
            cand(cands, "minecraft:sweet_berries", 1);
        }
        if (biome.contains("mountain") || biome.contains("hills") || biome.contains("peaks")
                || biome.contains("windswept") || biome.contains("stony")) {
            cand(cands, "minecraft:cobblestone", 0);
            cand(cands, "minecraft:coal", 1);
            cand(cands, "minecraft:iron_ingot", 3);
        }

        switch (camp.type) {
            case NOMAD -> {
                cand(cands, "minecraft:leather", 1);
                cand(cands, "minecraft:string", 0);
                cand(cands, "minecraft:white_wool", 1);
                cand(cands, "minecraft:flint", 0);
            }
            case TRIBE -> {
                cand(cands, "minecraft:leather", 1);
                cand(cands, "minecraft:bone", 0);
                cand(cands, "minecraft:feather", 1);
            }
            case RAIDER -> {
                cand(cands, "minecraft:cooked_beef", 1);
                cand(cands, "minecraft:leather", 1);
                cand(cands, "minecraft:flint", 0);
            }
            case MARAUDER -> {
                cand(cands, "minecraft:bone", 1);
                cand(cands, "minecraft:flint", 2);
            }
        }

        List<Stock> out = new ArrayList<>();
        for (Cand c : cands) {
            if (c.minTier > tier) continue;
            if (ItemValue.value(c.itemId, 1) <= 0) continue;
            int base = 2 + tier;
            int jitter = (int) Math.floorMod((camp.languageSeed ^ c.itemId.hashCode()), 3);
            out.add(new Stock(c.itemId, Math.max(1, base + jitter)));
        }
        return out;
    }

    private static void cand(List<Cand> list, String itemId, int minTier) {
        for (Cand c : list) {
            if (c.itemId.equals(itemId)) { c.minTier = Math.min(c.minTier, minTier); return; }
        }
        list.add(new Cand(itemId, minTier));
    }

    private static final class Cand {
        final String itemId;
        int minTier;
        Cand(String itemId, int minTier) { this.itemId = itemId; this.minTier = minTier; }
    }
}
