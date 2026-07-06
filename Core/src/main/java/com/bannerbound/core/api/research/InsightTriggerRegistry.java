package com.bannerbound.core.api.research;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public registry describing how insight trigger counters are evaluated, and the source of truth
 * for the built-in trigger types. Expansion mods register a type during common setup, then call
 * {@link InsightManager#recordEvent} when it occurs. Kind decides accumulation: COUNT/EVENT
 * increments per occurrence; LEVEL stores a live "have >=N now" reading and is sticky once the
 * threshold is hit. obtain_item is LEVEL because it is a holdings poll (InsightManager#pollObtain
 * sums settlement storage + online members' inventories), NOT an event - so it counts items
 * however they were obtained and stamps nothing on items; it replaced the old craft_item trigger,
 * which could not see crafts at the mod's many custom workstations. targetRequired gates whether a
 * target must be authored; breed_animal takes an optional target ("" = any animal, else id/#tag).
 * Re-registering an id with different kind/targetRequired throws.
 */
public final class InsightTriggerRegistry {
    public enum Kind { COUNT, LEVEL, EVENT }

    public record Type(String id, Kind kind, boolean targetRequired) {}

    private static final Map<String, Type> TYPES = new LinkedHashMap<>();

    static {
        register("mine_block", Kind.COUNT, true);
        register("kill_entity", Kind.COUNT, true);
        register("place_block", Kind.COUNT, true);
        register("claim_chunk", Kind.COUNT, false);
        register("reach_population", Kind.LEVEL, false);
        register("obtain_item", Kind.LEVEL, true);
        register("breed_animal", Kind.COUNT, false);
    }

    private InsightTriggerRegistry() {}

    public static synchronized void register(String id, Kind kind, boolean targetRequired) {
        if (id == null || id.isBlank() || kind == null) {
            throw new IllegalArgumentException("Insight trigger id and kind are required");
        }
        String key = id.trim();
        Type previous = TYPES.putIfAbsent(key, new Type(key, kind, targetRequired));
        if (previous != null && (!previous.kind().equals(kind)
                || previous.targetRequired() != targetRequired)) {
            throw new IllegalStateException("Insight trigger already registered with different properties: " + key);
        }
    }

    public static synchronized Type get(String id) {
        return TYPES.get(id);
    }

    public static synchronized Map<String, Type> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
    }
}
