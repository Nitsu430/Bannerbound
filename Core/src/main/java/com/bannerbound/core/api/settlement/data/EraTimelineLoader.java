package com.bannerbound.core.api.settlement.data;

import java.util.EnumMap;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads the era -> starting-year timeline used by the world-year HUD from
 * data/<namespace>/era_times/*.json. Every file is a map of era key (lowercase Era.key) to a
 * { "start_year": <int> } object; files from multiple namespaces are unioned and later
 * definitions for the same era key win, so an expansion datapack can override the base timeline
 * without replacing the whole file. Years are signed ints (negative = BC, positive = AD): ancient
 * typically starts deep in BC (e.g. -100000), future sits a century or two past the present. Eras
 * missing from the JSON fall back to DEFAULT_START_YEARS (buildDefaults) so the loader never
 * leaves the HUD broken; getAll() exposes the full map for sync.
 */
public final class EraTimelineLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "era_times";
    private static final Gson GSON = new Gson();

    private static final Map<Era, Integer> DEFAULT_START_YEARS = buildDefaults();
    private static volatile Map<Era, Integer> START_YEARS = DEFAULT_START_YEARS;

    public EraTimelineLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<Era, Integer> merged = new EnumMap<>(DEFAULT_START_YEARS);
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    Era era = Era.fromName(e.getKey());
                    if (era == null) {
                        BannerboundCore.LOGGER.warn("Unknown era '{}' in era_times {}", e.getKey(), entry.getKey());
                        continue;
                    }
                    JsonObject body = e.getValue().getAsJsonObject();
                    int year = GsonHelper.getAsInt(body, "start_year");
                    merged.put(era, year);
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse era_times {}", entry.getKey(), ex);
            }
        }
        START_YEARS = merged;
        BannerboundCore.LOGGER.info("Loaded era timeline: {} eras", merged.size());
    }

    public static int getStartYear(Era era) {
        Integer y = START_YEARS.get(era);
        return y != null ? y : DEFAULT_START_YEARS.getOrDefault(era, 0);
    }

    public static Map<Era, Integer> getAll() {
        return START_YEARS;
    }

    private static Map<Era, Integer> buildDefaults() {
        Map<Era, Integer> m = new EnumMap<>(Era.class);
        m.put(Era.ANCIENT, -100000);
        m.put(Era.CLASSICAL, -800);
        m.put(Era.MEDIEVAL, 450);
        m.put(Era.RENAISSANCE, 1450);
        m.put(Era.INDUSTRIAL, 1760);
        m.put(Era.DIESEL, 1900);
        m.put(Era.ATOMIC, 1945);
        m.put(Era.MODERN, 1990);
        m.put(Era.FUTURE, 2100);
        return m;
    }
}
