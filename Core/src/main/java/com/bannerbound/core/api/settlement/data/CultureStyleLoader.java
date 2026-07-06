package com.bannerbound.core.api.settlement.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CultureStyle;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Loads culture styles from data/<ns>/culture_styles/*.json, keyed by file stem, and serves them
 * via get/all/ids. Each file carries: "id" (defaults to the file stem), "name" (a LITERAL display
 * string rendered with Component.literal on the settle screen, NOT a lang key; defaults to the id
 * so a missing name stays readable rather than a dead translation key), "image" (ResourceLocation
 * of the preview thumbnail shown in the founding picker; defaults to
 * bannerbound:textures/gui/culture/<id>.png so a style following the naming convention needs no
 * explicit entry), "overrides" (per-block appeal values that replace the base AppealResolver
 * table), and "food_overrides" (per-item food values that replace the base FoodValueLoader
 * table -> effective). Both maps are optional; omit either to leave the base table untouched.
 * Unknown block/item ids are warned and skipped, and food override values are clamped at 0 (no
 * negative food, matching FoodValueLoader). ids() is sorted to feed the founding picker and the
 * default fallback.
 */
public class CultureStyleLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "culture_styles";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, CultureStyle> STYLES = Collections.emptyMap();

    public CultureStyleLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<String, CultureStyle> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String id = GsonHelper.getAsString(obj, "id", key.getPath());
                String nameKey = GsonHelper.getAsString(obj, "name", id);
                String image = GsonHelper.getAsString(obj, "image",
                    "bannerbound:textures/gui/culture/" + id + ".png");

                Map<Block, Float> overrides = new HashMap<>();
                if (obj.has("overrides")) {
                    JsonObject ov = obj.getAsJsonObject("overrides");
                    for (Map.Entry<String, JsonElement> o : ov.entrySet()) {
                        ResourceLocation rl = ResourceLocation.tryParse(o.getKey());
                        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                            BannerboundCore.LOGGER.warn("Unknown block '{}' in culture style {}",
                                o.getKey(), key);
                            continue;
                        }
                        overrides.put(BuiltInRegistries.BLOCK.get(rl), o.getValue().getAsFloat());
                    }
                }

                Map<Item, Float> foodOverrides = new HashMap<>();
                if (obj.has("food_overrides")) {
                    JsonObject fo = obj.getAsJsonObject("food_overrides");
                    for (Map.Entry<String, JsonElement> o : fo.entrySet()) {
                        ResourceLocation rl = ResourceLocation.tryParse(o.getKey());
                        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                            BannerboundCore.LOGGER.warn("Unknown item '{}' in culture style {} food_overrides",
                                o.getKey(), key);
                            continue;
                        }
                        float v = Math.max(0f, o.getValue().getAsFloat());
                        foodOverrides.put(BuiltInRegistries.ITEM.get(rl), v);
                    }
                }

                map.put(id, new CultureStyle(id, nameKey, image, overrides, foodOverrides));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse culture style {}", key, ex);
            }
        }
        STYLES = map;
        BannerboundCore.LOGGER.info("Loaded {} culture styles", map.size());
    }

    public static CultureStyle get(String id) {
        return STYLES.get(id);
    }

    public static Map<String, CultureStyle> all() {
        return STYLES;
    }

    public static List<String> ids() {
        List<String> ids = new ArrayList<>(STYLES.keySet());
        Collections.sort(ids);
        return ids;
    }
}
