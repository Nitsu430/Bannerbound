package com.bannerbound.antiquity.combat;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.bannerbound.antiquity.event.PoisonEvents;

/**
 * The blunt-weapon crit stun -- the shared effect every blunt weapon (bone club, smithing hammers;
 * everything in {@code #bannerboundantiquity:blunt_weapons}) lands on a critical hit. The struck
 * victim is staggered for {@link #STUN_TICKS} (1s): movement is cut to half base speed via a
 * transient {@code MOVEMENT_SPEED} modifier (the {@link com.bannerbound.antiquity.poison.Poisons}
 * paralysis pattern), and a struck player's vision blurs in and out client-side, driven off the
 * synced {@link BannerboundAntiquity#STUN_UNTIL} deadline by {@code StatusClientEffects}.
 * {@code isStunned} reads that synced deadline so it works on both sides; everything else is
 * server-side ({@code stun} no-ops on the client). {@code PoisonEvents} arms {@code stun} on
 * CriticalHitEvent and calls {@code tick} per tick while the deadline is set; once it passes,
 * the modifier is removed and the deadline zeroed so the victim recovers fully. Re-stunning just
 * re-bases the 1s window. {@code stun} also drops a PathfinderMob's current path and cancels a
 * player's sprint so the stagger reads as a hitch rather than a slow crawl.
 */
public final class BluntStun {
    private BluntStun() {}

    public static final int STUN_TICKS = 20;

    private static final double STUN_SLOW = -0.5;

    private static final ResourceLocation STUN_SPEED_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "blunt_stun_speed");

    public static boolean isStunned(LivingEntity victim, long now) {
        return now < victim.getData(BannerboundAntiquity.STUN_UNTIL.get());
    }

    public static void stun(LivingEntity victim) {
        if (victim.level().isClientSide) {
            return;
        }
        long until = victim.level().getGameTime() + STUN_TICKS;
        victim.setData(BannerboundAntiquity.STUN_UNTIL.get(), until);
        applySlow(victim);
        if (victim instanceof PathfinderMob mob) {
            mob.getNavigation().stop();
        }
        if (victim instanceof ServerPlayer player) {
            player.setSprinting(false);
        }
    }

    public static void tick(LivingEntity victim) {
        long until = victim.getData(BannerboundAntiquity.STUN_UNTIL.get());
        if (until <= 0L) {
            return;
        }
        if (victim.level().getGameTime() >= until) {
            victim.setData(BannerboundAntiquity.STUN_UNTIL.get(), 0L);
            clearSlow(victim);
        } else {
            applySlow(victim); // not redundant: re-pins the modifier if the attribute map was rebuilt mid-stun
            if (victim instanceof ServerPlayer player) {
                player.setSprinting(false);
            }
        }
    }

    private static void applySlow(LivingEntity victim) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(STUN_SPEED_ID) == null) {
            speed.addTransientModifier(new AttributeModifier(STUN_SPEED_ID, STUN_SLOW,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static void clearSlow(LivingEntity victim) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(STUN_SPEED_ID);
        }
    }
}
