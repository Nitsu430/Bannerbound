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
 * Client-side item tooltip event handlers (merged from TooltipHandlers, TooltipHandlers, TooltipHandlers).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class TooltipHandlers {
    private TooltipHandlers() {
    }

    // ---- from TooltipHandlers ----

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

    // ---- from TooltipHandlers ----

    /*
     * Appends a green "Food value: X" line to any item the {@link ClientFoodValueState} table
     * recognises as food. Silent for items with no food value (no line shown). Sits parallel to
     * {@link TooltipHandlers}; same {@code Dist.CLIENT} mod-bus subscriber pattern.
     */
    @SubscribeEvent
    public static void foodValueOnTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        float value = ClientFoodValueState.effectiveValue(stack);
        if (value <= 0f) return;
        event.getToolTip().add(Component.translatable("bannerbound.tooltip.food_value",
            String.format("%.2f", value)).withStyle(ChatFormatting.GREEN));
    }

    // ---- from TooltipHandlers ----

    /*
     * Appends a purple "Appeal: X" line to every block item's tooltip. The value is the appeal
     * resolved for the local player's settlement (base + culture-style overrides), synced via
     * {@link ClientBlockAppealState}, so it changes with the settlement's chosen style.
     */
    @SubscribeEvent
    public static void appealOnTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        // Disguised/unknown items get their tooltip rewritten by TooltipHandlers — leave them.
        if (UnknownItemHelper.isUnknownForLocalPlayer(stack)) return;

        float appeal = ClientBlockAppealState.appealOf(blockItem.getBlock());
        event.getToolTip().add(Component.translatable("bannerbound.tooltip.appeal",
            String.format("%.2f", appeal)).withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
