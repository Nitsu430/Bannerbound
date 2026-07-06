package com.bannerbound.core.api.settlement.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads citizen first-name pools from data/<namespace>/citizen_names/<gender>_<era>.json. The
 * filename encodes the pool (male_ancient.json, female_medieval.json, unisex_future.json, etc.) and
 * each file is a flat { "names": [ ... ] } list. A citizen of gender G immigrating in era E draws
 * from the union of the G_E pool and the unisex_E pool, so genuinely ambiguous names can be shared
 * across genders without duplicating them into both files; datapacks can drop in additional files
 * and pools for the same <gender>_<era> key are unioned across namespaces. randomName falls back to
 * "Citizen" when no pool for the era is loaded at all (datapack misconfiguration). Replaces the old
 * hard-coded CitizenNames table -- name content is now fully data-driven.
 */
public class CitizenNameLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "citizen_names";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, List<String>> POOLS = Map.of();

    public CitizenNameLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, List<String>> pools = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            // Key is the resource path minus folder + .json, i.e. exactly "<gender>_<era>".
            String key = entry.getKey().getPath();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray arr = obj.getAsJsonArray("names");
                List<String> names = pools.computeIfAbsent(key, k -> new ArrayList<>());
                for (JsonElement el : arr) {
                    names.add(el.getAsString());
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse citizen_names {}", entry.getKey(), ex);
            }
        }
        POOLS = pools;
        BannerboundCore.LOGGER.info("Loaded {} citizen name pools", pools.size());
    }

    public static String randomName(RandomSource rng, Era era, CitizenGender gender) {
        String eraKey = era.key();
        List<String> combined = new ArrayList<>();
        combined.addAll(POOLS.getOrDefault(gender.key() + "_" + eraKey, List.of()));
        combined.addAll(POOLS.getOrDefault("unisex_" + eraKey, List.of()));
        if (combined.isEmpty()) {
            return "Citizen";
        }
        return combined.get(rng.nextInt(combined.size()));
    }
}
