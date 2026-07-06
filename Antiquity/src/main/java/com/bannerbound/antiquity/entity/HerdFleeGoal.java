package com.bannerbound.antiquity.entity;

import java.util.EnumSet;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The herd half of fear propagation: an animal that has been alarmed (its SCARED_UNTIL stamped by a
 * fleeing or hurt herd-mate via {@link HuntingFear#alarmHerd}) but has NO line of sight to the player
 * bolts anyway - away from the nearest player within 32 blocks if one is around (even without LoS),
 * otherwise to a random land spot - so the whole herd scatters when one member panics, not just the
 * animals that can see you. Registered at the same priority as {@link FleeFromPlayerGoal}; that goal
 * is registered first so it wins MOVE when a player is actually visible, leaving this one to cover
 * the spooked-but-blind herd-mates. Tamed/domesticated animals never herd-flee.
 */
public class HerdFleeGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private Vec3 fleeTo;

    public HerdFleeGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (HuntingFear.isTamed(mob) || !HuntingFear.isScared(mob)) {
            return false;
        }
        Player p = mob.level().getNearestPlayer(mob, 32.0);
        Vec3 away = p != null
            ? DefaultRandomPos.getPosAway(mob, 16, 7, p.position())
            : LandRandomPos.getPos(mob, 16, 7);
        if (away == null) {
            return false;
        }
        this.fleeTo = away;
        return true;
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(fleeTo.x, fleeTo.y, fleeTo.z, speed);
    }

    @Override
    public boolean canContinueToUse() {
        return HuntingFear.isScared(mob) && !mob.getNavigation().isDone();
    }

    @Override
    public void stop() {
        this.fleeTo = null;
        mob.getNavigation().stop();
    }
}
