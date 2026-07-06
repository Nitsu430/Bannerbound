package com.bannerbound.core.social;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * The registry of all {@link ThoughtType}s -- Core's built-in {@link ThoughtKind} constants plus any
 * an expansion registers -- keyed by {@link ThoughtType#id()}. This is what {@link Thought} resolves
 * a saved id back through, and the public seam an addon publishes a new thought through: build one
 * with ThoughtType.builder(...), pass it to register() (returns the argument so it can be assigned to
 * a static final field in one line), then attach with {@code citizen.getThoughts().add(MY, null, now,
 * rng)}. First registration under an id wins; duplicates are ignored.
 */
public final class ThoughtTypes {
    private ThoughtTypes() {}

    private static final Map<ResourceLocation, ThoughtType> REGISTRY = new ConcurrentHashMap<>();

    static {
        // Force ThoughtKind init so built-ins self-register before any lookup (a citizen load can be the first touch).
        ThoughtKind.bootstrap();
    }

    public static <T extends ThoughtType> T register(T type) {
        REGISTRY.putIfAbsent(type.id(), type);
        return type;
    }

    @Nullable
    public static ThoughtType byId(ResourceLocation id) {
        return id == null ? null : REGISTRY.get(id);
    }

    @Nullable
    public static ThoughtType byId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : REGISTRY.get(rl);
    }
}
