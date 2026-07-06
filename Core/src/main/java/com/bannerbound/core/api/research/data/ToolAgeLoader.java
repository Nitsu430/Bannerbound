package com.bannerbound.core.api.research.data;

import com.bannerbound.core.api.research.ToolAge;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Loads {@link ToolAge} definitions from {@code data/<namespace>/tool_ages/<id>.json}, one file
 * per age (fields: name, order, optional chop_ticks/mine_speed/harvest_speed/weapon_damage/
 * weapon_attack_speed, and a role->item {@code tools} map). Modded ages drop in their own files -
 * no merge logic required. Map is keyed by the file id ({@link ResourceLocation#getPath()}, i.e.
 * the path minus namespace and {@code .json}), so displayName resolves the lang key
 * bannerbound.tool_age.&lt;id&gt; and falls back to the raw "name". Tool entries pointing at unknown
 * items are dropped with a warning. {@code getByTool} reverse-looks-up the age that defines a
 * given role's item so a worker's cadence reflects the tool actually in hand (a bone axe chops at
 * bone speed even after the settlement unlocks a better age), not the settlement's best age.
 */
public class ToolAgeLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "tool_ages";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, ToolAge> AGES = Collections.emptyMap();

    public ToolAgeLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, ToolAge> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            String id = key.getPath();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String rawName = GsonHelper.getAsString(obj, "name", id);
                int order = GsonHelper.getAsInt(obj, "order", 0);
                OptionalInt chopTicks = obj.has("chop_ticks")
                    ? OptionalInt.of(GsonHelper.getAsInt(obj, "chop_ticks"))
                    : OptionalInt.empty();
                OptionalInt mineTicks = obj.has("mine_speed")
                    ? OptionalInt.of(GsonHelper.getAsInt(obj, "mine_speed"))
                    : OptionalInt.empty();
                OptionalInt harvestTicks = obj.has("harvest_speed")
                    ? OptionalInt.of(GsonHelper.getAsInt(obj, "harvest_speed"))
                    : OptionalInt.empty();
                double weaponDamage = obj.has("weapon_damage")
                    ? GsonHelper.getAsDouble(obj, "weapon_damage") : 4.0;
                double weaponAttackSpeed = obj.has("weapon_attack_speed")
                    ? GsonHelper.getAsDouble(obj, "weapon_attack_speed") : 1.6;

                Map<String, Item> tools = new HashMap<>();
                if (obj.has("tools")) {
                    JsonObject toolsObj = GsonHelper.getAsJsonObject(obj, "tools");
                    for (Map.Entry<String, JsonElement> t : toolsObj.entrySet()) {
                        String itemId = t.getValue().getAsString();
                        ResourceLocation itemRl = ResourceLocation.tryParse(itemId);
                        Item item = itemRl == null ? Items.AIR : BuiltInRegistries.ITEM.get(itemRl);
                        if (item != Items.AIR) {
                            tools.put(t.getKey(), item);
                        } else {
                            BannerboundCore.LOGGER.warn("Tool age {} references unknown item '{}'", id, itemId);
                        }
                    }
                }

                Component displayName = Component.translatableWithFallback(
                    "bannerbound.tool_age." + id, rawName);

                map.put(id, new ToolAge(id, displayName, order, chopTicks, mineTicks, harvestTicks,
                    weaponDamage, weaponAttackSpeed, tools));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse tool_age {}", key, ex);
            }
        }
        AGES = map;
        BannerboundCore.LOGGER.info("Loaded {} tool ages", map.size());
    }

    public static Map<String, ToolAge> getAll() {
        return AGES;
    }

    public static ToolAge get(String id) {
        return AGES.get(id);
    }

    public static ToolAge getByTool(String role, Item item) {
        if (item == null) return null;
        for (ToolAge age : AGES.values()) {
            if (age.tools().get(role) == item) return age;
        }
        return null;
    }
}
