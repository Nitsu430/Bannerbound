package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.world.StockerTasks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The Stocker - the settlement's first pure-logistics worker (extends {@link LogisticsWorkGoal}).
 * No tool, no marked work area: targets are auto-assigned by the settlement's shared
 * {@link StockerTasks} board. Cycle: claim -> walk to source -> withdraw (the load rides visibly in
 * the mainhand) -> walk to destination -> deposit -> claim the next order. Tasks execute in enqueued
 * order; with several stockers each claims the oldest open order, so the queue splits across them and
 * no stocker overrides a decision already in flight.
 *
 * <p>Failure is always soft: an emptied source, an invalidated workshop or a timed-out path just
 * releases the task (carried items are inserted back into the source container, dropped at the feet
 * only if it vanished) - the next board regen recreates the haul if the need still exists.
 *
 * <p>Carry capacity scales with job skill from {@link #CARRY_NOVICE} to {@link #CARRY_MASTER}; the
 * board caps one order at a single stack, so a load bigger than the worker can carry is split and the
 * remainder re-queues on the next regen (an unskilled stocker just makes more trips for the need).
 *
 * <p>Reachability: a per-CITIZEN embargo ({@link #unreachableUntil}) records walk positions this
 * stocker could not path to. Without it an unreachable container livelocks the worker - the leg
 * timeout releases the task, the board recreates the same lane (need still unmet), the stocker
 * re-claims it: the "stuck at the fence" grind the player sees. A one-shot A* probe at claim time and
 * again before the delivery leg rejects unreachable endpoints in a tick instead of after a 30s
 * walk-and-grind. Per-citizen because a different stocker elsewhere may legitimately reach the spot.
 *
 * <p>Outpost trips (either endpoint in a working-claim chunk) get 4x leg time and travel the long
 * wilderness legs in {@link LongHaulWalker} hops until within {@link #OUTPOST_HANDOFF} of the
 * container (one moveTo to a ~128-block target truncates at FOLLOW_RANGE and stutters a fresh route
 * each trip), then hand off to a precise direct moveTo. They also pave a trader-style road as they
 * walk - but ONLY through true wilderness: claimed chunks and the outpost's own working-claim chunk
 * are off-limits (paving griefs player builds), checked per column; a stocker already on a road
 * follows it instead of widening or duplicating it. Probes are skipped on outpost legs because a
 * truncated hop-path is normal there.
 *
 * <p>Load-safety invariants: a chunk unload mid-haul skips {@link #stop()}, so the load survives the
 * save IN THE MAINHAND while transient {@link #carried} is lost - {@link #bankRecoveredLoad} banks it
 * into the pool on the next start before {@link #withdraw} would overwrite the slot and void it. A
 * render/conjured mainhand copy (job tool, pickaxe, the combat goal's tool-age sword, the courier
 * cargo prop) must be CLEARED, never banked, or the pool gains a duplicate. Killed mid-haul, the load
 * drops AT THE BODY (raidable supply line - interdiction made real); only peaceful interrupts (night,
 * stamina, timeout, job change) bank it home.
 */
@ApiStatus.Internal
public class StockerWorkGoal extends LogisticsWorkGoal {
    public static final String JOB_TYPE_ID = "stocker";

    private static final double USE_DIST_SQ = 2.6 * 2.6;
    private static final int LEG_TIMEOUT_TICKS = 600;
    private static final double OUTPOST_HANDOFF = 28.0;

    private static final int CARRY_NOVICE = 16;
    private static final int CARRY_MASTER = 64;

    private enum Phase { TO_SOURCE, TO_DEST }

    private static final long UNREACHABLE_COOLDOWN_TICKS = 2400;

    private final java.util.Map<Long, Long> unreachableUntil = new java.util.HashMap<>();

    private StockerTasks.Task task;
    private Phase phase = Phase.TO_SOURCE;
    private ItemStack carried = ItemStack.EMPTY;
    private BlockPos walkTarget;
    private int legAge;
    private int repathCooldown;
    private boolean outpostTrip;
    private BlockPos lastRoadPos;
    private final LongHaulWalker walker = new LongHaulWalker();

    public StockerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    @Override
    protected boolean canStartWork() {
        if (!JOB_TYPE_ID.equals(citizen.getJobType())) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement s = citizen.getSettlement();
        if (s == null) return false;
        StockerTasks.Task t = StockerTasks.claim(sl, s, citizen.getUUID(), c -> !isEmbargoed(sl, c));
        if (t == null) return false;
        BlockPos source = sourceWalkPos(sl, t);
        if (source == null) {
            StockerTasks.release(s, t);
            return false;
        }
        boolean outpost = touchesOutpost(s, t.sourcePos) || touchesOutpost(s, t.destPos);
        if (!outpost && !probeReachable(source)) {
            markUnreachable(sl, source);
            citizen.broadcastCannotReach(source);
            StockerTasks.release(s, t);
            return false;
        }
        this.task = t;
        this.walkTarget = source;
        this.phase = Phase.TO_SOURCE;
        this.outpostTrip = outpost;
        citizen.setRoadBuilding(outpostTrip);
        return true;
    }

    private boolean isEmbargoed(ServerLevel sl, StockerTasks.Task t) {
        if (unreachableUntil.isEmpty()) return false;
        long now = sl.getGameTime();
        unreachableUntil.values().removeIf(until -> until <= now);
        if (unreachableUntil.isEmpty()) return false;
        BlockPos src = sourceWalkPos(sl, t);
        BlockPos dst = destWalkPos(sl, t);
        return (src != null && unreachableUntil.containsKey(src.asLong()))
            || (dst != null && unreachableUntil.containsKey(dst.asLong()));
    }

    private void markUnreachable(ServerLevel sl, BlockPos p) {
        if (p != null) unreachableUntil.put(p.asLong(), sl.getGameTime() + UNREACHABLE_COOLDOWN_TICKS);
    }

    private boolean probeReachable(BlockPos p) {
        net.minecraft.world.level.pathfinder.Path path = citizen.getNavigation().createPath(p, 1);
        if (path == null) return false;
        if (path.canReach()) return true;
        net.minecraft.world.level.pathfinder.Node end = path.getEndNode();
        if (end == null) return false;
        double dx = end.x + 0.5 - (p.getX() + 0.5);
        double dy = end.y - (p.getY() + 0.5);
        double dz = end.z + 0.5 - (p.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= USE_DIST_SQ;
    }

    private static boolean touchesOutpost(Settlement s, BlockPos pos) {
        return pos != null && s.workingClaims().contains(
            new net.minecraft.world.level.ChunkPos(pos).toLong());
    }

    @Override
    protected boolean canKeepWorking() {
        return task != null;
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        bankRecoveredLoad();
        legAge = 0;
        repathCooldown = 0;
        walker.reset(citizen);
        if (walkTarget != null) moveTo(walkTarget);
    }

    private void bankRecoveredLoad() {
        ItemStack held = citizen.getMainHandItem();
        if (held.isEmpty() || !carried.isEmpty()) return;
        // Render/conjured hand copy (job tool/pickaxe/sword/courier prop): clear, never bank, or the pool dupes.
        if (isPhantomHandCopy(held)) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        if (citizen.level() instanceof ServerLevel sl) {
            Settlement s = citizen.getSettlement();
            if (s != null) {
                Container depot = SettlementStorage.depotAggregate(sl, s, citizen.blockPosition());
                if (depot != null) held = DropOffContainers.insert(depot, held);
            }
        }
        if (!held.isEmpty()) citizen.spawnAtLocation(held);
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private boolean isPhantomHandCopy(ItemStack held) {
        if (ItemStack.isSameItemSameComponents(held, citizen.getJobTool())) return true;
        if (ItemStack.isSameItemSameComponents(held, citizen.getJobPickaxe())) return true;
        if (held.getItem() == net.minecraft.world.item.Items.CHEST) return true;
        Settlement s = citizen.getSettlement();
        return s != null && held.getItem() == s.getToolForRole("sword");
    }

    @Override
    public void tick() {
        if (task == null || !(citizen.level() instanceof ServerLevel sl)) return;
        if (walkTarget == null) { fail(sl); return; }
        citizen.getLookControl().setLookAt(
            walkTarget.getX() + 0.5, walkTarget.getY() + 0.5, walkTarget.getZ() + 0.5);
        int legTimeout = outpostTrip ? LEG_TIMEOUT_TICKS * 4 : LEG_TIMEOUT_TICKS;
        if (++legAge > legTimeout) {
            markUnreachable(sl, walkTarget);
            citizen.broadcastCannotReach(walkTarget);
            fail(sl);
            return;
        }

        double distSq = citizen.distanceToSqr(
            walkTarget.getX() + 0.5, walkTarget.getY() + 0.5, walkTarget.getZ() + 0.5);
        if (distSq > USE_DIST_SQ) {
            if (outpostTrip) {
                trampleRoad(sl);
                LongHaulWalker.Status st = walker.stepToward(
                    citizen, walkTarget, skilledSpeed(), OUTPOST_HANDOFF, true);
                if (st != LongHaulWalker.Status.ARRIVED) return;
            }
            if (--repathCooldown <= 0) {
                repathCooldown = 20;
                moveTo(walkTarget);
            }
            return;
        }
        citizen.getNavigation().stop();

        if (phase == Phase.TO_SOURCE) {
            withdraw(sl);
        } else {
            deliver(sl);
        }
    }

    private void withdraw(ServerLevel sl) {
        Settlement s = citizen.getSettlement();
        if (s == null) { fail(sl); return; }
        int want = Math.min(task.count, carryCapacity());
        if (task.sourceWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, task.sourceWorkshopId);
            if (hit != null) {
                int have = WorkshopStorage.count(sl, hit.workshop(), task.item);
                int take = Math.min(want, have);
                if (take > 0) {
                    carried = WorkshopStorage.extract(sl, hit.workshop(), task.item, take);
                }
            }
        } else if (task.sourcePos != null) {
            Container src = DropOffContainers.resolveDropOff(sl, task.sourcePos);
            if (src != null) {
                carried = DropOffContainers.extract(src, task.item, want);
            }
        }
        if (carried.isEmpty()) { fail(sl); return; }
        citizen.setItemSlot(EquipmentSlot.MAINHAND, carried.copy());
        BlockPos dest = destWalkPos(sl, task);
        if (dest == null) { fail(sl); return; }
        if (!outpostTrip && !probeReachable(dest)) {
            markUnreachable(sl, dest);
            citizen.broadcastCannotReach(dest);
            fail(sl);
            return;
        }
        walkTarget = dest;
        phase = Phase.TO_DEST;
        legAge = 0;
        walker.reset(citizen);
        moveTo(walkTarget);
    }

    private void deliver(ServerLevel sl) {
        Settlement s = citizen.getSettlement();
        ItemStack leftover = carried;
        if (task.destWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, task.destWorkshopId);
            if (hit != null) leftover = WorkshopStorage.insert(sl, hit.workshop(), carried);
        } else if (task.destPos != null) {
            Container dest = DropOffContainers.resolveDropOff(sl, task.destPos);
            if (dest != null) leftover = DropOffContainers.insert(dest, carried);
        }
        if (!leftover.isEmpty()) {
            citizen.spawnAtLocation(leftover);
        }
        carried = ItemStack.EMPTY;
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (s != null) StockerTasks.complete(s, task);
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "haul");
        citizen.consumeStamina(1);
        task = null;
    }

    private void fail(ServerLevel sl) {
        if (!carried.isEmpty()) {
            ItemStack leftover = carried;
            if (task != null) {
                if (task.sourceWorkshopId != null) {
                    Workshops.Hit hit = Workshops.findById(sl, task.sourceWorkshopId);
                    if (hit != null) leftover = WorkshopStorage.insert(sl, hit.workshop(), leftover);
                } else if (task.sourcePos != null) {
                    Container src = DropOffContainers.resolveDropOff(sl, task.sourcePos);
                    if (src != null) leftover = DropOffContainers.insert(src, leftover);
                }
            }
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
            carried = ItemStack.EMPTY;
            citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        Settlement s = citizen.getSettlement();
        if (s != null && task != null) StockerTasks.release(s, task);
        task = null;
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        if (citizen.level() instanceof ServerLevel sl && task != null) {
            if (!citizen.isAlive() && !carried.isEmpty()) {
                // Killed mid-haul: load drops AT THE BODY, lootable by the killer; peaceful interrupts bank it home.
                citizen.spawnAtLocation(carried);
                carried = ItemStack.EMPTY;
                Settlement s = citizen.getSettlement();
                if (s != null) StockerTasks.release(s, task);
                task = null;
            } else {
                fail(sl);
            }
        }
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.getNavigation().stop();
        citizen.setRoadBuilding(false);
        walker.reset(citizen);
        outpostTrip = false;
        walkTarget = null;
        phase = Phase.TO_SOURCE;
    }

    private void trampleRoad(ServerLevel sl) {
        if (!citizen.onGround()) return;
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        BlockPos feet = citizen.blockPosition();
        if (feet.equals(lastRoadPos)) return;
        lastRoadPos = feet;
        if (com.bannerbound.core.sim.TraderSimManager.isRoad(sl.getBlockState(feet.below()))) {
            return;
        }
        paveIfWild(sl, s, feet.getX(), feet.getZ());
        paveIfWild(sl, s, feet.getX() + 1, feet.getZ());
        paveIfWild(sl, s, feet.getX() - 1, feet.getZ());
        paveIfWild(sl, s, feet.getX(), feet.getZ() + 1);
        paveIfWild(sl, s, feet.getX(), feet.getZ() - 1);
    }

    private static void paveIfWild(ServerLevel sl, Settlement s, int x, int z) {
        long packed = net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4);
        if (s.claimedChunks().contains(packed) || s.workingClaims().contains(packed)) return;
        com.bannerbound.core.sim.TraderSimManager.paveColumn(sl, x, z);
    }

    private static BlockPos sourceWalkPos(ServerLevel sl, StockerTasks.Task t) {
        if (t.sourcePos != null) return t.sourcePos;
        if (t.sourceWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, t.sourceWorkshopId);
            if (hit != null && !hit.workshop().storageBlocks().isEmpty()) {
                return hit.workshop().storageBlocks().get(0);
            }
        }
        return null;
    }

    private static BlockPos destWalkPos(ServerLevel sl, StockerTasks.Task t) {
        if (t.destPos != null) return t.destPos;
        if (t.destWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, t.destWorkshopId);
            if (hit != null && !hit.workshop().storageBlocks().isEmpty()) {
                return hit.workshop().storageBlocks().get(0);
            }
        }
        return null;
    }

    private void moveTo(BlockPos p) {
        citizen.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, skilledSpeed());
    }

    private int carryCapacity() {
        return Math.round(CARRY_NOVICE + (CARRY_MASTER - CARRY_NOVICE) * jobSkill());
    }
}
