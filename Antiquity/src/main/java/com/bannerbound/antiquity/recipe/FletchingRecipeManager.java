package com.bannerbound.antiquity.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.bannerbound.antiquity.event.AntiquityEvents;
import com.bannerbound.antiquity.craft.Fletching;

/**
 * Datapack loader for Fletching Station recipes - reads every JSON under
 * {@code data/<namespace>/fletching_recipes/}. Registered as a server reload listener in
 * {@code AntiquityEvents}; the block entity syncs its matched result to clients itself, and
 * {@code applyEntries} is public so {@code ClientDatapackRecipes} can re-read the same JSONs on
 * remote clients, where server datapacks don't reach. Recipes are also keyed by their file id
 * ({@code idOf}/{@code byId}) so the stretch minigame can send the matched recipe across the
 * client round trip and the server can resolve the result at completion. {@code find} returns the
 * recipe whose counts EXACTLY equal the pile; {@code candidates} mirrors the crafting stone's:
 * recipes the pile could still BECOME (placed items within required counts, at least one
 * ingredient missing - an exact match is the craftable result, not a candidate), empty for an
 * empty pile, sorted by result id so the renderer's ghost pick is stable across recomputes.
 */
@ApiStatus.Internal
public class FletchingRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<FletchingRecipe> recipes = List.of();
    private static volatile Map<ResourceLocation, FletchingRecipe> byId = Map.of();

    public FletchingRecipeManager() {
        super(GSON, "fletching_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<FletchingRecipe> loaded = new ArrayList<>();
        Map<ResourceLocation, FletchingRecipe> ids = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            FletchingRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid fletching recipe {}: {}", entry.getKey(), error))
                .ifPresent(recipe -> {
                    loaded.add(recipe);
                    ids.put(entry.getKey(), recipe);
                });
        }
        recipes = List.copyOf(loaded);
        byId = Map.copyOf(ids);
        BannerboundAntiquity.LOGGER.info("Loaded {} fletching recipe(s).", recipes.size());
    }

    public static List<FletchingRecipe> all() {
        return recipes;
    }

    @Nullable
    public static FletchingRecipe find(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return null;
        for (FletchingRecipe recipe : recipes) {
            if (recipe.matches(placed)) return recipe;
        }
        return null;
    }

    public static List<FletchingRecipe> candidates(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return List.of();
        List<FletchingRecipe> out = new ArrayList<>();
        for (FletchingRecipe recipe : recipes) {
            Map<Item, Integer> required = recipe.requiredCounts();
            if (required.equals(placed)) continue;
            boolean covers = true;
            for (Map.Entry<Item, Integer> e : placed.entrySet()) {
                if (required.getOrDefault(e.getKey(), 0) < e.getValue()) {
                    covers = false;
                    break;
                }
            }
            if (covers) out.add(recipe);
        }
        out.sort(Comparator.comparing(r -> BuiltInRegistries.ITEM.getKey(r.result().getItem())));
        return out;
    }

    @Nullable
    public static ResourceLocation idOf(FletchingRecipe recipe) {
        for (Map.Entry<ResourceLocation, FletchingRecipe> e : byId.entrySet()) {
            if (e.getValue() == recipe) return e.getKey();
        }
        return null;
    }

    @Nullable
    public static FletchingRecipe byId(ResourceLocation id) {
        return byId.get(id);
    }
}
