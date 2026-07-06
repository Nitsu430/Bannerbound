package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * The citizen idle/ambient-movement goal: picks a walk target and strolls there whenever no work,
 * sleep, or panic goal is active, then rests before the next stroll. What it picks depends on
 * context.
 *
 * <p>Outpost workers live ON SITE. When the citizen has a live outpost work site (still a working
 * claim of its settlement), idle strolls anchor on the outpost, never the settlement, so an
 * assigned miner spends its whole rotation out there instead of commuting home between shifts --
 * but only once it is within OUTPOST_IDLE_RADIUS (24) of the site: beyond that the long walk
 * belongs to {@link OutpostCommuteGoal} (priority 2), so this goal returns false rather than issue
 * a doomed cross-map target or wander the worker back toward town. The on-site loiter is tight
 * (+/-3, not the +/-8 town stroll) so a worker waiting out a vein's regen (up to ~8000 idle ticks)
 * reads as standing at its post by the rock, not as having abandoned the job. OUTPOST_IDLE_RADIUS
 * must comfortably exceed the commute's hand-off distance so there is no dead zone between them.
 *
 * <p>Otherwise the pick order depends on government: anarchy (NONE) wanders the territory first and
 * only falls back to the town hall, so citizens roam and swing back to the campfire incidentally
 * (mostly when AnarchyWorkGoal delivers loot); organised settlements (Council/Chiefdom) loiter near
 * the campfire/home first for the "village square" feel and fall back to wandering. Anarchy wander
 * steps are ANARCHY_WANDER_RADIUS (~16, a chunk-wide step) with a soft leash: a pick past
 * ANARCHY_LEASH_RADIUS from the town hall biases the next target HALF the way home so citizens
 * trend back without an unnatural snap. The campfire loiter radius GROWS with population
 * (REST_RADIUS base, capped at MAX_SPREAD_RADIUS) so a Hearth stays a cozy cluster while a big
 * Village fans out into an ambient town. Pregnant women loiter around their home instead of the
 * campfire (an explicit player request matching the nesting intuition), falling back to the town
 * hall when homeless.
 *
 * <p>Activation-tier aware: with no player nearby the citizen just idles (stays a real loaded
 * entity, resumes the moment a player approaches). A new patrol segment starts only on the
 * citizen's think tick so the fleet's A* searches spread across ticks instead of spiking.
 */
@ApiStatus.Internal
public class SettlementPatrolGoal extends Goal {
    private static final int TARGET_TRIES = 16;
    private static final int IDLE_TICKS_BETWEEN_PATROLS = 60;
    private static final double OUTPOST_IDLE_RADIUS = 24.0;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private double targetX, targetY, targetZ;
    private int cooldown;

    public SettlementPatrolGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!citizen.isAiActive()) return false;
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (!citizen.isThinkTick()) return false;
        Settlement s = citizen.getSettlement();
        Vec3 outpost = outpostAnchorVec(s);
        if (outpost != null) {
            if (citizen.distanceToSqr(outpost.x, outpost.y, outpost.z)
                    > OUTPOST_IDLE_RADIUS * OUTPOST_IDLE_RADIUS) {
                return false;
            }
            RandomSource orng = citizen.getRandom();
            this.targetX = outpost.x + orng.nextDouble() * 6 - 3;
            this.targetY = outpost.y;
            this.targetZ = outpost.z + orng.nextDouble() * 6 - 3;
            return true;
        }
        boolean anarchy = s != null
            && s.governmentType() == Settlement.Government.NONE;
        Vec3 target;
        if (anarchy) {
            target = pickWanderTarget();
            if (target == null) target = pickTownHallFallback();
        } else {
            target = pickTownHallFallback();
            if (target == null) target = pickInTerritoryTarget();
        }
        if (target == null) {
            return false;
        }
        this.targetX = target.x;
        this.targetY = target.y;
        this.targetZ = target.z;
        return true;
    }

    @Nullable
    private Vec3 pickWanderTarget() {
        RandomSource rng = citizen.getRandom();
        Vec3 candidate = DefaultRandomPos.getPos(citizen, ANARCHY_WANDER_RADIUS, 4);
        if (candidate != null) {
            Vec3 home = townHallVec();
            if (home != null) {
                double distSq = candidate.distanceToSqr(home);
                if (distSq > ANARCHY_LEASH_RADIUS * ANARCHY_LEASH_RADIUS) {
                    double mx = (home.x - citizen.getX()) * 0.5 + rng.nextDouble() * 6 - 3;
                    double mz = (home.z - citizen.getZ()) * 0.5 + rng.nextDouble() * 6 - 3;
                    return citizen.position().add(mx, 0, mz);
                }
            }
            return candidate;
        }
        return null;
    }

    private static final int ANARCHY_WANDER_RADIUS = 16;
    private static final double ANARCHY_LEASH_RADIUS = 32.0;

    @Override
    public void start() {
        PathNavigation nav = citizen.getNavigation();
        nav.moveTo(targetX, targetY, targetZ, speedModifier);
    }

    @Override
    public boolean canContinueToUse() {
        return !citizen.getNavigation().isDone();
    }

    @Override
    public void stop() {
        cooldown = IDLE_TICKS_BETWEEN_PATROLS + citizen.getRandom().nextInt(80);
    }

    @Nullable
    private Vec3 pickInTerritoryTarget() {
        Settlement s = citizen.getSettlement();
        RandomSource rng = citizen.getRandom();
        for (int i = 0; i < TARGET_TRIES; i++) {
            Vec3 candidate = DefaultRandomPos.getPos(citizen, 10, 4);
            if (candidate == null) continue;
            if (s == null) {
                return candidate;
            }
            long packed = new ChunkPos(BlockPos.containing(candidate)).toLong();
            if (s.claimedChunks().contains(packed)) {
                return candidate;
            }
            if (i > TARGET_TRIES / 2) {
                Vec3 home = townHallVec();
                if (home != null) {
                    double mx = (home.x - citizen.getX()) * 0.5 + rng.nextDouble() * 4 - 2;
                    double mz = (home.z - citizen.getZ()) * 0.5 + rng.nextDouble() * 4 - 2;
                    return citizen.position().add(mx, 0, mz);
                }
            }
        }
        return null;
    }

    private static final double REST_RADIUS = 7.0;
    private static final double MAX_SPREAD_RADIUS = 40.0;

    private double spreadRadius() {
        Settlement s = citizen.getSettlement();
        int pop = (s == null) ? 0 : s.population();
        return Math.min(MAX_SPREAD_RADIUS, REST_RADIUS + pop * 0.6);
    }

    @Nullable
    private Vec3 pickTownHallFallback() {
        if (citizen.isPregnant()) {
            Vec3 homeVec = homeVecForCitizen();
            if (homeVec != null) {
                RandomSource rngHome = citizen.getRandom();
                double dxh = rngHome.nextDouble() * (REST_RADIUS * 2) - REST_RADIUS;
                double dzh = rngHome.nextDouble() * (REST_RADIUS * 2) - REST_RADIUS;
                return new Vec3(homeVec.x + dxh, homeVec.y, homeVec.z + dzh);
            }
        }
        Vec3 v = townHallVec();
        if (v == null) return null;
        RandomSource rng = citizen.getRandom();
        double r = spreadRadius();
        double dx = rng.nextDouble() * (r * 2) - r;
        double dz = rng.nextDouble() * (r * 2) - r;
        return new Vec3(v.x + dx, v.y, v.z + dz);
    }

    @Nullable
    private Vec3 townHallVec() {
        Settlement s = citizen.getSettlement();
        if (s == null || s.townHallPos() == null) return null;
        BlockPos thp = s.townHallPos();
        return new Vec3(thp.getX() + 0.5, thp.getY(), thp.getZ() + 0.5);
    }

    @Nullable
    private Vec3 outpostAnchorVec(Settlement s) {
        BlockPos site = citizen.getOutpostSite();
        if (site == null || s == null) return null;
        if (!s.workingClaims().contains(new net.minecraft.world.level.ChunkPos(site).toLong())) {
            return null;
        }
        return new Vec3(site.getX() + 0.5, site.getY(), site.getZ() + 0.5);
    }

    @Nullable
    private Vec3 homeVecForCitizen() {
        Settlement s = citizen.getSettlement();
        if (s == null) return null;
        com.bannerbound.core.api.settlement.Home home = s.getHomeFor(citizen.getUUID());
        if (home == null) return null;
        BlockPos p = home.pos();
        return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }
}
