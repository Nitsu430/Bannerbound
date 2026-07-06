package com.bannerbound.antiquity.recipe;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * One datapack-defined modular-arrow part - a tip, shaft, or back. Loaded from
 * {@code data/<namespace>/arrow_parts/*.json} by {@link ArrowPartManager}, held in
 * {@link ArrowPartRegistry}, and synced server->client via {@code ArrowPartsSyncPayload}
 * (STREAM_CODEC below), so a modpack adds an arrow material (e.g. an iron tip) with a single JSON
 * plus two textures and gets crafting, stats, the NPC fletcher's part choice, the in-flight
 * projectile, and the layered inventory icon - no code or model files. Field semantics are
 * slot-specific: {@code material} is the id written to the arrow's ARROW_TIP/SHAFT/BACK component;
 * {@code ingredient} is the item consumed at the fletching station; {@code damage} is a tip-only
 * base damage factor (1.0 = flint baseline); {@code weight} (tip/shaft; 0 = light flint/wood) adds
 * damage but steepens drop; {@code accuracy} is a back-only multiplier on the bow's inaccuracy
 * (lower = tighter); {@code priority} orders NPC fletcher preference (higher = preferred).
 * {@code itemTexture} is an atlas sprite under {@code textures/item/}; {@code projectileTexture}
 * is a full texture path (textures/.../x.png) for the in-flight layer.
 */
@ApiStatus.Internal
public record ArrowPart(String slot, String material, Item ingredient,
                        double damage, int weight, double accuracy, int priority,
                        ResourceLocation itemTexture, ResourceLocation projectileTexture) {

    public static final String SLOT_TIP = "tip";
    public static final String SLOT_SHAFT = "shaft";
    public static final String SLOT_BACK = "back";

    public static final Codec<ArrowPart> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("slot").forGetter(ArrowPart::slot),
        Codec.STRING.fieldOf("material").forGetter(ArrowPart::material),
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("ingredient").forGetter(ArrowPart::ingredient),
        Codec.DOUBLE.optionalFieldOf("damage", 1.0).forGetter(ArrowPart::damage),
        Codec.INT.optionalFieldOf("weight", 0).forGetter(ArrowPart::weight),
        Codec.DOUBLE.optionalFieldOf("accuracy", 1.0).forGetter(ArrowPart::accuracy),
        Codec.INT.optionalFieldOf("priority", 0).forGetter(ArrowPart::priority),
        ResourceLocation.CODEC.fieldOf("item_texture").forGetter(ArrowPart::itemTexture),
        ResourceLocation.CODEC.fieldOf("projectile_texture").forGetter(ArrowPart::projectileTexture)
    ).apply(i, ArrowPart::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArrowPart> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.slot);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.material);
            ByteBufCodecs.STRING_UTF8.encode(buf, BuiltInRegistries.ITEM.getKey(p.ingredient).toString());
            buf.writeDouble(p.damage);
            buf.writeVarInt(p.weight);
            buf.writeDouble(p.accuracy);
            buf.writeVarInt(p.priority);
            ResourceLocation.STREAM_CODEC.encode(buf, p.itemTexture);
            ResourceLocation.STREAM_CODEC.encode(buf, p.projectileTexture);
        },
        buf -> new ArrowPart(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            BuiltInRegistries.ITEM.get(ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf))),
            buf.readDouble(),
            buf.readVarInt(),
            buf.readDouble(),
            buf.readVarInt(),
            ResourceLocation.STREAM_CODEC.decode(buf),
            ResourceLocation.STREAM_CODEC.decode(buf)));
}
