package com.bannerbound.antiquity.tannery;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.AntiquityEvents;
import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.SpearItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/**
 * The four hunting-weapon categories used for hide-quality grading (RDR2-style preferred weapon):
 * matching an animal's preferred category yields a GREAT hide; a different valid category yields
 * STANDARD; no valid weapon yields POOR. Detection is tag/instanceof based so the per-species
 * preference table ({@code hide_preferences/*.json}) stays the only thing a designer edits.
 * of() returns null for anything that isn't a recognized hunting weapon (fists, a pickaxe, ...);
 * arrow kills are usually resolved from the projectile by the caller, but a held bow still maps
 * to ARROW for completeness. HUNTER_BOWS lives in the Core "bannerbound" namespace because the
 * hunter NPC shares it.
 */
public enum WeaponCategory {
    BLADE,
    SPEAR,
    ARROW,
    BLUNT;

    public static final TagKey<Item> BLUNT_WEAPONS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "blunt_weapons"));
    public static final TagKey<Item> HUNTER_BOWS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("bannerbound", "hunter_bows"));

    @Nullable
    public static WeaponCategory of(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) return null;
        Item item = weapon.getItem();
        if (item instanceof SpearItem) return SPEAR;
        if (weapon.is(BLUNT_WEAPONS)) return BLUNT;
        if (weapon.is(HUNTER_BOWS) || item instanceof BowItem || item instanceof ArrowItem) return ARROW;
        if (weapon.is(AntiquityEvents.CUTTING_TOOLS) || item instanceof SwordItem) return BLADE;
        return null;
    }
}
