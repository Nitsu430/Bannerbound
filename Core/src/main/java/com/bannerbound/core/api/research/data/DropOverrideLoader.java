package com.bannerbound.core.api.research.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads the global drop-override list from {@code data/<namespace>/drop_overrides/*.json}: the
 * explicit exceptions to the automatic "don't drop unknown items" filter - the hybrid half of
 * the drop-gating system. Two modes per entry: {@code always_drop} forces the item to drop even
 * when the civ doesn't know it yet (bootstrap drops like bones, which must reach a fresh
 * settlement before any research exists); {@code never_drop} blocks the item even when known,
 * optionally scoped by a {@code sources} list of block/entity-type ids. An entry with no
 * {@code sources} blocks everywhere, recorded internally as the "*" sentinel so a later scoped
 * entry from another file cannot accidentally narrow it. All files in the folder are merged;
 * when the same item has conflicting entries, never_drop wins over always_drop (the explicit
 * block is the more restrictive intent). {@code decide()} takes the broken block's or killed
 * entity's id as sourceId, which may be null when the source is unknown (e.g. a felling-tree
 * drop); DEFAULT means "no override - fall back to the automatic known-set check".
 * Example entry: {@code {"item": "minecraft:wheat_seeds", "mode": "never_drop",
 * "sources": ["minecraft:short_grass"]}}.
 */
public class DropOverrideLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "drop_overrides";
    private static final Gson GSON = new Gson();

    public enum Decision {
        DEFAULT,
        ALWAYS_DROP,
        NEVER_DROP
    }

    private static volatile Set<String> ALWAYS_DROP = Set.of();
    private static volatile Map<String, Set<String>> NEVER_DROP = Map.of();

    public DropOverrideLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Set<String> always = new HashSet<>();
        Map<String, Set<String>> never = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray arr = GsonHelper.getAsJsonArray(obj, "overrides");
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    String item = GsonHelper.getAsString(o, "item");
                    String mode = GsonHelper.getAsString(o, "mode");
                    if ("always_drop".equals(mode)) {
                        always.add(item);
                    } else if ("never_drop".equals(mode)) {
                        Set<String> sources = never.computeIfAbsent(item, k -> new HashSet<>());
                        if (o.has("sources")) {
                            for (JsonElement s : GsonHelper.getAsJsonArray(o, "sources")) {
                                sources.add(s.getAsString());
                            }
                        }
                        else {
                            sources.add(BLOCK_EVERYWHERE);
                        }
                    } else {
                        BannerboundCore.LOGGER.warn("Unknown drop-override mode '{}' in {}", mode, entry.getKey());
                    }
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse drop_overrides {}", entry.getKey(), ex);
            }
        }
        ALWAYS_DROP = always;
        NEVER_DROP = never;
        BannerboundCore.LOGGER.info("Loaded drop overrides: {} always-drop, {} never-drop",
            always.size(), never.size());
    }

    private static final String BLOCK_EVERYWHERE = "*";

    public static Decision decide(String itemId, @Nullable String sourceId) {
        Set<String> blockedSources = NEVER_DROP.get(itemId);
        if (blockedSources != null) {
            if (blockedSources.contains(BLOCK_EVERYWHERE)) {
                return Decision.NEVER_DROP;
            }
            if (sourceId != null && blockedSources.contains(sourceId)) {
                return Decision.NEVER_DROP;
            }
        }
        if (ALWAYS_DROP.contains(itemId)) {
            return Decision.ALWAYS_DROP;
        }
        return Decision.DEFAULT;
    }
}
