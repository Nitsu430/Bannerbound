package com.bannerbound.antiquity.entity;

import java.util.EnumSet;
import java.util.List;

import com.bannerbound.antiquity.Config;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.HunterWorkGoal;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * The NPC-hunter twin of {@link FleeFromPlayerGoal}: wild prey fears a working Hunter citizen the
 * way it fears a player. Only hunters actively ON THE JOB (isWorking + hunter job type) are threats;
 * citizens strolling past on other business never scatter the wildlife, and fed/domesticated animals
 * behave like vanilla livestock and never flee. The hunter's crouch-stalk uses the same "noise" rule
 * as a sneaking player (detection range drops to {@code FLEE_RANGE_SNEAK}), which is exactly what
 * makes the stealth approach work: stand up too close and the animal bolts and the hunt becomes a
 * chase. On the not-scared -> scared edge it alarms the herd once, so spooking one animal scatters
 * them all; fear is refreshed each tick while chased. Candidate flee points are rejected when they
 * would run toward the hunter. Prey sprints when the hunter is close and walks otherwise, slowed
 * while tired/bleeding. Same priority slot as the other flee goals.
 */
public class FleeFromHunterGoal extends Goal {
    private final PathfinderMob mob;
    private final double walkSpeed;
    private final double sprintSpeed;
    private CitizenEntity threat;
    private Vec3 fleeTo;

    public FleeFromHunterGoal(PathfinderMob mob, double walkSpeed, double sprintSpeed) {
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
        CitizenEntity t = findThreat();
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
        CitizenEntity t = this.threat != null && isThreat(this.threat) ? this.threat : findThreat();
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

    private double speedFor(CitizenEntity t) {
        double base = mob.distanceToSqr(t) < 49.0 ? sprintSpeed : walkSpeed;
        if (HuntingFear.isTired(mob)) {
            base *= Config.TIRED_SPEED_MULT.get();
        }
        if (HuntingFear.isBleeding(mob)) {
            base *= Config.BLEED_SPEED_MULT.get();
        }
        return base;
    }

    private CitizenEntity findThreat() {
        double maxRange = Config.FLEE_RANGE.get();
        List<CitizenEntity> hunters = mob.level().getEntitiesOfClass(CitizenEntity.class,
            mob.getBoundingBox().inflate(maxRange), FleeFromHunterGoal::isThreat);
        CitizenEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (CitizenEntity c : hunters) {
            double range = c.isCrouching() ? Config.FLEE_RANGE_SNEAK.get() : Config.FLEE_RANGE.get();
            double d = mob.distanceToSqr(c);
            if (d > range * range) {
                continue;
            }
            if (Config.REQUIRE_LINE_OF_SIGHT.get() && !mob.getSensing().hasLineOfSight(c)) {
                continue;
            }
            if (d < best) {
                best = d;
                nearest = c;
            }
        }
        return nearest;
    }

    private static boolean isThreat(CitizenEntity c) {
        return c.isAlive() && c.isWorking() && HunterWorkGoal.JOB_TYPE_ID.equals(c.getJobType());
    }
}
