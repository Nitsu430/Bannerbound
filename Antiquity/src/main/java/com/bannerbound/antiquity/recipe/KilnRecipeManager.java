package com.bannerbound.antiquity.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import net.minecraft.world.item.ItemStack;
import com.bannerbound.antiquity.event.AntiquityEvents;

/**
 * Datapack loader for Kiln firing recipes - reads every JSON under
 * {@code data/<namespace>/kiln_recipes/}. Registered as a server-data reload listener (see
 * {@code AntiquityEvents}). {@code applyEntries} is public so the client jar loader
 * ({@code ClientDatapackRecipes}) can re-read the same JSONs on remote clients, where server
 * datapacks don't reach. {@code all()} feeds JEI and tutorial displays.
 */
@ApiStatus.Internal
public class KilnRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<KilnRecipe> recipes = List.of();

    public KilnRecipeManager() {
        super(GSON, "kiln_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<KilnRecipe> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            KilnRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid kiln recipe {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        recipes = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} kiln recipe(s).", recipes.size());
    }

    public static List<KilnRecipe> all() {
        return recipes;
    }

    @Nullable
    public static KilnRecipe find(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (KilnRecipe recipe : recipes) {
            if (recipe.matches(stack)) {
                return recipe;
            }
        }
        return null;
    }
}
