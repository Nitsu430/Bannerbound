package com.bannerbound.core.entity;

import net.minecraft.world.item.Item;

/**
 * A settlement-less {@link CitizenEntity} fighter whose combat stats come from an explicit loadout
 * rather than a settlement's tool age - barbarians and city-state mercenaries.
 * {@link CitizenCombatGoal} reads these instead of the settlement lookup, so both fight with their
 * assigned weapon/damage. combatDamage() (half-hearts) and combatAttackSpeed() (attacks/sec) both
 * treat &lt;= 0 as "fall back to bare hands / default"; meleeItem() is the weapon to hold/swap to in
 * melee (may be null / AIR); prefersRanged() marks a kiting bowman that only melees when cornered;
 * combatSpeed() is a per-member chase-speed variance multiplier.
 */
public interface CombatantCitizen {
    double combatDamage();

    double combatAttackSpeed();

    Item meleeItem();

    boolean prefersRanged();

    double combatSpeed();
}
