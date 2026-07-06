package com.bannerbound.antiquity.item;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * The stew identity cooked in a stone cooking pot - a snapshot of the {@code StewRecipe} (or the
 * generic fallback) it was made from: the display-name lang key (e.g. {@code stew.generic}), the
 * packed 0xRRGGBB liquid colour tinting the pot's stew layer, the food value one serving restores
 * (haunch scale), how many servings the full pot yields, the per-serving mob effects snapshotted
 * from the recipe, and a poisoned flag (any poisoned ingredient taints the whole stew). Carried
 * by the pot's block entity while a stew is held, and forward-compatible as a
 * {@code DataComponentType} for the future bowl item (woodworking): the same record can ride on
 * a portable serving once bowls exist.
 */
public record StewContents(String name, int tint, double foodPerServing, int servings,
                           List<MobEffectInstance> effects, boolean poisoned) {

    public static final Codec<StewContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("name", "").forGetter(StewContents::name),
        Codec.INT.optionalFieldOf("tint", 0xFFFFFF).forGetter(StewContents::tint),
        Codec.DOUBLE.optionalFieldOf("food_per_serving", 1.0).forGetter(StewContents::foodPerServing),
        Codec.INT.optionalFieldOf("servings", 1).forGetter(StewContents::servings),
        MobEffectInstance.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(StewContents::effects),
        Codec.BOOL.optionalFieldOf("poisoned", false).forGetter(StewContents::poisoned)
    ).apply(instance, StewContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, StewContents> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StewContents::name,
            ByteBufCodecs.INT, StewContents::tint,
            ByteBufCodecs.DOUBLE, StewContents::foodPerServing,
            ByteBufCodecs.VAR_INT, StewContents::servings,
            MobEffectInstance.STREAM_CODEC.apply(ByteBufCodecs.list()), StewContents::effects,
            ByteBufCodecs.BOOL, StewContents::poisoned,
            StewContents::new);

    public double totalFoodValue() {
        return foodPerServing * servings;
    }
}
