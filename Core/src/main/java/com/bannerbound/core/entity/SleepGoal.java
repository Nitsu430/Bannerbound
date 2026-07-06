package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Citizens go home at night and sleep in a bed until morning. Sleep preempts work goals (a
 * gatherer mid-shift drops its tool, walks home, and lies down when night falls) and yields to
 * panic -- fire, mobs, and the like still wake a citizen the way they would a vanilla villager.
 *
 * <p>Priority 2, sharing the slot with the door/gate goals. Strictly less than the work goals at 3
 * so we preempt them (vanilla's WrappedGoal uses strict-less-than for preemption), and strictly
 * greater than panic at 1 so PanicGoal preempts us. OpenDoorGoal / OpenFenceGateGoal sit at 2 too
 * but don't claim {@code Flag.MOVE}, so a citizen walking home through a door still opens it.
 *
 * <p>Bed selection scans the home's selection union for the nearest unoccupied, unreserved BedBlock
 * HEAD. RESERVED_BEDS is an in-memory reservation set shared across every SleepGoal instance on the
 * server: OCCUPIED is not written to the block until a citizen actually arrives and calls
 * startSleeping, so without this two citizens whose canUse ticks land in the same game tick would
 * both see the bed free and pile onto it. canUse adds the picked bed, stop() removes it, and
 * {@link #releaseReservation} is the public clear called from {@code CitizenLifecycleEvents} when a
 * sleeping citizen dies -- vanilla does not guarantee stop() runs before entity removal, so the
 * reservation would otherwise leak and block the bed forever. The set is a ConcurrentHashMap-backed
 * keyset for safety if a non-main thread ever pokes it.
 *
 * <p>Stuck-bed safety: if the bed is destroyed/replaced/rotated or the home goes invalid mid-night,
 * {@link #canContinueToUse} returns false and stop() wakes the citizen and frees the bed cleanly.
 * Every wake path (morning, bed lost, panic preempt, eviction) runs through stop(), so a stale
 * OCCUPIED flag is never left behind. OCCUPIED is set on the HEAD half only, matching where vanilla
 * checks it for both renderer and claim semantics.
 *
 * <p>Reload recovery: vanilla persists the sleeping pos + pose, but this goal's transient
 * lying/targetBed fields are not saved. A save-and-reload mid-sleep thus leaves the citizen
 * visually lying down with isSleeping() true but no goal running, so work/patrol goals would grab
 * MOVE and drag the sleeper around. canUse detects that state and either reclaims the bed (when it
 * is still a valid bed in this home or outpost) or calls stopSleeping to break out so the normal
 * pick path can run.
 *
 * <p>Outpost lodging: an assigned worker beds down ON SITE when its outpost chunk offers a roofed,
 * free, unreserved bed -- beating the nightly trek home at the price of the ROUGH_LODGING thought.
 * "Roofed" means any motion-blocking block within 6 above the bed head (no walls required, a
 * lean-to is enough). That scan walks a chunk-sized region, so a miss is cached briefly via
 * outpostBedRetryAt rather than re-run on every canUse poll through the night. Night-watch guards
 * under the NIGHT_WATCH policy skip sleep entirely (a weary thought is the price) and have any
 * vanilla sleeping pose from a reload broken here.
 *
 * <p>Constants: NIGHT_START 12500 / NIGHT_END 23460 bracket vanilla's sleep window on dayTime mod
 * 24000 (monsters spawn / beds usable at 12541 rounded to 12500; natural wake at 23459).
 * BED_REACH_SQ (~1.8 blocks) is "at the bed"; BED_SETTLE_REACH_SQ (~2.5 blocks) is the fallback
 * reach used once navigation is done -- a bed under a low roof has no standable cell a 2-tall
 * citizen can path onto, so the navmesh only gets them near it, which is enough because vanilla
 * startSleeping snaps them onto the pillow regardless of headroom. REPATH_INTERVAL caps the moveTo
 * re-issue at once a second.
 */
@ApiStatus.Internal
public class SleepGoal extends Goal {
    private static final long NIGHT_START = 12_500L;
    private static final long NIGHT_END = 23_460L;
    private static final double BED_REACH_SQ = 3.25;
    private static final double BED_SETTLE_REACH_SQ = 6.25;
    private static final int REPATH_INTERVAL = 20;
    private static final java.util.Set<BlockPos> RESERVED_BEDS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final CitizenEntity citizen;
    private final double speedModifier;

    private BlockPos targetBed;
    private boolean lying;
    private int repathCooldown;
    private boolean atOutpost;
    private int outpostBedRetryAt;

    public SleepGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    private static boolean isNight(ServerLevel sl) {
        long t = sl.getDayTime() % 24_000L;
        return t >= NIGHT_START && t < NIGHT_END;
    }

    public static void releaseReservation(BlockPos bed) {
        if (bed != null) RESERVED_BEDS.remove(bed);
    }

    @Override
    public boolean canUse() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!isNight(sl)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (citizen.isGuard() && settlement.hasPolicy(
                com.bannerbound.core.api.settlement.PolicyRegistry.NIGHT_WATCH)) {
            if (citizen.isSleeping()) citizen.stopSleeping();
            return false;
        }
        Home home = settlement.getHomeFor(citizen.getUUID());

        if (citizen.isSleeping()) {
            BlockPos already = citizen.getSleepingPos().orElse(null);
            boolean reclaimable = already != null
                && ((home != null && home.valid() && isBedInHome(sl, home, already))
                    || isOutpostBed(sl, settlement, already));
            if (reclaimable) {
                targetBed = already.immutable();
                lying = true;
                RESERVED_BEDS.add(targetBed);
                return true;
            }
            citizen.stopSleeping();
        }

        BlockPos outpostBed = findOutpostBed(sl, settlement);
        if (outpostBed != null) {
            targetBed = outpostBed;
            atOutpost = true;
            return true;
        }

        if (home == null || !home.valid()) return false;
        targetBed = findFreeBed(sl, home);
        return targetBed != null;
    }

    private static boolean isBedInHome(ServerLevel sl, Home home, BlockPos bedPos) {
        BlockState bs = sl.getBlockState(bedPos);
        if (!(bs.getBlock() instanceof BedBlock)) return false;
        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) return false;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        for (BlockSelection box : registry.findByHome(home.id())) {
            if (box.contains(bedPos)) return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!isNight(sl)) return false;
        if (targetBed == null) return false;
        BlockState bs = sl.getBlockState(targetBed);
        if (!(bs.getBlock() instanceof BedBlock)) return false;
        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) return false;
        Settlement settlement = citizen.getSettlement();
        boolean homeOk = false;
        if (settlement != null) {
            Home home = settlement.getHomeFor(citizen.getUUID());
            homeOk = home != null && home.valid() && isBedInHome(sl, home, targetBed);
        }
        boolean outpostOk = settlement != null && isOutpostBed(sl, settlement, targetBed);
        return homeOk || outpostOk;
    }

    @Override
    public void start() {
        if (targetBed == null) return;
        repathCooldown = 0;
        citizen.getNavigation().moveTo(
            targetBed.getX() + 0.5, targetBed.getY(), targetBed.getZ() + 0.5, speedModifier);
    }

    @Override
    public void tick() {
        if (targetBed == null) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;

        if (lying) {
            return;
        }

        double dx = (targetBed.getX() + 0.5) - citizen.getX();
        double dy = targetBed.getY() - citizen.getY();
        double dz = (targetBed.getZ() + 0.5) - citizen.getZ();
        citizen.getLookControl().setLookAt(
            targetBed.getX() + 0.5, targetBed.getY() + 0.5, targetBed.getZ() + 0.5);

        double distSq = dx * dx + dy * dy + dz * dz;
        boolean settledNearby = distSq <= BED_SETTLE_REACH_SQ && citizen.getNavigation().isDone();
        if (distSq <= BED_REACH_SQ || settledNearby) {
            citizen.getNavigation().stop();
            citizen.startSleeping(targetBed);
            // OCCUPIED on the HEAD half only -- where vanilla checks it for render + claim.
            BlockState bs = sl.getBlockState(targetBed);
            if (bs.getBlock() instanceof BedBlock && bs.getValue(BedBlock.PART) == BedPart.HEAD) {
                sl.setBlock(targetBed, bs.setValue(BedBlock.OCCUPIED, true), Block.UPDATE_ALL);
            }
            lying = true;
            if (atOutpost && citizen.getThoughts() != null) {
                citizen.getThoughts().add(com.bannerbound.core.social.ThoughtKind.ROUGH_LODGING,
                    null, sl.getGameTime(), sl.random);
                citizen.recomputeHappiness();
            }
            return;
        }
        if (--repathCooldown <= 0 && citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(
                targetBed.getX() + 0.5, targetBed.getY(), targetBed.getZ() + 0.5, speedModifier);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    @Override
    public void stop() {
        if (lying) {
            citizen.stopSleeping();
            if (citizen.level() instanceof ServerLevel sl && targetBed != null) {
                BlockState bs = sl.getBlockState(targetBed);
                if (bs.getBlock() instanceof BedBlock && bs.getValue(BedBlock.PART) == BedPart.HEAD) {
                    sl.setBlock(targetBed, bs.setValue(BedBlock.OCCUPIED, false), Block.UPDATE_ALL);
                }
            }
            lying = false;
        }
        if (targetBed != null) {
            RESERVED_BEDS.remove(targetBed);
        }
        targetBed = null;
        repathCooldown = 0;
        atOutpost = false;
    }

    private BlockPos findOutpostBed(ServerLevel sl, Settlement settlement) {
        BlockPos site = citizen.getOutpostSite();
        if (site == null) return null;
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(site);
        if (!settlement.workingClaims().contains(cp.toLong())) return null;
        if (!sl.hasChunk(cp.x, cp.z)) return null;
        if (citizen.tickCount < outpostBedRetryAt) return null;

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        double cx = citizen.getX(), cy = citizen.getY(), cz = citizen.getZ();
        int minY = site.getY() - 12;
        int maxY = site.getY() + 12;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    BlockState bs = sl.getBlockState(m);
                    if (!(bs.getBlock() instanceof BedBlock)) continue;
                    if (bs.getValue(BedBlock.PART) != BedPart.HEAD) continue;
                    if (bs.getValue(BedBlock.OCCUPIED)) continue;
                    if (RESERVED_BEDS.contains(m)) continue;
                    if (!hasRoof(sl, m)) continue;
                    double ddx = x + 0.5 - cx, ddy = y - cy, ddz = z + 0.5 - cz;
                    double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = m.immutable();
                    }
                }
            }
        }
        if (best == null) {
            outpostBedRetryAt = citizen.tickCount + 200;
            return null;
        }
        RESERVED_BEDS.add(best);
        return best;
    }

    private static boolean hasRoof(ServerLevel sl, BlockPos bed) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= 6; dy++) {
            m.set(bed.getX(), bed.getY() + dy, bed.getZ());
            if (sl.getBlockState(m).blocksMotion()) return true;
        }
        return false;
    }

    private static boolean isOutpostBed(ServerLevel sl, Settlement settlement, BlockPos bedPos) {
        BlockState bs = sl.getBlockState(bedPos);
        if (!(bs.getBlock() instanceof BedBlock)) return false;
        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) return false;
        return settlement.workingClaims().contains(
            new net.minecraft.world.level.ChunkPos(bedPos).toLong());
    }

    private BlockPos findFreeBed(ServerLevel sl, Home home) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        List<BlockSelection> boxes = registry.findByHome(home.id());
        if (boxes.isEmpty()) return null;
        Set<BlockPos> seen = new HashSet<>();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        double cx = citizen.getX(), cy = citizen.getY(), cz = citizen.getZ();
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!seen.add(p)) continue;
                        if (RESERVED_BEDS.contains(p)) continue;
                        BlockState bs = sl.getBlockState(p);
                        if (!(bs.getBlock() instanceof BedBlock)) continue;
                        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) continue;
                        if (bs.getValue(BedBlock.OCCUPIED)) continue;
                        double ddx = p.getX() + 0.5 - cx;
                        double ddy = p.getY() - cy;
                        double ddz = p.getZ() + 0.5 - cz;
                        double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (d2 < bestDistSq) {
                            bestDistSq = d2;
                            best = p.immutable();
                        }
                    }
                }
            }
        }
        if (best != null) RESERVED_BEDS.add(best);
        return best;
    }
}
