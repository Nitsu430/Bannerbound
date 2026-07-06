package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;
import com.bannerbound.antiquity.item.SpearItem;
import com.bannerbound.core.api.hunter.HunterHooks;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Antiquity's {@link HunterHooks.Extension}: plugs the immersive-hunting layer into Core's Hunter
 * job. Installed once at common setup, mirroring AntiquityHerderHooks. Fed-favourite-food livestock
 * is never prey ({@link HuntingFear#isTamed}); calm prey makes the hunter crouch-stalk (wild animals
 * fear hunter citizens the way they fear players, see {@link FleeFromHunterGoal}); spooked prey
 * turns the stalk into a chase; a spear job tool opens each engagement with a throw (bleed + slow)
 * before the melee kill. Spear and sling shots lead-aim ahead of a moving target from the
 * projectile's spawn point and add ARC_LIFT_PER_BLOCK of lift per block of distance so the short
 * arc lands on prey rather than pelting the dirt; SPEAR_SPEED matches the spear fisher's throw and
 * SLING_SPEED sits near the player slingshot's full-draw speed (NPC-steady inaccuracy). The
 * primitive bow shoots slower than vanilla, scaled further by its fletched craftsmanship quality
 * (slower arrow -> softer hit), so quality acts on effectiveness with no durability cost while the
 * hunter's shot obeys exactly the player's physics (PrimitiveBowItem); conjured arrows are STANDARD
 * quality so infinite ammo never outclasses hand-fletched batches. Guard sling rocks are likewise
 * conjured and never touch the settlement's real rock stock (like barbarian archer arrows, the
 * WEAPON is the economy item, not each pebble); they discard on impact with no stun.
 */
public final class AntiquityHunterHooks implements HunterHooks.Extension {
    private static final double SPEAR_SPEED = 1.6;
    private static final double THROW_INACCURACY = 1.0;
    private static final double SLING_SPEED = 2.0;
    private static final double SLING_INACCURACY = 3.0;
    private static final double ARC_LIFT_PER_BLOCK = 0.08;

    @Override
    public boolean isDomesticated(Mob animal) {
        return HuntingFear.isTamed(animal);
    }

    @Override
    public boolean isPreyScared(Mob animal) {
        return Config.HUNTING_ENABLED.get() && HuntingFear.isScared(animal);
    }

    @Override
    public boolean wantsStealth(CitizenEntity hunter, Mob target) {
        return Config.HUNTING_ENABLED.get()
            && !HuntingFear.isTamed(target)
            && !HuntingFear.isScared(target);
    }

    @Override
    public boolean isThrowableSpear(ItemStack stack) {
        return stack.getItem() instanceof SpearItem;
    }

    @Override
    public float bowVelocityFactor(ItemStack bow) {
        if (bow.getItem() instanceof com.bannerbound.antiquity.item.PrimitiveBowItem) {
            return com.bannerbound.antiquity.item.PrimitiveBowItem.BASE_VELOCITY_FACTOR
                * com.bannerbound.core.api.quality.QualityTier.of(bow).statMultiplier();
        }
        return 1.0F;
    }

    @Override
    public net.minecraft.world.entity.projectile.AbstractArrow createArrow(
            CitizenEntity hunter, ItemStack bow) {
        if (!(bow.getItem() instanceof com.bannerbound.antiquity.item.PrimitiveBowItem)) {
            return null; // null -> caller falls back to a vanilla arrow
        }
        return new CompositeArrowEntity(hunter.level(), hunter,
            new ItemStack(BannerboundAntiquity.ARROW.get()), bow.copy());
    }

    @Override
    public boolean shootSling(CitizenEntity shooter,
            net.minecraft.world.entity.LivingEntity target, ItemStack sling, double damage) {
        if (!(shooter.level() instanceof ServerLevel sl)) {
            return false;
        }
        ThrownRock rock = new ThrownRock(sl, shooter);
        rock.setItem(new ItemStack(BannerboundAntiquity.STONE_ROCK_ITEM.get()));
        rock.setImpactDamage((float) damage);
        rock.setStun(false);
        Vec3 start = rock.position();
        double dist = Math.sqrt(target.distanceToSqr(shooter));
        double leadTicks = dist / SLING_SPEED;
        Vec3 aim = target.position()
            .add(0.0, target.getBbHeight() * 0.5 + dist * ARC_LIFT_PER_BLOCK, 0.0)
            .add(target.getDeltaMovement().scale(leadTicks));
        Vec3 dir = aim.subtract(start);
        rock.shoot(dir.x, dir.y, dir.z, (float) SLING_SPEED, (float) SLING_INACCURACY);
        sl.addFreshEntity(rock);
        sl.playSound(null, shooter.blockPosition(),
            BannerboundAntiquity.SLING_SHOT.get(), SoundSource.NEUTRAL, 0.9F, 1.1F);
        return true;
    }

    @Override
    public boolean throwSpear(CitizenEntity hunter, Mob target, ItemStack spear, double damage) {
        if (!(hunter.level() instanceof ServerLevel sl)) {
            return false;
        }
        SpearProjectile s = new SpearProjectile(sl, hunter, spear.copy(), damage);
        Vec3 start = s.position();
        double dist = Math.sqrt(target.distanceToSqr(hunter));
        double leadTicks = dist / SPEAR_SPEED;
        Vec3 aim = target.position()
            .add(0.0, target.getBbHeight() * 0.5 + dist * ARC_LIFT_PER_BLOCK, 0.0)
            .add(target.getDeltaMovement().scale(leadTicks));
        Vec3 dir = aim.subtract(start);
        s.shoot(dir.x, dir.y, dir.z, (float) SPEAR_SPEED, (float) THROW_INACCURACY);
        s.markNoRecovery(); // thrown stack is a COPY of the equipped spear; no-recovery blocks item dup
        sl.addFreshEntity(s);
        sl.playSound(null, hunter.blockPosition(),
            BannerboundAntiquity.SPEAR_THROW_SOUND.get(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        return true;
    }
}
