package com.bannerbound.core.api.research;

import java.util.Map;
import java.util.OptionalInt;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * A parsed tool-age entry: one tier of tools (stone, iron, ...) a settlement advances to via
 * research. Loaded by {@code ToolAgeLoader} (id = filename minus .json); consumed by worker
 * goals and {@code CitizenCombatGoal}. {@code order} is the "higher tier wins" lever -
 * settlements only upgrade their current age when a newly granted age has a higher order.
 * {@code tools} maps open-ended role strings ("axe", "shovel", "hoe", "sword", ...) to the item
 * a worker equips for that role; modders add new roles just by shipping ages that include them,
 * no schema change needed. The OptionalInt tick fields override each goal's bare-handed wind-up
 * default when present: chopTicks (ForesterWorkGoal, default 30), mineTicks (DiggerWorkGoal,
 * default 80; JSON key "mine_speed", semantically ticks-per-block so lower = faster) and
 * harvestTicks (FarmerWorkGoal till/plant/harvest, default 70; JSON key "harvest_speed").
 * weaponDamage (JSON "weapon_damage", default 4.0 = wood-sword baseline) is half-hearts per
 * swing with the age's sword; weaponAttackSpeed (JSON "weapon_attack_speed", default 1.6) is
 * attacks per second - combat cooldown is 20 / value ticks per swing (1.6 -> ~12 ticks).
 */
public record ToolAge(String id, Component displayName, int order, OptionalInt chopTicks,
                      OptionalInt mineTicks, OptionalInt harvestTicks,
                      double weaponDamage, double weaponAttackSpeed,
                      Map<String, Item> tools) {
}
