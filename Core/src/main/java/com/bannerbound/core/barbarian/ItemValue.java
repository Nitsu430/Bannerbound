package com.bannerbound.core.barbarian;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.data.FoodValueLoader;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;

/**
 * Fully-automatic trade valuation: a single abstract "worth" number per item, derived purely from
 * signals the game already carries - food value, material class (gems/ingots/ores/leather via the
 * common {@code c:} tags), tool/armor non-stackability, and rarity. No authored value table; the
 * numbers are relative, so only their ratios matter (10 wheat ~ 1 iron, etc.).
 *
 * <p>Used by the barbarian barter system to score demands, generate fair offers, and judge a
 * player's counter-offer. Tune the constants here to reshape the whole economy at once.
 * unitValue returns >= 1 for any real item and 0 for air/null; value(item,count) is unitValue x
 * count; value(itemId,count) resolves a registry id and yields 0 for an unknown id.
 */
@ApiStatus.Internal
public final class ItemValue {
    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", path));
    }

    private static final TagKey<Item> GEMS = tag("gems");
    private static final TagKey<Item> INGOTS = tag("ingots");
    private static final TagKey<Item> RAW_MATERIALS = tag("raw_materials");
    private static final TagKey<Item> ORES = tag("ores");
    private static final TagKey<Item> NUGGETS = tag("nuggets");
    private static final TagKey<Item> LEATHERS = tag("leathers");
    private static final TagKey<Item> STORAGE_BLOCKS = tag("storage_blocks");

    private ItemValue() {}

    public static int unitValue(Item item) {
        if (item == null || item == Items.AIR) return 0;
        ItemStack stack = new ItemStack(item);
        double v = 1.0;

        float food = FoodValueLoader.base(item);
        if (food > 0) v = Math.max(v, food * 1.5);

        if (stack.is(GEMS)) v = Math.max(v, 9);
        else if (stack.is(STORAGE_BLOCKS)) v = Math.max(v, 9);
        else if (stack.is(INGOTS)) v = Math.max(v, 5);
        else if (stack.is(RAW_MATERIALS) || stack.is(ORES)) v = Math.max(v, 3);
        else if (stack.is(LEATHERS)) v = Math.max(v, 3);
        else if (stack.is(NUGGETS)) v = Math.max(v, 1.5);

        if (stack.getMaxStackSize() == 1) v = Math.max(v, 6);

        v *= switch (stack.getRarity()) {
            case UNCOMMON -> 1.5;
            case RARE -> 2.5;
            case EPIC -> 4.0;
            default -> 1.0;
        };
        if (stack.getRarity() == Rarity.COMMON && v < 1.0) v = 1.0;
        return Math.max(1, (int) Math.round(v));
    }

    public static int value(Item item, int count) {
        return unitValue(item) * Math.max(0, count);
    }

    public static int value(String itemId, int count) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return 0;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return value(item, count);
    }
}
