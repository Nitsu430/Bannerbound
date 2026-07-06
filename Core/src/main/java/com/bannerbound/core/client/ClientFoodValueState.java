package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side cache of the base per-item food values. Loaded from the server via
 * {@link com.bannerbound.core.network.FoodValueSyncPayload} on join + datapack reload. Read by
 * the green "Food value" tooltip line - see {@code TooltipHandlers}. A value of 0 means the item
 * is not food (the tooltip line is gated off it).
 *
 * <p>Expansions can register per-stack <b>value modifiers</b> (e.g. Antiquity halving the line for
 * bland food) via {@link #addModifier}, so the tooltip shows the value a stack would actually
 * contribute, not just its item's base value.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientFoodValueState {
    private static volatile Map<Item, Float> VALUES = Map.of();
    private static volatile List<ToDoubleFunction<ItemStack>> MODIFIERS = List.of();

    private ClientFoodValueState() {
    }

    public static void replace(List<String> ids, List<Float> values) {
        Map<Item, Float> map = new HashMap<>();
        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            ResourceLocation rl = ResourceLocation.tryParse(ids.get(i));
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                map.put(BuiltInRegistries.ITEM.get(rl), values.get(i));
            }
        }
        VALUES = Map.copyOf(map);
    }

    public static float valueOf(Item item) {
        return VALUES.getOrDefault(item, 0f);
    }

    public static boolean isFood(Item item) {
        return valueOf(item) > 0f;
    }

    public static void addModifier(ToDoubleFunction<ItemStack> modifier) {
        List<ToDoubleFunction<ItemStack>> list = new ArrayList<>(MODIFIERS);
        list.add(modifier);
        MODIFIERS = List.copyOf(list);
    }

    public static float effectiveValue(ItemStack stack) {
        float v = valueOf(stack.getItem());
        if (v <= 0f) return v;
        for (ToDoubleFunction<ItemStack> modifier : MODIFIERS) {
            v *= (float) modifier.applyAsDouble(stack);
        }
        return v;
    }
}
