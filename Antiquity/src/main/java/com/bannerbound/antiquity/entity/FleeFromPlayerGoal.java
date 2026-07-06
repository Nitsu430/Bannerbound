package com.bannerbound.antiquity.entity;

import java.util.EnumSet;
import java.util.List;

import com.bannerbound.antiquity.Config;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A prey animal flees the player: it runs when a player is within range (24 blocks, or 12 if the
 * player sneaks, x1.5 if sprinting -- the "noise" rule) AND has line of sight AND is not holding the
 * animal's favourite food (the lure cancel: wheat for cows, seeds for chickens, etc.).
 * Fed/domesticated animals behave like vanilla livestock and never flee. On the not-scared -> scared
 * edge it alarms the herd once and, for pigs, elects a boar-charger. Fear is refreshed each tick
 * while chased, and the goal keeps running while still moving or while spooked with a visible threat
 * (tick repaths), so a chased herd stays spooked; it releases (herd-flee/wander resume) once calm
 * with no threat. Candidate flee points are rejected when they would run toward the player. Prey
 * sprints when the player is close and walks otherwise, slowed while tired/bleeding; cows use small
 * speed multipliers (catchable) while everything else outruns the player. Deliberately a plain
 * {@link Goal} (not {@code AvoidEntityGoal}) for full control over the dynamic range, sneak/LoS/food
 * gating, stamina, and bleed slow, while using the vanilla {@link DefaultRandomPos} away-path the
 * way AvoidEntityGoal does.
 */
public class FleeFromPlayerGoal extends Goal {
    private final PathfinderMob mob;
    private final double walkSpeed;
    private final double sprintSpeed;
    private Player threat;
    private Vec3 fleeTo;

    public FleeFromPlayerGoal(PathfinderMob mob, double walkSpeed, double sprintSpeed) {
        this.mob = mob;
        this.walkSpeed = walkSpeed;
        this.sprintSpeed = sprintSpeed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!Config.HUNTING_ENABLED.get()) {
            return false;
        }
        if (HuntingFear.isTamed(mob)) {
            return false;
        }
        if (mob.isBaby() && !Config.BABIES_FLEE.get()) {
            return false;
        }
        Player t = findThreat();
        if (t == null) {
            return false;
        }
        Vec3 away = DefaultRandomPos.getPosAway(mob, 16, 7, t.position());
        if (away == null || t.distanceToSqr(away) < t.distanceToSqr(mob)) {
            return false;
        }
        this.threat = t;
        this.fleeTo = away;
        if (!HuntingFear.isScared(mob)) {
            HuntingFear.alarmHerd(mob, Config.HERD_ALARM_RADIUS.get(), Config.SCARED_DURATION_TICKS.get());
            if (mob instanceof Pig pig) {
                HuntingFear.electBoarCharger(pig, Config.HERD_ALARM_RADIUS.get(),
                    Config.BOAR_CHARGE_CLAIM_TICKS.get(), Config.BOAR_CHARGE_CHANCE.get(), mob.getRandom());
            }
        }
        HuntingFear.scare(mob, Config.SCARED_DURATION_TICKS.get());
        return true;
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(fleeTo.x, fleeTo.y, fleeTo.z, walkSpeed);
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.getNavigation().isDone() || (HuntingFear.isScared(mob) && findThreat() != null);
    }

    @Override
    public void tick() {
        Player t = this.threat != null ? this.threat : findThreat();
        if (t != null) {
            mob.getNavigation().setSpeedModifier(speedFor(t));
            HuntingFear.scare(mob, Config.SCARED_DURATION_TICKS.get());
        }
        if (mob.getNavigation().isDone() && t != null) {
            Vec3 away = DefaultRandomPos.getPosAway(mob, 16, 7, t.position());
            if (away != null && t.distanceToSqr(away) >= t.distanceToSqr(mob)) {
                this.threat = t;
                mob.getNavigation().moveTo(away.x, away.y, away.z, walkSpeed);
            }
        }
    }

    @Override
    public void stop() {
        this.threat = null;
        this.fleeTo = null;
        mob.getNavigation().stop();
    }

    private double speedFor(Player t) {
        double base = mob.distanceToSqr(t) < 49.0 ? sprintSpeed : walkSpeed;
        if (HuntingFear.isTired(mob)) {
            base *= Config.TIRED_SPEED_MULT.get();
        }
        if (HuntingFear.isBleeding(mob)) {
            base *= Config.BLEED_SPEED_MULT.get();
        }
        return base;
    }

    private Player findThreat() {
        double maxRange = Config.FLEE_RANGE.get() * Config.RANGE_SPRINT_MULT.get();
        List<Player> players = mob.level().getEntitiesOfClass(Player.class,
            mob.getBoundingBox().inflate(maxRange),
            p -> p.isAlive() && !p.isCreative() && !p.isSpectator());
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            double range = effectiveRange(p);
            double d = mob.distanceToSqr(p);
            if (d > range * range) {
                continue;
            }
            if (Config.REQUIRE_LINE_OF_SIGHT.get() && !mob.getSensing().hasLineOfSight(p)) {
                continue;
            }
            if (isLured(p)) {
                continue;
            }
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private double effectiveRange(Player p) {
        double r = p.isCrouching() ? Config.FLEE_RANGE_SNEAK.get() : Config.FLEE_RANGE.get();
        if (p.isSprinting()) {
            r *= Config.RANGE_SPRINT_MULT.get();
        }
        return r;
    }

    private boolean isLured(Player p) {
        if (!Config.LURE_FOOD_CANCELS.get() || !(mob instanceof Animal animal)) {
            return false;
        }
        return animal.isFood(p.getMainHandItem()) || animal.isFood(p.getOffhandItem());
    }
}
