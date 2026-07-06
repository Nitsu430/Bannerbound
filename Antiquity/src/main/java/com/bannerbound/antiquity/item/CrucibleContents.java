package com.bannerbound.antiquity.item;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * What a crucible holds, stored as a {@code DataComponentType} on the crucible item (and mirrored
 * on the placed {@link com.bannerbound.antiquity.block.entity.CrucibleBlockEntity}). Two phases:
 * the CHARGE phase - a list of raw smeltable items (raw ore, metal heads, ingots, ...) dropped in
 * while the crucible sits on the ground, visible inside the block, kept when the crucible is
 * broken and re-inserted, and recoverable with no loss if pulled out before it melts - and the
 * MOLTEN phase - once heated in a bloomery the charge melts to {@code mb} millibuckets of
 * {@code metalId} (the resolved metal/alloy id, e.g. "bronze"; empty pre-melt), the charge list
 * clears and {@code molten} flips true; only a molten crucible can pour. {@code tintColor} is the
 * resolved packed 0xRRGGBB colour for the molten layer. Immutable: withAdded/withoutLast/drain
 * return new instances; lastItem/withoutLast let a still-solid charge be popped back out item by
 * item, and drain empties to EMPTY when drained dry.
 */
public record CrucibleContents(List<ItemStack> charge, boolean molten, String metalId, int mb, int tintColor) {

    public static final CrucibleContents EMPTY =
        new CrucibleContents(List.of(), false, "", 0, 0xFFFFFF);

    public static final Codec<CrucibleContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ItemStack.CODEC.listOf().optionalFieldOf("charge", List.of()).forGetter(CrucibleContents::charge),
        Codec.BOOL.optionalFieldOf("molten", false).forGetter(CrucibleContents::molten),
        Codec.STRING.optionalFieldOf("metal_id", "").forGetter(CrucibleContents::metalId),
        Codec.INT.optionalFieldOf("mb", 0).forGetter(CrucibleContents::mb),
        Codec.INT.optionalFieldOf("tint_color", 0xFFFFFF).forGetter(CrucibleContents::tintColor)
    ).apply(instance, CrucibleContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CrucibleContents> STREAM_CODEC =
        StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), CrucibleContents::charge,
            ByteBufCodecs.BOOL, CrucibleContents::molten,
            ByteBufCodecs.STRING_UTF8, CrucibleContents::metalId,
            ByteBufCodecs.VAR_INT, CrucibleContents::mb,
            ByteBufCodecs.INT, CrucibleContents::tintColor,
            CrucibleContents::new);

    public static CrucibleContents ofCharge(List<ItemStack> items) {
        return new CrucibleContents(List.copyOf(items), false, "", 0, 0xFFFFFF);
    }

    public static CrucibleContents molten(String metalId, int mb, int tintColor) {
        return new CrucibleContents(List.of(), true, metalId, mb, tintColor);
    }

    public boolean isEmpty() {
        return charge.isEmpty() && mb <= 0;
    }

    public boolean hasCharge() {
        return !charge.isEmpty();
    }

    public int totalMb() {
        return molten ? mb : 0;
    }

    public String dominantMetal() {
        return metalId;
    }

    public CrucibleContents withAdded(ItemStack stack) {
        List<ItemStack> next = new ArrayList<>(charge);
        next.add(stack.copyWithCount(1));
        return new CrucibleContents(next, false, "", 0, tintColor);
    }

    public ItemStack lastItem() {
        return charge.isEmpty() ? ItemStack.EMPTY : charge.get(charge.size() - 1).copy();
    }

    public CrucibleContents withoutLast() {
        if (charge.isEmpty()) return this;
        List<ItemStack> next = new ArrayList<>(charge);
        next.remove(next.size() - 1);
        return new CrucibleContents(next, false, "", 0, tintColor);
    }

    public CrucibleContents drain(int drainMb) {
        if (!molten || drainMb <= 0) return this;
        int left = Math.max(0, mb - drainMb);
        if (left <= 0) return EMPTY;
        return new CrucibleContents(List.of(), true, metalId, left, tintColor);
    }
}
