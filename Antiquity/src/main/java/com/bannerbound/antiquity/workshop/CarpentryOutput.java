package com.bannerbound.antiquity.workshop;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One data-driven carpentry output row, written ONCE per variant and resolved per wood family by
 * {@link WoodFamily#variant(String)}. Loaded from {@code data/<namespace>/carpentry_outputs/*.json},
 * e.g. <pre>{ "variant": "stairs", "log_cost": 1, "yield": 6 }</pre>
 * "variant" is the family variant suffix (planks, stairs, slab, door, ...), "log_cost" the logs one
 * crafted unit costs from the table's budget, and "yield" how many output items it produces.
 */
@ApiStatus.Internal
public record CarpentryOutput(String variant, int logCost, int yield) {
    public static final Codec<CarpentryOutput> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("variant").forGetter(CarpentryOutput::variant),
        Codec.INT.optionalFieldOf("log_cost", 1).forGetter(CarpentryOutput::logCost),
        Codec.INT.optionalFieldOf("yield", 1).forGetter(CarpentryOutput::yield)
    ).apply(i, CarpentryOutput::new));
}
