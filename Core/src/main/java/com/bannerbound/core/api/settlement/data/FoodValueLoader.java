package com.bannerbound.core.api.settlement.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CultureStyle;
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
import net.minecraft.world.item.Item;

/**
 * Loads the base food-value table from data/<ns>/food_values/*.json and serves it via base(),
 * effective(), and all(). Each file's "items" object (item id -> bare number) is merged into one
 * map, later files winning per id, so expansion datapacks can extend or override the vanilla
 * starter values. Values are clamped at 0 (no negative food); an item with no entry has value 0
 * and is silently ignored.
 *
 * <p>After loading files, the table is unioned with the MAX food_override across every culture
 * style, so the deposit pre-check (TownHallFoodDepositEvents) and tooltip sync
 * (FoodValueSyncPayload, base values only for now) can answer "is this item food in at least one
 * culture?". base() is the context-free value (client tooltip cache, datapack validation);
 * effective(item, settlement) is the server-authoritative value -- it starts from base() then
 * walks the settlement's culture styles in order, each style that lists the item OVERRIDING the
 * value outright (last style wins, mirroring AppealResolver.appealOf for blocks), clamped at 0; a
 * null settlement falls back to base(). Tooltip-side style awareness is a follow-up (would need
 * the style overrides synced to clients alongside ids/names).
 */
public class FoodValueLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "food_values";
    private static final Gson GSON = new Gson();
    private static volatile Map<Item, Float> BASE = Collections.emptyMap();

    public FoodValueLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<Item, Float> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonObject items = GsonHelper.getAsJsonObject(obj, "items");
                for (Map.Entry<String, JsonElement> i : items.entrySet()) {
                    ResourceLocation itemRl = ResourceLocation.tryParse(i.getKey());
                    if (itemRl == null || !BuiltInRegistries.ITEM.containsKey(itemRl)) {
                        BannerboundCore.LOGGER.warn("Unknown item '{}' in food_values {}",
                            i.getKey(), key);
                        continue;
                    }
                    float v = Math.max(0f, i.getValue().getAsFloat());
                    map.put(BuiltInRegistries.ITEM.get(itemRl), v);
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse food_values {}", key, ex);
            }
        }
        // Order-dependent: CultureStyleLoader registers before FoodValueLoader (ResearchEvents.onAddReloadListeners), so STYLES is populated here.
        int unionAdds = 0;
        for (CultureStyle style : CultureStyleLoader.all().values()) {
            for (Map.Entry<Item, Float> e : style.foodOverrides().entrySet()) {
                Float prev = map.get(e.getKey());
                if (prev == null || e.getValue() > prev) {
                    map.put(e.getKey(), e.getValue());
                    if (prev == null) unionAdds++;
                }
            }
        }
        BASE = map;
        BannerboundCore.LOGGER.info("Loaded {} food value definitions ({} unioned in from {} culture styles)",
            map.size(), unionAdds, CultureStyleLoader.all().size());
    }

    public static float base(Item item) {
        return BASE.getOrDefault(item, 0f);
    }

    public static float effective(Item item, Settlement settlement) {
        float v = BASE.getOrDefault(item, 0f);
        if (settlement != null) {
            for (String styleId : settlement.cultureStyles()) {
                CultureStyle style = CultureStyleLoader.get(styleId);
                if (style != null && style.hasFoodOverride(item)) {
                    v = style.foodOverride(item);
                }
            }
        }
        return Math.max(0f, v);
    }

    public static Map<Item, Float> all() {
        return BASE;
    }
}
