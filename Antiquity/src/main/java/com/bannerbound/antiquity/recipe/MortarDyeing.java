package com.bannerbound.antiquity.recipe;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * Turns a finished Mortar and Pestle dye liquid into an actual dyed item: applied when a player
 * uses a dyeable item on a mortar holding a dye. Liquid ids are plain strings: "water" and empty
 * mean "not a dye", "ink" dyes black, anything else must parse as a vanilla DyeColor name.
 * Handles two dye paths: leather/component-dyeable items (minecraft:dyeable tag -> DYED_COLOR
 * component) and the uniform {@code <colour>_<family>} block families (wool, terracotta, etc.),
 * recoloured purely by registry-name prefix swap - already-coloured variants get their colour
 * prefix replaced, uncoloured bases (glass, terracotta, ...) map to their stained family.
 * recolor() returns an empty stack when the item is not dyeable, preserving count otherwise.
 */
@ApiStatus.Internal
public final class MortarDyeing {
    private static final Set<String> COLORED_FAMILIES = Set.of(
        "wool", "carpet", "bed", "banner", "candle", "terracotta", "glazed_terracotta",
        "concrete", "concrete_powder", "stained_glass", "stained_glass_pane", "shulker_box");
    private static final Map<String, String> UNCOLORED_BASES = Map.of(
        "glass", "stained_glass",
        "glass_pane", "stained_glass_pane",
        "terracotta", "terracotta",
        "candle", "candle",
        "shulker_box", "shulker_box");

    private MortarDyeing() {
    }

    @Nullable
    public static DyeColor dyeColorFor(String liquidId) {
        if (liquidId == null || liquidId.isEmpty() || "water".equals(liquidId)) {
            return null;
        }
        if ("ink".equals(liquidId)) {
            return DyeColor.BLACK;
        }
        return DyeColor.byName(liquidId, null);
    }

    public static ItemStack recolor(ItemStack stack, DyeColor color) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (stack.is(ItemTags.DYEABLE)) {
            ItemStack out = stack.copy();
            out.set(DataComponents.DYED_COLOR, new DyedItemColor(color.getTextureDiffuseColor(), true));
            return out;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        String path = id.getPath();
        for (DyeColor existing : DyeColor.values()) {
            String prefix = existing.getName() + "_";
            if (path.startsWith(prefix)) {
                String family = path.substring(prefix.length());
                if (COLORED_FAMILIES.contains(family)) {
                    return makeStack(id.getNamespace(), color.getName() + "_" + family, stack.getCount());
                }
                return ItemStack.EMPTY;
            }
        }
        String family = UNCOLORED_BASES.get(path);
        if (family != null) {
            return makeStack(id.getNamespace(), color.getName() + "_" + family, stack.getCount());
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack makeStack(String namespace, String path, int count) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(namespace, path));
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, count);
    }
}
