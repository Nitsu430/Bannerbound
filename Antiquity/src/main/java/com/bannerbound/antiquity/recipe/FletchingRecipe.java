package com.bannerbound.antiquity.recipe;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.bannerbound.antiquity.craft.Fletching;

/**
 * A data-driven Fletching Station recipe - an unordered, count-based pile recipe (same exact-match
 * semantics as {@link CraftingStoneRecipe}, shared via {@code PileRecipes}) plus the
 * stretch-minigame knobs that drive the quality roll. Loaded from
 * {@code data/<namespace>/fletching_recipes/*.json}, e.g.:
 * <pre>
 * { "ingredients": [ { "item": "minecraft:stick", "count": 3 },
 *                    { "item": "bannerboundantiquity:plant_string", "count": 3 } ],
 *   "result": { "id": "bannerboundantiquity:primitive_bow", "count": 1 },
 *   "stretches": 3, "base_zone_pct": 0.18, "zone_decay": 0.65,
 *   "min_zone_pct": 0.06, "yellow_pad_pct": 0.05 }
 * </pre>
 * Knob semantics: {@code stretches} = hold-and-release reps the minigame requires;
 * {@code baseZonePct} = green-zone width (fraction of the bar) on the first stretch;
 * {@code zoneDecay} = per-stretch multiplier shrinking the green zone (<= 1 narrows each rep)
 * down to the {@code minZonePct} floor; {@code yellowPadPct} = width of the "good" amber band
 * flanking each side of the green zone. {@code inProgress} optionally names a display-only item
 * shown lying on the station while this recipe's minigame runs (set on commit, cleared on
 * complete/cancel).
 */
@ApiStatus.Internal
public record FletchingRecipe(List<Ing> ingredients, ItemStack result,
                              int stretches, float baseZonePct, float zoneDecay,
                              float minZonePct, float yellowPadPct,
                              java.util.Optional<Item> inProgress) {

    public record Ing(Item item, int count) implements PileRecipes.Counted {
        public static final Codec<Ing> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Ing::item),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Ing::count)
        ).apply(i, Ing::new));
    }

    public static final Codec<FletchingRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ing.CODEC.listOf().fieldOf("ingredients").forGetter(FletchingRecipe::ingredients),
        ItemStack.CODEC.fieldOf("result").forGetter(FletchingRecipe::result),
        Codec.INT.optionalFieldOf("stretches", 3).forGetter(FletchingRecipe::stretches),
        Codec.FLOAT.optionalFieldOf("base_zone_pct", 0.18F).forGetter(FletchingRecipe::baseZonePct),
        Codec.FLOAT.optionalFieldOf("zone_decay", 0.65F).forGetter(FletchingRecipe::zoneDecay),
        Codec.FLOAT.optionalFieldOf("min_zone_pct", 0.06F).forGetter(FletchingRecipe::minZonePct),
        Codec.FLOAT.optionalFieldOf("yellow_pad_pct", 0.05F).forGetter(FletchingRecipe::yellowPadPct),
        BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("in_progress").forGetter(FletchingRecipe::inProgress)
    ).apply(instance, FletchingRecipe::new));

    public Map<Item, Integer> requiredCounts() {
        return PileRecipes.requiredCounts(ingredients);
    }

    public boolean matches(Map<Item, Integer> placed) {
        return PileRecipes.matches(ingredients, placed);
    }
}
