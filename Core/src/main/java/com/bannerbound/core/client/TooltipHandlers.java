package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Client-side item tooltip handlers (mod-bus, Dist.CLIENT), three ItemTooltipEvent subscribers:
 * onTooltip replaces an unknown/undiscovered item's tooltip with the masked name + action (via
 * UnknownItemHelper); foodValueOnTooltip appends a green "Food value: X" line for items
 * ClientFoodValueState recognises as food (silent otherwise); appealOnTooltip appends a purple
 * "Appeal: X" line to block items, resolved for the local player's settlement (base + culture-style
 * overrides, synced via ClientBlockAppealState) so it tracks the chosen style. appealOnTooltip skips
 * unknown items so it never fights onTooltip's rewrite.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class TooltipHandlers {
    private TooltipHandlers() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!UnknownItemHelper.isUnknownForLocalPlayer(stack)) {
            return;
        }
        var list = event.getToolTip();
        list.clear();
        list.add(UnknownItemHelper.unknownName());
        list.add(UnknownItemHelper.unknownAction());
    }

    @SubscribeEvent
    public static void foodValueOnTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        float value = ClientFoodValueState.effectiveValue(stack);
        if (value <= 0f) return;
        event.getToolTip().add(Component.translatable("bannerbound.tooltip.food_value",
            String.format("%.2f", value)).withStyle(ChatFormatting.GREEN));
    }

    @SubscribeEvent
    public static void appealOnTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        if (UnknownItemHelper.isUnknownForLocalPlayer(stack)) return;

        float appeal = ClientBlockAppealState.appealOf(blockItem.getBlock());
        event.getToolTip().add(Component.translatable("bannerbound.tooltip.appeal",
            String.format("%.2f", appeal)).withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
