package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Holds the floor at priority 0 while a retaliation swing is pending, then lands the swing. Its
 * priority number sits BELOW PanicGoal (priority 1), so when CitizenBrawlEvents schedules a
 * counter-swing the citizen stops fleeing from pain and faces the attacker. Without this goal vanilla
 * PanicGoal would carry the citizen away before the aiStep swing-handler could fire and the brawl loop
 * would silently break at every step.
 *
 * Flow: CitizenBrawlEvents sets schedulePendingRetaliation on the victim (target UUID + scheduled tick
 * ~10 ticks out); canUse sees the pending state and starts, claiming MOVE+LOOK at priority 0 to
 * preempt PanicGoal; start stops navigation so the citizen plants their feet; tick stares the target
 * down until the scheduled tick, then swings via performBrawlSwing, notes the exchange (so the next
 * hit is treated as ongoing) and clears the pending state, which ends the goal. Target may be any
 * LivingEntity (citizen or player) since player-on-citizen attacks trigger the same retaliation rolls.
 */
@ApiStatus.Internal
public class BrawlRetaliationGoal extends Goal {
    private final CitizenEntity citizen;
    @Nullable private LivingEntity target;

    public BrawlRetaliationGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        UUID id = citizen.getPendingRetaliationTargetId();
        if (id == null) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Entity e = sl.getEntity(id);
        if (!(e instanceof LivingEntity le) || !le.isAlive()) {
            citizen.clearPendingRetaliation();
            return false;
        }
        this.target = le;
        return true;
    }

    @Override
    public void start() {
        citizen.getNavigation().stop();
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive()) return false;
        return citizen.getPendingRetaliationTargetId() != null;
    }

    @Override
    public void tick() {
        if (target == null) return;
        citizen.getLookControl().setLookAt(target, 30.0f, 30.0f);
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        if (sl.getGameTime() >= citizen.getPendingRetaliationTick()) {
            if (citizen.performBrawlSwing(target)) {
                citizen.noteBrawlExchange(target.getUUID(), sl.getGameTime());
            }
            citizen.clearPendingRetaliation();
        }
    }

    @Override
    public void stop() {
        target = null;
        // Never clear pendingRetaliation here: stop() also runs on preemption, and clearing would drop a still-pending retaliation.
    }
}
