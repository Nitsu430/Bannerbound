package com.bannerbound.antiquity.entity;

import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import javax.annotation.Nullable;

/**
 * Shared server-side state for immersive hunting - the spine that the flee/herd/charge goals and the
 * combat event handlers all read and write. Backed by the transient data attachments registered on
 * {@link BannerboundAntiquity} (SCARED_UNTIL, BOAR_CHARGE_CLAIM, HUNT_STAMINA, BLEED_TICKS, BLEED_BY)
 * plus the persistent TAMED_LIVESTOCK attachment.
 *
 * <p>Tamed/domesticated animals behave like vanilla livestock: no flee, no footprints, no hunting
 * fear, and they stay calm even when their herd panics. "Tamed" is any of: fed its favourite food
 * (TAMED_LIVESTOCK, persists across save/reload), stamped with the shared persistent-data flag
 * {@link #DOMESTICATED_TAG} (Core's Herder domesticates the animals it corrals/raises but cannot
 * reference an Antiquity attachment, so it writes this NBT flag which we honour here), or
 * vanilla-tamed (TamableAnimal pet / tamed AbstractHorse mount).
 *
 * <p>Fear propagation: when an animal first becomes scared (sees the player, or is hurt) the caller
 * fires {@link #alarmHerd} exactly once, on the not-scared->scared edge, spooking nearby same-species
 * animals without re-cascading. {@link #electBoarCharger} runs on that same first-scared edge for
 * pigs: it rolls the charge chance and claims the charge only if no nearby pig already holds a live
 * claim, so at most one boar charges while the rest flee. Stamina (persistence hunting) is clamped to
 * [0, Config.STAMINA_MAX]; a negative stored value is the never-set sentinel meaning full stamina.
 */
public final class HuntingFear {
    private HuntingFear() {}

    public static boolean isTamed(LivingEntity mob) {
        if (mob.getData(BannerboundAntiquity.TAMED_LIVESTOCK.get())) {
            return true;
        }
        if (mob.getPersistentData().getBoolean(DOMESTICATED_TAG)) {
            return true;
        }
        if (mob instanceof TamableAnimal t && t.isTame()) {
            return true;
        }
        return mob instanceof AbstractHorse h && h.isTamed();
    }

    public static final String DOMESTICATED_TAG = "BannerboundDomesticated";

    public static void setTamed(LivingEntity mob) {
        mob.setData(BannerboundAntiquity.TAMED_LIVESTOCK.get(), true);
    }

    public static boolean isScared(LivingEntity mob) {
        return mob.getData(BannerboundAntiquity.SCARED_UNTIL.get()) >= mob.level().getGameTime();
    }

    public static void scare(LivingEntity mob, int ticks) {
        long until = mob.level().getGameTime() + ticks;
        if (mob.getData(BannerboundAntiquity.SCARED_UNTIL.get()) < until) {
            mob.setData(BannerboundAntiquity.SCARED_UNTIL.get(), until);
        }
    }

    public static void alarmHerd(Mob source, int radius, int ticks) {
        if (radius <= 0) {
            return;
        }
        long until = source.level().getGameTime() + ticks;
        List<? extends Mob> herd = source.level().getEntitiesOfClass(source.getClass(),
            source.getBoundingBox().inflate(radius), o -> o != source && o.isAlive());
        for (Mob mate : herd) {
            if (isTamed(mate)) {
                continue;
            }
            if (mate.getData(BannerboundAntiquity.SCARED_UNTIL.get()) < until) {
                mate.setData(BannerboundAntiquity.SCARED_UNTIL.get(), until);
            }
        }
    }

    public static void electBoarCharger(Pig source, int radius, int claimTicks, double chance, RandomSource rng) {
        if (rng.nextDouble() >= chance) {
            return;
        }
        long now = source.level().getGameTime();
        List<Pig> pigs = source.level().getEntitiesOfClass(Pig.class,
            source.getBoundingBox().inflate(radius), p -> p != source && p.isAlive());
        for (Pig other : pigs) {
            if (other.getData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get()) >= now) {
                return;
            }
        }
        source.setData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get(), now + claimTicks);
    }

    public static boolean hasChargeClaim(LivingEntity mob) {
        return mob.getData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get()) >= mob.level().getGameTime();
    }

    public static void clearChargeClaim(LivingEntity mob) {
        mob.setData(BannerboundAntiquity.BOAR_CHARGE_CLAIM.get(), 0L);
    }

    public static float getStamina(LivingEntity mob) {
        float s = mob.getData(BannerboundAntiquity.HUNT_STAMINA.get());
        return s < 0.0F ? (float) (double) Config.STAMINA_MAX.get() : s;
    }

    public static void setStamina(LivingEntity mob, float value) {
        float max = (float) (double) Config.STAMINA_MAX.get();
        mob.setData(BannerboundAntiquity.HUNT_STAMINA.get(), Math.max(0.0F, Math.min(max, value)));
    }

    public static boolean isTired(LivingEntity mob) {
        return getStamina(mob) <= (float) (double) Config.STAMINA_TIRED_THRESHOLD.get();
    }

    public static boolean isBleeding(LivingEntity mob) {
        return mob.getData(BannerboundAntiquity.BLEED_TICKS.get()) > 0;
    }

    public static void applyBleed(LivingEntity mob, int ticks) {
        int current = mob.getData(BannerboundAntiquity.BLEED_TICKS.get());
        mob.setData(BannerboundAntiquity.BLEED_TICKS.get(), Math.max(current, ticks));
    }

    public static void applyBleed(LivingEntity mob, int ticks, @Nullable Entity causedBy) {
        applyBleed(mob, ticks);

        if (causedBy != null) {
            mob.setData(BannerboundAntiquity.BLEED_BY.get(), causedBy.getStringUUID());
        } else {
            // Unattributed re-wound must CLEAR BLEED_BY, or the refreshed bleed keeps crediting the previous attacker.
            mob.removeData(BannerboundAntiquity.BLEED_BY.get());
        }
    }
}
