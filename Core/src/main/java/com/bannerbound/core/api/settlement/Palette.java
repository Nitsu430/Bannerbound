package com.bannerbound.core.api.settlement;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.level.block.Block;

/**
 * A culture palette loaded from a JSON datapack file (see
 * {@link com.bannerbound.core.api.settlement.data.PaletteLoader}): a named bundle of blocks, each
 * carrying an appeal bonus. Unlike a {@link CultureStyle} (whose per-block values REPLACE the base
 * appeal), a palette's bonus is ADDED on top of the resolved appeal while the palette is one of the
 * settlement's active palettes (see {@link AppealResolver}) - so a Brown Haven palette granting
 * dirt +0.15 raises dirt's settlement-wide appeal by 0.15 while active. Palettes unlock via the
 * culture/research tree: a node's {@code unlocks.palette: ["id"]} folds into the flag
 * {@code unlock.palette.<id>}, queried through
 * {@link com.bannerbound.core.api.research.ResearchManager#hasFlagEitherTree}. Fields: id matches
 * the JSON file stem (e.g. "brown_haven"); name is a player-facing literal string; bonuses is the
 * per-block appeal bonus, insertion-ordered for stable UI rendering.
 */
@ApiStatus.Internal
public record Palette(String id, String name, Map<Block, Float> bonuses) {
    public boolean has(Block block) {
        return bonuses.containsKey(block);
    }

    public float bonus(Block block) {
        return bonuses.getOrDefault(block, 0f);
    }

    public List<Block> blocks() {
        return List.copyOf(bonuses.keySet());
    }
}
