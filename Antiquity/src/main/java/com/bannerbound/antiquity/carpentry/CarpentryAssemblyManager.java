package com.bannerbound.antiquity.carpentry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;

/**
 * Datapack loader for carpentry "assembly" recipes (fixed-result, multi-ingredient: wooden tools
 * etc.) that reads every JSON under {@code data/<namespace>/carpentry_assembly/}. Registered as a
 * server reload listener in AntiquityEvents; the resolved, affordable offers are synced to clients on
 * the block entity itself, but applyEntries() is public so the client-side jar loader
 * (ClientDatapackRecipes) can reuse it on remote clients, where server datapacks don't reach.
 * Recipes are sorted by name so the picker's browse order is stable. isIngredient() answers whether a
 * stack is depositable budget material by testing it against every loaded recipe's ingredients, so
 * adding a recipe that uses a new item automatically makes that item valid. Mirrors
 * {@link CarpentryOutputManager}.
 */
@ApiStatus.Internal
public class CarpentryAssemblyManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<CarpentryAssembly> recipes = List.of();

    public CarpentryAssemblyManager() {
        super(GSON, "carpentry_assembly");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<CarpentryAssembly> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            CarpentryAssembly.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid carpentry assembly {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        loaded.sort(Comparator.comparing(CarpentryAssembly::name));
        recipes = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} carpentry assembly recipe(s).", recipes.size());
    }

    public static List<CarpentryAssembly> all() {
        return recipes;
    }

    public static boolean isIngredient(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (CarpentryAssembly recipe : recipes) {
            for (CarpentryAssembly.Ingredient in : recipe.ingredients()) {
                if (in.toCost().matches(stack)) return true;
            }
        }
        return false;
    }
}
