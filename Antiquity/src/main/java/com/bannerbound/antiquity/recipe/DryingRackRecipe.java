package com.bannerbound.antiquity.recipe;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A data-driven Drying Rack recipe: one input item that dries, over {@code ticks}, into a result.
 * Loaded from {@code data/<namespace>/drying_recipes/*.json}, e.g.
 * <pre>
 * { "input": "bannerboundantiquity:plant_fiber",
 *   "result": { "id": "bannerboundantiquity:thatch_bundle", "count": 1 },
 *   "ticks": 600 }
 * </pre>
 * Each occupied slot on a rack runs one of these independently. {@link #category()} is the
 * NPC-tending split: "food" = the Cook tends it (jerky, dried fish), "craft" = General Crafts
 * tends it (plant fiber -> thatch), "none" = no NPC touches it (cured hide stays the Tannery's
 * own leather line; a generic rack still works by hand). An explicit {@code "category"} JSON
 * field wins; when absent it derives from the result (edible -> food, else craft), so pre-split
 * JSONs keep working (thatch derives to craft).
 */
@ApiStatus.Internal
public record DryingRackRecipe(Item input, ItemStack result, int ticks, String categoryRaw) {

    public static final String FOOD = "food";
    public static final String CRAFT = "craft";
    public static final String NONE = "none";

    public static final Codec<DryingRackRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("input").forGetter(DryingRackRecipe::input),
        ItemStack.CODEC.fieldOf("result").forGetter(DryingRackRecipe::result),
        Codec.INT.optionalFieldOf("ticks", 600).forGetter(DryingRackRecipe::ticks),
        Codec.STRING.optionalFieldOf("category", "").forGetter(DryingRackRecipe::categoryRaw)
    ).apply(instance, DryingRackRecipe::new));

    public String category() {
        if (!categoryRaw.isEmpty()) return categoryRaw;
        return result.has(net.minecraft.core.component.DataComponents.FOOD) ? FOOD : CRAFT;
    }
}
