package com.bannerbound.antiquity.carpentry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Datapack loader for carpentry output rows that reads every JSON under
 * {@code data/<namespace>/carpentry_outputs/}. Each row is a templated variant (stairs, slab, ...)
 * resolved per wood family at runtime. Registered as a server reload listener in AntiquityEvents; the
 * resolved, affordable offers are synced to clients on the block entity itself, but applyEntries() is
 * public so the client-side jar loader (ClientDatapackRecipes) can reuse it on remote clients, where
 * server datapacks don't reach. Rows are sorted by variant name so the picker's browse order is
 * stable. Mirrors {@link CarpentryAssemblyManager}.
 */
@ApiStatus.Internal
public class CarpentryOutputManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<CarpentryOutput> outputs = List.of();

    public CarpentryOutputManager() {
        super(GSON, "carpentry_outputs");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<CarpentryOutput> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            CarpentryOutput.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid carpentry output {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        loaded.sort(Comparator.comparing(CarpentryOutput::variant));
        outputs = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} carpentry output(s).", outputs.size());
    }

    public static List<CarpentryOutput> all() {
        return outputs;
    }
}
