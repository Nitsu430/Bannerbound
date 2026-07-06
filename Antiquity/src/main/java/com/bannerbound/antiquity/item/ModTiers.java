package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/**
 * Custom tool tiers for Antiquity.
 *
 * <p>Bone sits BEFORE wood in the progression: lower durability than wood (48 vs 59, still above
 * the flint knife's 26) but a touch faster to mine with (2.5 vs wood's 2.0). It mines the stone
 * family for drops but no ores at all (even coal) - the {@link #INCORRECT_FOR_BONE_TOOL} tag
 * (defined by the datapack tag of the same name) is {@code #minecraft:incorrect_for_wooden_tool}
 * union {@code #c:ores}, denying everything a wooden tool can't mine PLUS every ore. Attack damage
 * is left to each tool's own modifiers (tier bonus 0), so the per-item numbers read exactly like
 * the user-specified values.
 */
public final class ModTiers {
    private ModTiers() {}

    public static final TagKey<Block> INCORRECT_FOR_BONE_TOOL = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "incorrect_for_bone_tool"));

    public static final Tier BONE = new Tier() {
        @Override public int getUses() { return 48; }
        @Override public float getSpeed() { return 2.5F; }
        @Override public float getAttackDamageBonus() { return 0.0F; }
        @Override public TagKey<Block> getIncorrectBlocksForDrops() { return INCORRECT_FOR_BONE_TOOL; }
        @Override public int getEnchantmentValue() { return 5; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(Items.BONE); }
    };
}
