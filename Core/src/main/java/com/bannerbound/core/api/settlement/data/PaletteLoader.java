package com.bannerbound.core.api.settlement.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Palette;
import com.bannerbound.core.api.settlement.Settlement;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;

/**
 * Loads culture palettes from data/<ns>/palettes/*.json and doubles as the palette registry
 * (get/all/availableFor). Each file is one palette: "id" (defaults to the file stem), "name"
 * (defaults to the id), and "blocks" (block id -> bonus). Block ids resolve to Block instances at
 * load time (unknown ids warned and skipped), bonuses are clamped to [-1, 1], and the blocks map
 * is a LinkedHashMap preserving authoring order so the UI icon row stays stable. Mirror of
 * BlockAppealLoader / CultureStyleLoader. A palette is available to a settlement when a completed
 * science/culture research lists its unlock flag UNLOCK_FLAG_PREFIX + id ("unlock.palette.<id>",
 * set via a node's "unlocks": {"palette": ["id"]}); availableFor walks BY_ID in order and returns
 * the unlocked ids for the UI "Available" list.
 */
public class PaletteLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "palettes";
    public static final String UNLOCK_FLAG_PREFIX = "unlock.palette.";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, Palette> BY_ID = Collections.emptyMap();

    public PaletteLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<String, Palette> map = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String stem = key.getPath();
                int slash = stem.lastIndexOf('/');
                if (slash >= 0) stem = stem.substring(slash + 1);
                String id = GsonHelper.getAsString(obj, "id", stem);
                String name = GsonHelper.getAsString(obj, "name", id);

                Map<Block, Float> bonuses = new LinkedHashMap<>();
                JsonObject blocks = GsonHelper.getAsJsonObject(obj, "blocks");
                for (Map.Entry<String, JsonElement> b : blocks.entrySet()) {
                    ResourceLocation blockRl = ResourceLocation.tryParse(b.getKey());
                    if (blockRl == null || !BuiltInRegistries.BLOCK.containsKey(blockRl)) {
                        BannerboundCore.LOGGER.warn("Unknown block '{}' in palette {}", b.getKey(), key);
                        continue;
                    }
                    float bonus = Math.max(-1f, Math.min(1f, b.getValue().getAsFloat()));
                    bonuses.put(BuiltInRegistries.BLOCK.get(blockRl), bonus);
                }
                map.put(id, new Palette(id, name, bonuses));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse palette {}", key, ex);
            }
        }
        BY_ID = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded {} palette definitions", map.size());
    }

    public static Palette get(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static Map<String, Palette> all() {
        return BY_ID;
    }

    public static List<String> availableFor(Settlement settlement) {
        List<String> out = new ArrayList<>();
        if (settlement == null) return out;
        for (String id : BY_ID.keySet()) {
            if (ResearchManager.hasFlagEitherTree(settlement, UNLOCK_FLAG_PREFIX + id)) {
                out.add(id);
            }
        }
        return out;
    }
}
