package com.bannerbound.antiquity.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

/**
 * The loaded set of modular {@link ArrowPart}s, indexed slot -> material -> part. One shared static
 * holder backs BOTH sides: the integrated/dedicated server fills it from {@link ArrowPartManager}
 * (datapack apply) and the client fills it from the server sync or the jar fallback - see
 * {@link com.bannerbound.antiquity.item.ArrowParts}, the accessor everything else goes through.
 * {@code sorted(slot)} lists parts best-first for NPC selection: higher priority, then heavier,
 * then by material id so the order stays stable across reloads.
 */
@ApiStatus.Internal
public final class ArrowPartRegistry {
    private ArrowPartRegistry() {}

    private static volatile Map<String, Map<String, ArrowPart>> bySlot = Map.of();
    private static volatile Map<String, List<ArrowPart>> sortedBySlot = Map.of();

    public static void replace(List<ArrowPart> parts) {
        Map<String, Map<String, ArrowPart>> slots = new LinkedHashMap<>();
        for (ArrowPart p : parts) {
            slots.computeIfAbsent(p.slot(), s -> new LinkedHashMap<>()).put(p.material(), p);
        }
        Map<String, List<ArrowPart>> sorted = new LinkedHashMap<>();
        for (var e : slots.entrySet()) {
            List<ArrowPart> list = new ArrayList<>(e.getValue().values());
            list.sort(Comparator.comparingInt(ArrowPart::priority).reversed()
                .thenComparing(Comparator.comparingInt(ArrowPart::weight).reversed())
                .thenComparing(ArrowPart::material));
            sorted.put(e.getKey(), Collections.unmodifiableList(list));
        }
        bySlot = Collections.unmodifiableMap(slots);
        sortedBySlot = Collections.unmodifiableMap(sorted);
    }

    @Nullable
    public static ArrowPart get(String slot, String material) {
        Map<String, ArrowPart> m = bySlot.get(slot);
        return m == null ? null : m.get(material);
    }

    public static List<ArrowPart> sorted(String slot) {
        return sortedBySlot.getOrDefault(slot, List.of());
    }

    public static List<ArrowPart> all() {
        List<ArrowPart> out = new ArrayList<>();
        for (Map<String, ArrowPart> m : bySlot.values()) out.addAll(m.values());
        return out;
    }

    public static boolean isEmpty() {
        return bySlot.isEmpty();
    }
}
