package com.bannerbound.core.territory;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

/**
 * The tunable distribution for specialized chunks, loaded from
 * data/bannerbound/chunk_resources/*.json (see ChunkResourceLoader). Holds, per biome category
 * (aquatic / mountainous / plains / forest / other): the chance a chunk of that category is special
 * and the weighted menu of resources it can carry. This is the single place to retune sparsity/mix
 * by hand -- edit the JSON and /reload; ChunkResources maps a biome to a category (by name
 * substring) and then asks this object.
 *
 * <p>A Category stores its resource picks alongside a cumulative-weight array (ascending, last
 * entry == 1.0), so pick() walks it to select a resource for a roll in [0,1). maxChance is the
 * largest category chance, a cheap reject: a chunk rolling above it can't be special. maxRelief is
 * the max ground-height spread a chunk may have before it's too steep/terraced to be special.
 * defaults() is a hard-coded fallback mirroring the shipped distribution.json; note that basic
 * livestock (cow/pig/sheep/chicken) are NOT chunk-typed -- they spawn naturally everywhere, so only
 * HORSES and FISH remain animal chunk types.
 */
@ApiStatus.Internal
public final class ChunkResourceDistribution {

    public static final class Category {
        private final double chance;
        private final ChunkResource[] picks;
        private final double[] cumulative;

        public Category(double chance, ChunkResource[] picks, double[] cumulative) {
            this.chance = chance;
            this.picks = picks;
            this.cumulative = cumulative;
        }

        public double chance() {
            return chance;
        }

        public ChunkResource pick(double rollPick) {
            for (int i = 0; i < cumulative.length; i++) {
                if (rollPick < cumulative[i]) return picks[i];
            }
            return picks.length == 0 ? ChunkResource.NONE : picks[picks.length - 1];
        }
    }

    private final int maxRelief;
    private final double maxChance;
    private final Map<String, Category> categories;

    public ChunkResourceDistribution(int maxRelief, Map<String, Category> categories) {
        this.maxRelief = maxRelief;
        this.categories = categories;
        double m = 0;
        for (Category c : categories.values()) m = Math.max(m, c.chance);
        this.maxChance = m;
    }

    public int maxRelief() {
        return maxRelief;
    }

    public double maxChance() {
        return maxChance;
    }

    public Category category(String key) {
        return categories.get(key);
    }

    public static Category category(double chance, LinkedHashMap<ChunkResource, Double> weights) {
        ChunkResource[] picks = new ChunkResource[weights.size()];
        double[] cumulative = new double[weights.size()];
        double sum = 0;
        for (double w : weights.values()) sum += Math.max(0, w);
        int i = 0;
        double run = 0;
        for (Map.Entry<ChunkResource, Double> e : weights.entrySet()) {
            picks[i] = e.getKey();
            run += Math.max(0, e.getValue());
            cumulative[i] = sum > 0 ? run / sum : 1.0;
            i++;
        }
        return new Category(chance, picks, cumulative);
    }

    public static ChunkResourceDistribution defaults() {
        Map<String, Category> cats = new LinkedHashMap<>();
        cats.put("aquatic", category(0.12, weights(ChunkResource.FISH, 100)));
        cats.put("mountainous", category(0.15, weights(
            ChunkResource.COPPER, 48, ChunkResource.IRON, 20, ChunkResource.COAL, 30, ChunkResource.MARBLE, 16,
            ChunkResource.TIN, 9, ChunkResource.STONE, 18, ChunkResource.LIMESTONE, 14,
            ChunkResource.ANDESITE, 16, ChunkResource.DIORITE, 14, ChunkResource.GRANITE, 14)));
        cats.put("plains", category(0.13, weights(
            ChunkResource.HORSES, 10.5,
            ChunkResource.WHEAT, 8, ChunkResource.CARROT, 5, ChunkResource.POTATO, 4, ChunkResource.BEETROOT, 3,
            ChunkResource.COPPER, 6, ChunkResource.IRON, 3.7, ChunkResource.COAL, 3, ChunkResource.MARBLE, 1.7,
            ChunkResource.TIN, 1.1, ChunkResource.CLAY, 4, ChunkResource.SAND, 2, ChunkResource.LIMESTONE, 3,
            ChunkResource.ANDESITE, 4, ChunkResource.DIORITE, 3, ChunkResource.GRANITE, 3)));
        cats.put("forest", category(0.09, weights(
            ChunkResource.WHEAT, 5, ChunkResource.POTATO, 4, ChunkResource.BEETROOT, 3,
            ChunkResource.COPPER, 10.7, ChunkResource.IRON, 8, ChunkResource.COAL, 6, ChunkResource.CLAY, 4,
            ChunkResource.STONE, 3, ChunkResource.ANDESITE, 4, ChunkResource.GRANITE, 4)));
        cats.put("other", category(0.04, weights(
            ChunkResource.COPPER, 55, ChunkResource.IRON, 25, ChunkResource.COAL, 30, ChunkResource.MARBLE, 12,
            ChunkResource.TIN, 8, ChunkResource.STONE, 18, ChunkResource.SAND, 14, ChunkResource.CLAY, 5,
            ChunkResource.LIMESTONE, 12, ChunkResource.ANDESITE, 18, ChunkResource.DIORITE, 16,
            ChunkResource.GRANITE, 16)));
        return new ChunkResourceDistribution(5, cats);
    }

    private static LinkedHashMap<ChunkResource, Double> weights(Object... pairs) {
        LinkedHashMap<ChunkResource, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((ChunkResource) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return m;
    }
}
