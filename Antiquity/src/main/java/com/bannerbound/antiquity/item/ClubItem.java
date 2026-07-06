package com.bannerbound.antiquity.item;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * A primitive blunt weapon - a heavy, slow club (the BLUNT weapon category for hide-preference
 * grading; small/skittish animals prefer it because it doesn't pierce the pelt). Not a cutting
 * tool (kept out of {@code #bannerboundantiquity:cutting_tools}), so it neither knaps nor harvests
 * fiber; it's listed in {@code #bannerboundantiquity:blunt_weapons}. Plain {@link Item} (not a
 * tiered digger) - durability loss on attack comes from {@link #hurtEnemy}. {@link #clubAttributes}
 * builds sword-style attack attributes from TOTAL displayed values.
 */
public class ClubItem extends Item {
    public ClubItem(Properties properties, int durability, double attackDamage, double attackSpeed) {
        super(properties.durability(durability).attributes(clubAttributes(attackDamage, attackSpeed)));
    }

    public static ItemAttributeModifiers clubAttributes(double damage, double speed) {
        // damage/speed are TOTAL displayed values - subtract the player base (1 dmg / 4 speed) for the modifier.
        return ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(BASE_ATTACK_DAMAGE_ID, damage - 1.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED,
                new AttributeModifier(BASE_ATTACK_SPEED_ID, speed - 4.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .build();
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
        return true;
    }
}
