package com.bannerbound.core.api.settlement;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * A culture style (Forest, Desert, Dark Oak, ...) loaded from a JSON datapack file, matching its
 * file stem as the id. A style is a named bundle of two override tables that REPLACE base values
 * entirely where present: per-block appeal overrides (consumed by AppealResolver - a Desert town
 * rewards sandstone, a Forest town rewards wood) and per-item food-deposit overrides (consumed by
 * FoodValueLoader.effective - a Desert town values cactus, a Forest town berries). The override
 * getters return 0f when absent, so callers must gate on the hasOverride/hasFoodOverride check.
 * Picking a style at settlement founding is how a player declares a biome / culture aesthetic.
 */
@ApiStatus.Internal
public record CultureStyle(String id, String nameKey,
                            Map<Block, Float> overrides,
                            Map<Item, Float> foodOverrides) {
    public boolean hasOverride(Block block) {
        return overrides.containsKey(block);
    }

    public float override(Block block) {
        return overrides.getOrDefault(block, 0f);
    }

    public boolean hasFoodOverride(Item item) {
        return foodOverrides.containsKey(item);
    }

    public float foodOverride(Item item) {
        return foodOverrides.getOrDefault(item, 0f);
    }
}
