package com.bannerbound.antiquity.entity;

import java.util.function.Predicate;

import com.bannerbound.antiquity.Config;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Makes a predator target the player. A thin {@link NearestAttackableTargetGoal} subclass so it can
 * be instanceof-matched for dedup (the way the repo dedups on its own goal classes); the mob's
 * existing melee goal (wolf MeleeAttackGoal, ocelot OcelotAttackGoal) does the actual attacking once
 * the target is set. Inert unless Config.HUNTING_ENABLED. Never targets creative/spectator players,
 * a player holding a bone when the mob is a wolf (a held bone reads as taming intent and calms it,
 * even though a bone is not wolf "food"), or - when Config.PREDATORS_PACIFIED_BY_FOOD - a player
 * holding the predator's food in either hand, so predators can still be pacified/lured like prey.
 */
public class PlayerHostilityTargetGoal extends NearestAttackableTargetGoal<Player> {
    public PlayerHostilityTargetGoal(Mob mob) {
        super(mob, Player.class, 10, true, false,
            (Predicate<LivingEntity>) le -> isHuntablePlayer(mob, le));
    }

    private static boolean isHuntablePlayer(Mob mob, LivingEntity le) {
        if (!Config.HUNTING_ENABLED.get()) {
            return false;
        }
        if (!(le instanceof Player player) || player.isCreative() || player.isSpectator()) {
            return false;
        }
        if (mob instanceof Wolf
                && (player.getMainHandItem().is(Items.BONE) || player.getOffhandItem().is(Items.BONE))) {
            return false;
        }
        if (Config.PREDATORS_PACIFIED_BY_FOOD.get() && mob instanceof Animal animal
                && (animal.isFood(player.getMainHandItem()) || animal.isFood(player.getOffhandItem()))) {
            return false;
        }
        return true;
    }
}
