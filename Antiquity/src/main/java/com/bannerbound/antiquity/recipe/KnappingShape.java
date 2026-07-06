package com.bannerbound.antiquity.recipe;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * A data-driven knapping shape - the 3x3 silhouette a player chips out of a rock to make a tool
 * head ({@code head}). {@code keep} lists the grid cells that REMAIN stone; every other cell is
 * flaked away. Cells are numbered row-major, top row first:
 * <pre>
 *   0 1 2
 *   3 4 5
 *   6 7 8
 * </pre>
 * When the remaining cells exactly equal {@code keep}, the head is made; chipping every cell away
 * "breaks the stone" (a wasted rock). The keep set must be UNIQUE across all loaded shapes, since
 * the silhouette alone identifies the recipe. {@code percentage_standard} / {@code percentage_fine}
 * are the total minigame score (as % of the possible per-rep 100s) needed for STANDARD / FINE
 * quality. Loaded from {@code data/<namespace>/knapping_shapes/*.json}:
 * <pre>{ "head": "bannerboundantiquity:stone_pick_head", "keep": [0, 1, 2],
 *   "percentage_standard": 50, "percentage_fine": 75 }</pre>
 */
@ApiStatus.Internal
public record KnappingShape(Item head, List<Integer> keep, int percentage_standard, int percentage_fine) {
    public static final Codec<KnappingShape> CODEC = RecordCodecBuilder.create(i -> i.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("head").forGetter(KnappingShape::head),
        Codec.INT.listOf().fieldOf("keep").forGetter(KnappingShape::keep),
        Codec.INT.optionalFieldOf("percentage_standard", 50).forGetter(KnappingShape::percentage_standard),
        Codec.INT.optionalFieldOf("percentage_fine", 75).forGetter(KnappingShape::percentage_fine)
    ).apply(i, KnappingShape::new));

    public int keepMask() {
        int mask = 0;
        for (int c : keep) {
            if (c >= 0 && c < 9) mask |= (1 << c);
        }
        return mask;
    }
}
