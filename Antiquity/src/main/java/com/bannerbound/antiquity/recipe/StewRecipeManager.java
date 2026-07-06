package com.bannerbound.antiquity.recipe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import com.bannerbound.antiquity.event.AntiquityEvents;

/**
 * Datapack loader for named stew recipes - every JSON under {@code data/<namespace>/stew_recipes/}.
 * Server-side reload listener (registered in {@code AntiquityEvents}); re-read jar-side on remote
 * clients by {@code ClientDatapackRecipes} so a pot's stew tint/identity resolves there too. Named
 * recipes are overrides - any unmatched food mix still cooks into a generic stew in the pot.
 * findMatch picks the MOST SPECIFIC recipe covering the placed ingredient-type set (counts never
 * change a stew's identity, only its food value): fewest tag-based ingredients wins, ties broken
 * by more ingredients - so a beetroot-only pot is "Beetroot Stew" while carrot + beetroot falls
 * back to the tag-based "Vegetable Stew". isStewIngredient lets the pot accept items with no
 * standalone food value (e.g. mushrooms); without it those recipes would be unreachable because
 * the inputs could never be added. all() is what the Cook NPC scans to pick what to brew next.
 */
@ApiStatus.Internal
public class StewRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile Map<ResourceLocation, StewRecipe> recipes = Map.of();

    public StewRecipeManager() {
        super(GSON, "stew_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        Map<ResourceLocation, StewRecipe> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            StewRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid stew recipe {}: {}", entry.getKey(), error))
                .ifPresent(recipe -> loaded.put(entry.getKey(), recipe));
        }
        recipes = Map.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} stew recipe(s).", recipes.size());
    }

    public static java.util.Collection<StewRecipe> all() {
        return recipes.values();
    }

    @Nullable
    public static StewRecipe byId(String id) {
        if (id == null || id.isEmpty()) return null;
        return recipes.get(ResourceLocation.parse(id));
    }

    public static boolean isStewIngredient(Item item) {
        for (StewRecipe recipe : recipes.values()) {
            for (StewRecipe.Ing ing : recipe.ingredients()) {
                if (ing.matchesItem(item)) return true;
            }
        }
        return false;
    }

    @Nullable
    public static StewRecipe findMatch(Set<Item> placedTypes) {
        StewRecipe best = null;
        int bestTags = Integer.MAX_VALUE;
        int bestCount = -1;
        for (StewRecipe recipe : recipes.values()) {
            if (!recipe.matchesTypes(placedTypes)) continue;
            int tags = recipe.tagIngredientCount();
            int count = recipe.ingredients().size();
            if (tags < bestTags || (tags == bestTags && count > bestCount)) {
                best = recipe;
                bestTags = tags;
                bestCount = count;
            }
        }
        return best;
    }
}
