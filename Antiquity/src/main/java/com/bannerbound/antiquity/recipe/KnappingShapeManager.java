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
import net.minecraft.world.item.Item;

/**
 * Datapack loader for knapping shapes - reads every JSON under
 * {@code data/<namespace>/knapping_shapes/}. Registered as a server reload listener in
 * {@code AntiquityEvents}; the shapes are pushed to the client inside the
 * {@code OpenKnappingPayload} when a knapping session starts, so the client screen is
 * self-contained, and {@code applyEntries} is public so {@code ClientDatapackRecipes} can re-read
 * the same JSONs on remote clients, where server datapacks don't reach. {@code byHead} is the
 * server-side validation lookup when a COMPLETE comes back from the client.
 */
@ApiStatus.Internal
public class KnappingShapeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<KnappingShape> shapes = List.of();

    public KnappingShapeManager() {
        super(GSON, "knapping_shapes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<KnappingShape> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            KnappingShape.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid knapping shape {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        shapes = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} knapping shape(s).", shapes.size());
    }

    public static List<KnappingShape> all() {
        return shapes;
    }

    @Nullable
    public static KnappingShape byHead(Item head) {
        for (KnappingShape s : shapes) {
            if (s.head() == head) return s;
        }
        return null;
    }
}
