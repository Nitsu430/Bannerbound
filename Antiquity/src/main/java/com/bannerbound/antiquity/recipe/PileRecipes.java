package com.bannerbound.antiquity.recipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.Item;

/**
 * Shared pile-matching for the count-based "place a pile, match a recipe" workstations - the crafting
 * stone (knapping), fletching station, and pottery slab. Each recipe keeps its own ingredient record
 * (and its own JSON codec); they only need to expose item + count via {@link Counted} to reuse the
 * one multiset-matching implementation instead of carrying three identical copies. matches() is an
 * EXACT multiset equality check: the placed pile must contain the required items and nothing else.
 */
public final class PileRecipes {
    private PileRecipes() {
    }

    public interface Counted {
        Item item();

        int count();
    }

    public static Map<Item, Integer> requiredCounts(List<? extends Counted> ingredients) {
        Map<Item, Integer> m = new HashMap<>();
        for (Counted ing : ingredients) {
            m.merge(ing.item(), ing.count(), Integer::sum);
        }
        return m;
    }

    public static boolean matches(List<? extends Counted> ingredients, Map<Item, Integer> placed) {
        return requiredCounts(ingredients).equals(placed);
    }
}
