package com.bannerbound.core.menu;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * One summed line in the Stockpile terminal: a count-1 display stack (item + components for render
 * and matching) plus the total quantity across the stockpile's enclosed containers. Totals can
 * exceed a stack, which is why the terminal syncs this virtual list instead of using real Slots.
 */
@ApiStatus.Internal
public record StockEntry(ItemStack display, int total) {
    public static final StreamCodec<RegistryFriendlyByteBuf, StockEntry> STREAM_CODEC =
        StreamCodec.composite(
            ItemStack.STREAM_CODEC, StockEntry::display,
            ByteBufCodecs.VAR_INT, StockEntry::total,
            StockEntry::new);
}
