package com.bannerbound.antiquity.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The hidden poison riding a coated food ItemStack (a {@code POISONED_FOOD} data component). {@code
 * poisonId} is the {@link com.bannerbound.antiquity.poison.PoisonType} id; {@code dose} is how many
 * times it's been coated (-> the starting stage when eaten; {@link #withAnotherDose} caps it at the
 * poison's max so repeat coats can't run away); {@code poisoner} is the UUID string of the player
 * who laced it. The tooltip warning shows only to that poisoner and whoever currently shares their
 * settlement - so the reveal follows the poisoner's LIVE membership (resolved at display time), not
 * a snapshot. To everyone else the food looks clean.
 */
public record PoisonedFoodData(String poisonId, int dose, String poisoner) {
    public static final Codec<PoisonedFoodData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("poison_id").forGetter(PoisonedFoodData::poisonId),
        Codec.INT.fieldOf("dose").forGetter(PoisonedFoodData::dose),
        Codec.STRING.optionalFieldOf("poisoner", "").forGetter(PoisonedFoodData::poisoner)
    ).apply(instance, PoisonedFoodData::new));

    public static final StreamCodec<ByteBuf, PoisonedFoodData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, PoisonedFoodData::poisonId,
        ByteBufCodecs.VAR_INT, PoisonedFoodData::dose,
        ByteBufCodecs.STRING_UTF8, PoisonedFoodData::poisoner,
        PoisonedFoodData::new);

    public PoisonedFoodData withAnotherDose(int cap) {
        return new PoisonedFoodData(poisonId, Math.min(cap, dose + 1), poisoner);
    }
}
