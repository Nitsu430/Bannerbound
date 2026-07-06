package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Reusable long-distance traveller for citizens: drives a worker toward a far target in short,
 * vanilla-pathable hops instead of one cross-map moveTo that truncates at the mob's FOLLOW_RANGE
 * search radius (64 blocks). Each hop is a ~20-block (HOP) straight step toward the target,
 * ground-snapped to loaded terrain, so a single A* search always succeeds and the route stays
 * roughly straight trip-to-trip (which also lets the stocker's trample-a-road pass reuse one road
 * instead of laying a fresh meandering strip every haul). HOP is deliberately well under the
 * 64-block FOLLOW_RANGE so vanilla nav never gives up mid-hop.
 *
 * <p>Stateful -- one instance per traveller, reused across legs. The caller calls stepToward each
 * tick while the target is far and takes over the precise final approach once it returns ARRIVED
 * (vanilla nav handles the last stretch inside its search radius, so we leave its navigation
 * untouched there and aren't fought). WAITING means the corridor ahead is unloaded -- idle and
 * retry; we never path or teleport into ungenerated space (groundAt returns null when a column's
 * chunk isn't loaded, gating every move).
 *
 * <p>Off-screen rescue: a stuck, unwatched traveller (no player within WATCH_RANGE 48) may
 * abstract-advance one short ABSTRACT_STEP (8 blocks, kept short so it still reads as travel if a
 * player loads in) onto loaded solid ground only, never water/void. Shared by OutpostCommuteGoal
 * (workers commuting to a remote site) and StockerWorkGoal (haulers supplying an outpost). No A*,
 * no chunk force-loading.
 */
@ApiStatus.Internal
public final class LongHaulWalker {
    public enum Status { WALKING, WAITING, ARRIVED }

    private static final int HOP = 20;
    private static final double HOP_REACHED_SQ = 4.0;
    private static final int STUCK_TICKS = 50;
    private static final double WATCH_RANGE = 48.0;
    private static final int ABSTRACT_STEP = 8;
    private static final int REPATH_COOLDOWN = 10;

    private BlockPos hop;
    private double bestHopDistSq = Double.MAX_VALUE;
    private int stuck;
    private int repathCooldown;

    public void reset(CitizenEntity c) {
        clear();
        if (c != null) c.getNavigation().stop();
    }

    private void clear() {
        hop = null;
        bestHopDistSq = Double.MAX_VALUE;
        stuck = 0;
        repathCooldown = 0;
    }

    public Status stepToward(CitizenEntity c, BlockPos target, double speed,
                             double handoffDist, boolean allowAbstract) {
        if (target == null || !(c.level() instanceof ServerLevel sl)) return Status.WAITING;
        if (horizDistSq(c, target) <= handoffDist * handoffDist) { clear(); return Status.ARRIVED; }

        if (hop == null) {
            hop = nextHop(sl, c, target, speed);
            if (hop == null) return Status.WAITING;
        }
        c.getLookControl().setLookAt(hop.getX() + 0.5, hop.getY() + 1.0, hop.getZ() + 0.5);

        double d = horizDistSq(c, hop);
        if (d <= HOP_REACHED_SQ) { hop = null; return Status.WALKING; }

        if (d + 0.05 < bestHopDistSq) {
            bestHopDistSq = d;
            stuck = 0;
        } else if (++stuck > STUCK_TICKS) {
            if (allowAbstract && !isWatched(c)) abstractAdvance(sl, c, target);
            hop = null;
            return Status.WALKING;
        }
        if (c.getNavigation().isDone() && --repathCooldown <= 0) {
            repathCooldown = REPATH_COOLDOWN;
            c.getNavigation().moveTo(hop.getX() + 0.5, hop.getY(), hop.getZ() + 0.5, speed);
        }
        return Status.WALKING;
    }

    private BlockPos nextHop(ServerLevel sl, CitizenEntity c, BlockPos target, double speed) {
        bestHopDistSq = Double.MAX_VALUE;
        stuck = 0;
        repathCooldown = 0;
        Vec3 from = c.position();
        double dx = (target.getX() + 0.5) - from.x;
        double dz = (target.getZ() + 0.5) - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        int tx;
        int tz;
        if (dist <= HOP) {
            tx = target.getX();
            tz = target.getZ();
        } else {
            tx = (int) Math.round(from.x + dx / dist * HOP);
            tz = (int) Math.round(from.z + dz / dist * HOP);
        }
        BlockPos g = groundAt(sl, tx, tz);
        if (g == null) return null;
        c.getNavigation().moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, speed);
        return g;
    }

    private static BlockPos groundAt(ServerLevel sl, int x, int z) {
        if (!sl.hasChunk(x >> 4, z >> 4)) return null;
        int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static void abstractAdvance(ServerLevel sl, CitizenEntity c, BlockPos target) {
        Vec3 from = c.position();
        double dx = (target.getX() + 0.5) - from.x;
        double dz = (target.getZ() + 0.5) - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return;
        int step = (int) Math.min(ABSTRACT_STEP, dist);
        int tx = (int) Math.round(from.x + dx / dist * step);
        int tz = (int) Math.round(from.z + dz / dist * step);
        BlockPos g = groundAt(sl, tx, tz);
        if (g == null || !WorkerPathing.hasFloor(sl, g.below())) return;
        c.getNavigation().stop();
        c.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, c.getYRot(), c.getXRot());
        // Without the tag the rope-fence clamp cancels this rescue -> shoved back -> stuck forever.
        CitizenEntity.tagDeliberateTeleport(c);
    }

    private static boolean isWatched(CitizenEntity c) {
        return c.level().getNearestPlayer(c, WATCH_RANGE) != null;
    }

    private static double horizDistSq(CitizenEntity c, BlockPos p) {
        double dx = (p.getX() + 0.5) - c.getX();
        double dz = (p.getZ() + 0.5) - c.getZ();
        return dx * dx + dz * dz;
    }
}
