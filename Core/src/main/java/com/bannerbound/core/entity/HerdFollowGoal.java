package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;

/**
 * Makes a herded animal FOLLOW the herder that claims it, exactly like vanilla TemptGoal makes an
 * animal follow a player holding its food -- using the animal's OWN vanilla navigation. This is the
 * whole design: we do not overwrite or replace the animal's pathfinding (every attempt to do that
 * failed), we just make the animal WANT to walk to the herder and let its own nav do the work -- a
 * wheat-tempted cow walks straight through an open rope gate into a pen, and the herder is simply a
 * stand-in for the real player vanilla TemptGoal only targets. Lives in the animal's goalSelector
 * (added on claim) with MOVE+LOOK so it beats the wander goals. Resolves the herder each
 * canUse/canContinueToUse from the HERDED_BY data attachment and yields the instant the animal isn't
 * claimed, so a released or penned animal behaves normally; stop() also clears a dangling HERDED_BY
 * when the herder has vanished so the animal can't linger claimed with no herder.
 */
@ApiStatus.Internal
public class HerdFollowGoal extends Goal {
    private static final double STOP_DIST_SQ = 2.5 * 2.5;
    private static final double SPEED = 1.15;
    private static final int REPATH_INTERVAL = 5;

    private final Animal animal;
    @Nullable private CitizenEntity herder;
    private int repath;

    public HerdFollowGoal(Animal animal) {
        this.animal = animal;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private CitizenEntity resolveHerder() {
        if (!(animal.level() instanceof ServerLevel sl)) return null;
        Integer id = animal.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
        if (id == null || id == 0) return null;
        return sl.getEntity(id) instanceof CitizenEntity c && c.isAlive() ? c : null;
    }

    @Override
    public boolean canUse() {
        this.herder = resolveHerder();
        return herder != null;
    }

    @Override
    public boolean canContinueToUse() {
        this.herder = resolveHerder();
        return herder != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        if (herder == null) animal.removeData(BannerboundCore.HERDED_BY.get());
        herder = null;
        animal.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (herder == null) return;
        animal.getLookControl().setLookAt(herder, 30.0F, (float) animal.getMaxHeadXRot());
        if (animal.distanceToSqr(herder) <= STOP_DIST_SQ) {
            animal.getNavigation().stop();
            return;
        }
        if (--repath > 0) return;
        repath = REPATH_INTERVAL;
        animal.getNavigation().moveTo(herder, SPEED);
    }
}
