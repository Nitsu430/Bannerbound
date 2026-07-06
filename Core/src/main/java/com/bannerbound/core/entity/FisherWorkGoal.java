package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.fisher.FisherCatchTable;
import com.bannerbound.core.api.fisher.FisherShoreRegistry;
import com.bannerbound.core.api.fisher.FishingVessels;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Fisher gatherer goal (extends GathererWorkGoal): a self-directed worker with no Foreman's Rod.
 * Its Job tab is just a fishing-rod tool slot plus a drop-off the player places by the water;
 * JOB_TYPE_ID "fishers_creel" matches WorkstationUnlocks. The loop: scan for a stand tile beside
 * open water inside the settlement's claims, walk there, cast a FisherBobber into deep open water,
 * wait for a bite, reel a catch from FisherCatchTable, deposit it into the drop-off, credit the
 * "fishing" food-production statistic (Settlement.addFoodProduced), then repeat. Stand tiles are
 * soft-locked through FisherShoreRegistry so two fishers never share a spot (SHORE_SEPARATION on
 * shore, BOAT_SEPARATION at sea). In anarchy the rod is waived: a self-organizing citizen fishes
 * bare-handed and slower, resting TOOL_FREE_RECAST_DELAY_TICKS between catches. Scan work is
 * staggered per fisher (id-based jitter on the rescan/upgrade intervals) so a batch assigned at
 * once doesn't run findSpot on the same tick and stack into one frame spike.
 *
 * Casting is the hard part. A FisherBobber sticks to the FIRST water block it touches, so a naive
 * lob thunks into a tree/bank or drops on the shallow shelf at the lake's edge. Instead we simulate
 * the bobber's own ballistics (simulateLanding, which shares FisherBobber's drag/gravity constants,
 * so a velocity that lands well here lands well in the world) for a fan of launch velocities and
 * keep only an arc proven to settle in open water without clipping terrain. simulateLanding is the
 * scan's hot path (many arcs x steps x hundreds of stands) so it reuses one mutable BlockPos and
 * only materialises on a water hit. The throw is re-solved at cast time (solveArc) from the
 * citizen's ACTUAL position, not the stand tile it aimed at, so a block-short stop still arcs into
 * water rather than onto the bank; if no arc from where it stands reaches water it steps back onto
 * the spot and retries instead of casting blind. Stands are ranked by where their best arc lands --
 * depth and openness dominate (deep water bites ~2x faster), a shorter flight breaks ties, drop-off
 * proximity is a tiny final tiebreaker -- so a pier's short cast straight into deep water naturally
 * out-scores a long lob off the bank without special-casing.
 *
 * Sailing (research-gated, needs a FishingVessels provider) is the PREFERRED fishery once unlocked
 * because deep water bites ~2x faster. When the shore can't cast into genuinely deep water but a
 * deep, open body lies within SAIL_SCAN_RADIUS of the town hall, the fisher conjures a ghost vessel
 * (a raft) beside a launch bank, paddles out (LAUNCH -> SAIL_OUT), fishes from the seat, and rides
 * home (SAIL_BACK). A sail target must be both deep (>= SAIL_DEPTH_MIN) and genuinely open
 * (MIN_SAIL_EXPANSE plus a clear 3x3 via hasRaftRoom) so the raft never jams in a source block or
 * thin channel. The trip deliberately leaves the claims -- going past the territory is the point;
 * the FIXED town-hall scan radius (never the citizen's own drifting position, which made fishers
 * migrate down the coast) is the only leash. The registry claim is placed on the SEA SPOT, not the
 * launch bank, so two vessels never anchor atop each other.
 *
 * Sailing state is transient (not saved). A fisher saved out at sea wakes seated on the vessel with
 * no trip, so canStartWork ADOPTS the boat under her -- re-anchors and fishes on, or rides home --
 * and a hard interrupt (AI deactivation, sleep, refusal) likewise leaves her SEATED via stop() for
 * the next start to adopt; an occupied ghost raft never self-despawns, an orphaned one does. The
 * ride home (SAIL_BACK) outranks the ordinary lost-tool/full-depot gates. A trip that ends afloat
 * snaps ashore with a deliberate teleport (the visible ride already happened); isBedtimeSoon
 * (9000-12500, skipped under Nightshift) turns afloat fishers home early -- with margin before
 * WorkGoal's social gate at 10100 -- and blocks launching a new trip she'd immediately reverse.
 */
@ApiStatus.Internal
public class FisherWorkGoal extends GathererWorkGoal {
    public static final String JOB_TYPE_ID = "fishers_creel";

    private static final int SCAN_RADIUS = 28;
    private static final int SHORE_SEPARATION = 3;
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    private static final double ARRIVE_SQ = 1.6 * 1.6;
    private static final int WALK_TIMEOUT_TICKS = 200;
    private static final int CAST_MISS_TICKS = 80;
    private static final int MAX_DEPTH_SCORE = 8;
    private static final int REEL_TICKS = 6;
    private static final int UPGRADE_SCAN_TICKS = 1200;
    private static final double UPGRADE_MARGIN = 500.0;
    private static final double EXCELLENT_SCORE = 9000.0;
    private static final int AVOID_TICKS = 2400;
    private static final int TOOL_FREE_RECAST_DELAY_TICKS = 40;

    private static final int SAIL_DEPTH_MIN = 4;
    private static final int SAIL_DEPTH_CAP = 16;
    private static final int SAIL_SCAN_RADIUS = 48;
    private static final int SAIL_SCAN_STEP = 2;
    private static final int MIN_SAIL_DIST = 20;
    private static final int BOAT_SEPARATION = 8;
    private static final int MIN_SAIL_EXPANSE = 16;
    private static final double SAIL_ARRIVE_SQ = 2.0 * 2.0;
    private static final int SAIL_TIMEOUT_TICKS = 400;

    private static final int MAX_CAST_DIST = 12;
    private static final int MAX_FLIGHT_TICKS = 70;
    private static final float BOBBER_DRAG = 0.98f;    // must equal FisherBobber's airborne drag or simulated casts mis-land
    private static final float BOBBER_GRAVITY = 0.03f; // must equal FisherBobber.GRAVITY or simulated casts mis-land
    private static final double[] CAST_VY = {0.10, 0.22, 0.36, 0.50};
    private static final double[] CAST_SPEED = {0.18, 0.30, 0.42, 0.56};
    private static final int[][] CAST_DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int[][] EXPANSE_SAMPLES = {
        {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
        {5, 0}, {-5, 0}, {0, 5}, {0, -5}, {4, 4}, {4, -4}, {-4, 4}, {-4, -4}
    };

    private enum Phase { SEEK, WAIT, REEL, LAUNCH, SAIL_OUT, SAIL_BACK }

    private Phase phase = Phase.SEEK;
    private BlockPos shorePos;
    private BlockPos waterPos;
    private FisherBobber bobber;
    private int phaseAge;
    private int rescanCooldown;
    private int repositionTries;
    private double currentScore;
    private int upgradeCooldown;
    private BlockPos avoidStand;
    private int avoidTicks;
    private int recastDelay;
    private boolean sailing;
    private net.minecraft.world.entity.vehicle.Boat vessel;
    private BlockPos launchPos;
    private BlockPos boardWater;
    private BlockPos sailTarget;
    private int sailTimeout;

    public FisherWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        this.rescanCooldown = citizen.getId() % 100;
    }

    private int upgradeInterval() {
        return UPGRADE_SCAN_TICKS + (citizen.getId() & 255);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    public static boolean isFishingRod(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.FishingRodItem;
    }

    private boolean fisherReady() {
        if (citizen.isAnarchy()) return citizen.isFisherReady();
        return citizen.isFisherReady() && isFishingRod(citizen.getJobTool());
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        boolean ready = fisherReady();

        Container depot = resolveDepot();
        boolean depotOk = depot != null && DropOffContainers.hasFreeSlot(depot);

        if (!sailing && citizen.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat b) {
            if (citizen.level() instanceof ServerLevel sl) {
                BlockPos here = b.blockPosition();
                BlockPos launch = findLaunchStand(sl, here, here);
                if (launch != null && FisherShoreRegistry.tryClaim(citizen.getUUID(), here)) {
                    sailing = true;
                    vessel = b;
                    sailTarget = here;
                    launchPos = launch;
                    boardWater = boardWaterBeside(sl, launch);
                    shorePos = launch;
                    waterPos = null;
                    phase = (ready && depotOk && !isBedtimeSoon()) ? Phase.SAIL_OUT : Phase.SAIL_BACK;
                    phaseAge = 0;
                    sailTimeout = SAIL_TIMEOUT_TICKS;
                    return true;
                }
            }
            citizen.stopRiding();
            return false;
        }
        if (!ready || !depotOk) return false;

        if (shorePos != null && isWater(citizen.level(), waterPos)
                && !FisherShoreRegistry.isClaimedByOther(shorePos, citizen.getUUID())) {
            FisherShoreRegistry.tryClaim(citizen.getUUID(), shorePos);
            return true;
        }
        shorePos = null;
        waterPos = null;
        if (rescanCooldown-- > 0) return false;
        SailPlan plan = planSailTrip();
        if (plan != null && FisherShoreRegistry.tryClaim(citizen.getUUID(), plan.target())) {
            sailing = true;
            launchPos = plan.launch();
            sailTarget = plan.target();
            shorePos = plan.launch();
            waterPos = null;
            phase = Phase.LAUNCH;
            phaseAge = 0;
            return true;
        }
        CastSolution spot = findSpot();
        if (spot == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) {
            rescanCooldown = 5;
            return false;
        }
        shorePos = spot.stand();
        waterPos = spot.target();
        currentScore = spot.score();
        upgradeCooldown = upgradeInterval();
        repositionTries = 0;
        phase = Phase.SEEK;
        phaseAge = 0;
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (sailing && phase == Phase.SAIL_BACK) {
            return vessel != null && vessel.isAlive() && citizen.getVehicle() == vessel;
        }
        if (!fisherReady()) return false;
        Container depot = resolveDepot();
        boolean depotOk = depot != null && DropOffContainers.hasFreeSlot(depot);
        if (sailing) {
            if (phase == Phase.LAUNCH) {
                return depotOk && shorePos != null && WorkerPathing.isWalkable(citizen.level(), shorePos);
            }
            if (vessel == null || !vessel.isAlive() || citizen.getVehicle() != vessel) return false;
            if (!depotOk) startSailBack();
            return phase == Phase.SAIL_OUT || phase == Phase.SAIL_BACK
                || isWater(citizen.level(), waterPos);
        }
        if (!depotOk) return false;
        return shorePos != null
            && WorkerPathing.isWalkable(citizen.level(), shorePos)
            && isWater(citizen.level(), waterPos);
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
        citizen.setAvoidWaterPathing(true);
        boolean adopted = sailing && vessel != null && citizen.getVehicle() == vessel;
        if (!adopted) {
            phase = sailing ? Phase.LAUNCH : Phase.SEEK;
        }
        phaseAge = 0;
        if (shorePos != null && !citizen.isPassenger()) {
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(false);
        discardBobber();
        if (sailing && vessel != null && vessel.isAlive() && citizen.getVehicle() == vessel) {
            vessel = null;
            sailing = false;
            sailTarget = null;
            boardWater = null;
            launchPos = null;
        } else {
            endSailing();
        }
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        phase = Phase.SEEK;
        recastDelay = 0;
    }

    @Override
    public void tick() {
        if (!fisherReady() || shorePos == null) return;
        phaseAge++;
        if (avoidTicks > 0) avoidTicks--;
        if (--upgradeCooldown <= 0) {
            upgradeCooldown = upgradeInterval();
            if (!sailing) tryUpgradeSpot();
        }
        if (sailing && vessel != null && vessel.isAlive()
                && (phase == Phase.SEEK || phase == Phase.WAIT || phase == Phase.REEL)) {
            FishingVessels.anchor(vessel);
        }
        if (sailing && phase != Phase.LAUNCH && phase != Phase.SAIL_BACK
                && (citizen.getStamina() <= 1 || isBedtimeSoon())) {
            startSailBack();
        }
        if (waterPos != null) {
            citizen.getLookControl().setLookAt(
                waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);
            if (sailing && citizen.isPassenger()) {
                float yawTo = (float) (Math.toDegrees(Math.atan2(
                    waterPos.getZ() + 0.5 - citizen.getZ(),
                    waterPos.getX() + 0.5 - citizen.getX())) - 90.0);
                citizen.setYRot(yawTo);
                citizen.yBodyRot = yawTo;
                citizen.yHeadRot = yawTo;
            }
        }

        if (recastDelay > 0) { recastDelay--; return; }

        switch (phase) {
            case SEEK -> tickSeek();
            case WAIT -> tickWait();
            case REEL -> tickReel();
            case LAUNCH -> tickLaunch();
            case SAIL_OUT -> tickSailOut();
            case SAIL_BACK -> tickSailBack();
        }
    }

    private void tryUpgradeSpot() {
        if (phase == Phase.REEL) return;
        if (bobber != null && bobber.isHooked()) return;
        if (currentScore >= EXCELLENT_SCORE) return;
        CastSolution spot = findSpot();
        if (spot == null || spot.stand().equals(shorePos)) return;
        if (spot.score() <= currentScore + UPGRADE_MARGIN) return;
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) return;
        shorePos = spot.stand();
        waterPos = spot.target();
        currentScore = spot.score();
        repositionTries = 0;
        discardBobber();
        phase = Phase.SEEK;
        phaseAge = 0;
        citizen.getNavigation().moveTo(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
    }

    private void tickSeek() {
        if (sailing && citizen.isPassenger()) {
            cast();
            return;
        }
        double d = citizen.position().distanceToSqr(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5);
        // Cast only while standing ON the spot, never from the water, or she fishes adrift in the lake.
        if (d <= ARRIVE_SQ && !citizen.isInWater()) {
            cast();
            return;
        }
        if (citizen.getNavigation().isDone()) {
            if (phaseAge > WALK_TIMEOUT_TICKS) { abandonSpot(); return; }
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    private void cast() {
        citizen.getNavigation().stop();
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        discardBobber();
        if (sailing) {
            BlockPos fresh = pickCastWaterNearVessel(sl);
            if (fresh != null) waterPos = fresh;
        }
        Vec3 eye = new Vec3(citizen.getX(), citizen.getEyeY() - 0.1, citizen.getZ());
        Vec3 vel = solveArc(sl, eye, waterPos);
        if (vel == null) {
            if (++repositionTries > 3) { abandonSpot(); return; }
            if (sailing) {
                waterPos = pickCastWaterNearVessel(sl);
                if (waterPos == null) { abandonSpot(); return; }
                phase = Phase.SEEK;
                phaseAge = 0;
                return;
            }
            phase = Phase.SEEK;
            phaseAge = 0;
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
            return;
        }
        repositionTries = 0;
        bobber = new FisherBobber(sl, citizen, vel);
        sl.addFreshEntity(bobber);
        sl.playSound(null, citizen.blockPosition(),
            net.minecraft.sounds.SoundEvents.FISHING_BOBBER_THROW,
            net.minecraft.sounds.SoundSource.NEUTRAL, 0.5f, 0.9f);
        phase = Phase.WAIT;
        phaseAge = 0;
    }

    private void tickWait() {
        if (bobber == null || !bobber.isAlive()) { cast(); return; }
        if (!isWater(citizen.level(), bobber.blockPosition())) {
            if (phaseAge > CAST_MISS_TICKS) cast();
            return;
        }
        if (bobber.isHooked()) {
            phase = Phase.REEL;
            phaseAge = 0;
        }
    }

    private void tickReel() {
        if (phaseAge < REEL_TICKS) return;
        if (citizen.level() instanceof ServerLevel sl && bobber != null) {
            BlockPos bp = bobber.blockPosition();
            ItemStack catchStack = FisherCatchTable.roll(
                sl, citizen, citizen.getSettlement(), bp, isOpenWater(sl, bp));
            deposit(catchStack);
            com.bannerbound.core.api.settlement.Settlement settlement = citizen.getSettlement();
            if (settlement != null && !catchStack.isEmpty()) {
                settlement.addFoodProduced("fishing", catchStack.getCount());
            }
            sl.playSound(null, citizen.blockPosition(),
                net.minecraft.sounds.SoundEvents.FISHING_BOBBER_RETRIEVE,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6f, 1.0f);
            citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "fish");
            citizen.consumeStamina(1);
        }
        discardBobber();
        if (citizen.isAnarchy() && !citizen.hasJobTool()) {
            recastDelay = TOOL_FREE_RECAST_DELAY_TICKS;
            phase = Phase.SEEK;
            phaseAge = 0;
        } else {
            cast();
        }
    }

    private void deposit(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        Container depot = resolveDepot();
        ItemStack leftover = depot == null ? stack : DropOffContainers.insert(depot, stack);
        if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
    }

    private void discardBobber() {
        if (bobber != null) {
            bobber.discard();
            bobber = null;
        }
    }

    private void abandonSpot() {
        discardBobber();
        endSailing();
        avoidStand = shorePos;
        avoidTicks = AVOID_TICKS;
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        repositionTries = 0;
        phase = Phase.SEEK;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    private void tickLaunch() {
        if (launchPos == null || sailTarget == null) { abandonSpot(); return; }
        double d = citizen.position().distanceToSqr(
            launchPos.getX() + 0.5, launchPos.getY(), launchPos.getZ() + 0.5);
        // Board only once ashore on the launch stand, never mid-swim, or she conjures the raft under a swimming body.
        if (d <= ARRIVE_SQ && !citizen.isInWater()) {
            if (!board()) { abandonSpot(); return; }
            phase = Phase.SAIL_OUT;
            phaseAge = 0;
            sailTimeout = SAIL_TIMEOUT_TICKS;
            return;
        }
        if (citizen.getNavigation().isDone()) {
            if (phaseAge > WALK_TIMEOUT_TICKS) { abandonSpot(); return; }
            citizen.getNavigation().moveTo(
                launchPos.getX() + 0.5, launchPos.getY(), launchPos.getZ() + 0.5, skilledSpeed());
        }
    }

    private boolean board() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        BlockPos water = boardWaterBeside(sl, launchPos);
        if (water == null) return false;
        this.boardWater = water;
        double surfaceY = water.getY() + sl.getFluidState(water).getHeight(sl, water);
        float yaw = (float) Math.toDegrees(Math.atan2(
            sailTarget.getZ() - water.getZ(), sailTarget.getX() - water.getX())) - 90.0F;
        citizen.getNavigation().stop();
        vessel = FishingVessels.spawnGhostVessel(sl, water.getX() + 0.5, surfaceY, water.getZ() + 0.5, yaw);
        return vessel != null && citizen.startRiding(vessel, true);
    }

    private static BlockPos boardWaterBeside(ServerLevel sl, BlockPos stand) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(dir);
            if (isSurfaceWater(sl, n)) return n;
            if (isSurfaceWater(sl, n.below())) return n.below();
        }
        return null;
    }

    private void tickSailOut() {
        if (vessel == null || !vessel.isAlive() || !citizen.isPassenger()) { abandonSpot(); return; }
        if (!(citizen.level() instanceof ServerLevel sl) || sailTarget == null) { abandonSpot(); return; }
        double d = vessel.position().distanceToSqr(
            sailTarget.getX() + 0.5, vessel.getY(), sailTarget.getZ() + 0.5);
        if (d <= SAIL_ARRIVE_SQ || --sailTimeout <= 0) {
            FishingVessels.anchor(vessel);
            waterPos = pickCastWaterNearVessel(sl);
            if (waterPos == null) { abandonSpot(); return; }
            currentScore = EXCELLENT_SCORE;
            repositionTries = 0;
            cast();
            return;
        }
        FishingVessels.drive(vessel, sailTarget.getX() + 0.5, sailTarget.getZ() + 0.5,
            FishingVessels.VESSEL_SPEED);
    }

    private BlockPos pickCastWaterNearVessel(ServerLevel sl) {
        if (vessel == null) return null;
        BlockPos base = vessel.blockPosition();
        double heading = Math.toRadians(vessel.getYRot() + 90.0);
        double fx = Math.cos(heading);
        double fz = Math.sin(heading);
        List<BlockPos> candidates = new ArrayList<>();
        for (int[] dir : CAST_DIRS) {
            double len = Math.sqrt((double) (dir[0] * dir[0] + dir[1] * dir[1]));
            if ((dir[0] * fx + dir[1] * fz) / len < 0.45) continue;
            for (int r = 4; r <= 9; r++) {
                int dx = (int) Math.round(dir[0] / len * r);
                int dz = (int) Math.round(dir[1] / len * r);
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos c = base.offset(dx, dy, dz);
                    if (isSurfaceWater(sl, c)) {
                        candidates.add(c.immutable());
                        break;
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(citizen.getRandom().nextInt(candidates.size()));
    }

    private void endSailing() {
        if (!sailing) return;
        boolean wasAfloat = citizen.isPassenger() && citizen.getVehicle() == vessel;
        if (wasAfloat) {
            citizen.stopRiding();
        }
        if ((wasAfloat || citizen.isInWater()) && launchPos != null) {
            citizen.moveTo(launchPos.getX() + 0.5, launchPos.getY(), launchPos.getZ() + 0.5,
                citizen.getYRot(), 0.0F);
            CitizenEntity.tagDeliberateTeleport(citizen);   // mark the ashore snap sanctioned; must follow the moveTo above
            citizen.getNavigation().stop();
        }
        if (vessel != null && vessel.isAlive()) vessel.discard();
        vessel = null;
        sailing = false;
        sailTarget = null;
        boardWater = null;
        launchPos = null;
    }

    private boolean isBedtimeSoon() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement s = citizen.getSettlement();
        if (s != null && s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHTSHIFT)) {
            return false;
        }
        long t = sl.getDayTime() % 24_000L;
        return t >= 9_000L && t < 12_500L;
    }

    private void startSailBack() {
        if (!sailing || phase == Phase.SAIL_BACK || phase == Phase.LAUNCH) return;
        discardBobber();
        waterPos = null;
        phase = Phase.SAIL_BACK;
        phaseAge = 0;
        sailTimeout = SAIL_TIMEOUT_TICKS;
    }

    private void tickSailBack() {
        if (vessel == null || !vessel.isAlive() || !citizen.isPassenger()) { abandonSpot(); return; }
        BlockPos home = boardWater != null ? boardWater : launchPos;
        if (home == null) { abandonSpot(); return; }
        double d = vessel.position().distanceToSqr(home.getX() + 0.5, vessel.getY(), home.getZ() + 0.5);
        if (d <= SAIL_ARRIVE_SQ || --sailTimeout <= 0) {
            endSailing();
            FisherShoreRegistry.release(citizen.getUUID());
            shorePos = null;
            waterPos = null;
            phase = Phase.SEEK;
            rescanCooldown = 20;
            return;
        }
        FishingVessels.drive(vessel, home.getX() + 0.5, home.getZ() + 0.5,
            FishingVessels.VESSEL_SPEED * 1.25);
    }

    private record SailPlan(BlockPos launch, BlockPos target) {}

    private SailPlan planSailTrip() {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        if (!FishingVessels.hasProvider() || !FishingVessels.isSailingUnlocked(settlement)) return null;
        if (isBedtimeSoon()) return null;
        BlockPos origin = settlement.townHallPos() != null ? settlement.townHallPos() : citizen.blockPosition();
        BlockPos deep = findDeepOpenWater(sl, origin);
        if (deep == null) return null;
        BlockPos launch = findLaunchStand(sl, origin, deep);
        if (launch == null) return null;
        return new SailPlan(launch, deep);
    }

    private static int sailDepth(ServerLevel sl, BlockPos surface) {
        int depth = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos().set(surface);
        while (depth < SAIL_DEPTH_CAP && sl.getFluidState(p).is(FluidTags.WATER)) {
            depth++;
            p.move(0, -1, 0);
        }
        return depth;
    }

    private BlockPos findDeepOpenWater(ServerLevel sl, BlockPos origin) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int dx = -SAIL_SCAN_RADIUS; dx <= SAIL_SCAN_RADIUS; dx += SAIL_SCAN_STEP) {
            for (int dz = -SAIL_SCAN_RADIUS; dz <= SAIL_SCAN_RADIUS; dz += SAIL_SCAN_STEP) {
                for (int dy = 8; dy >= -24; dy--) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) break;
                    if (!isSurfaceWater(sl, c)) continue;
                    double distSq = origin.distSqr(c);
                    if (distSq >= (double) MIN_SAIL_DIST * MIN_SAIL_DIST
                            && !FisherShoreRegistry.isClaimedByOther(c, citizen.getUUID())
                            && !FisherShoreRegistry.isAnyClaimWithin(c, citizen.getUUID(), BOAT_SEPARATION)) {
                        int depth = sailDepth(sl, c);
                        int expanse = openExpanse(sl, c);
                        if (depth >= SAIL_DEPTH_MIN && expanse >= MIN_SAIL_EXPANSE && hasRaftRoom(sl, c)) {
                            double score = depth * 30
                                         + expanse * 2
                                         + Math.sqrt(distSq) * 0.8;
                            if (score > bestScore) {
                                bestScore = score;
                                best = c.immutable();
                            }
                        }
                    }
                    break;
                }
            }
        }
        return best;
    }

    private BlockPos findLaunchStand(ServerLevel sl, BlockPos origin, BlockPos target) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        Set<Long> tried = new HashSet<>();
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = 4; dy >= -12; dy--) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) break;
                    if (!isSurfaceWater(sl, c)) continue;
                    for (BlockPos stand : standsBeside(sl, c)) {
                        if (!tried.add(stand.asLong())) continue;
                        if (avoidTicks > 0 && stand.equals(avoidStand)) continue;
                        if (FisherShoreRegistry.isClaimedByOther(stand, citizen.getUUID())) continue;
                        double score = -stand.distSqr(target) - stand.distSqr(origin) * 0.05;
                        if (score > bestScore) {
                            bestScore = score;
                            best = stand.immutable();
                        }
                    }
                    break;
                }
            }
        }
        return best;
    }

    private record CastSolution(BlockPos stand, BlockPos target, double score) {}

    private CastSolution findSpot() {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        BlockPos origin = citizen.blockPosition();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        Set<Long> triedStands = new HashSet<>();
        CastSolution best = null;
        for (int dy = -4; dy <= 3; dy++) {
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) continue;
                    if (!isSurfaceWater(sl, c)) continue;
                    if (!inClaim(settlement, c)) continue;
                    for (BlockPos stand : standsBeside(sl, c)) {
                        if (!triedStands.add(stand.asLong())) continue;
                        if (avoidTicks > 0 && stand.equals(avoidStand)) continue;
                        if (isField(sl, settlement, stand)) continue;
                        if (FisherShoreRegistry.isClaimedByOther(stand, citizen.getUUID())) continue;
                        if (FisherShoreRegistry.isAnyClaimWithin(stand, citizen.getUUID(), SHORE_SEPARATION)) continue;
                        CastSolution sol = solveCast(sl, settlement, origin, stand);
                        if (sol == null) continue;
                        if (best == null || sol.score() > best.score()) best = sol;
                    }
                }
            }
        }
        return best;
    }

    private static List<BlockPos> standsBeside(Level level, BlockPos water) {
        List<BlockPos> out = new ArrayList<>(4);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = water.relative(dir);
            if (WorkerPathing.isWalkable(level, adj)) out.add(adj.immutable());
            else if (WorkerPathing.isWalkable(level, adj.above())) out.add(adj.above().immutable());
        }
        return out;
    }

    private CastSolution solveCast(ServerLevel sl, Settlement settlement, BlockPos origin, BlockPos stand) {
        Vec3 from = new Vec3(stand.getX() + 0.5,
                             stand.getY() + citizen.getEyeHeight() - 0.1,
                             stand.getZ() + 0.5);
        int pier = pierBonus(sl, stand);
        CastSolution best = null;
        for (int[] d : CAST_DIRS) {
            if (!waterNearby(sl, stand, d[0], d[1])) continue;
            double len = Math.sqrt((double) (d[0] * d[0] + d[1] * d[1]));
            double ux = d[0] / len;
            double uz = d[1] / len;
            for (double vy : CAST_VY) {
                for (double sp : CAST_SPEED) {
                    Vec3 v0 = new Vec3(ux * sp, vy, uz * sp);
                    Landing land = simulateLanding(sl, from, v0);
                    if (land == null) continue;
                    BlockPos w = land.pos();
                    int hdx = w.getX() - stand.getX();
                    int hdz = w.getZ() - stand.getZ();
                    if (hdx * hdx + hdz * hdz > MAX_CAST_DIST * MAX_CAST_DIST) continue;
                    if (!inClaim(settlement, w)) continue;
                    if (!isSurfaceWater(sl, w)) continue;
                    double score = (castScore(sl, w) + pier * 8) * 100.0
                                 - land.ticks() * 0.5
                                 - origin.distSqr(stand) * 0.01;
                    if (best == null || score > best.score()) {
                        best = new CastSolution(stand, w, score);
                    }
                }
            }
        }
        return best;
    }

    private static int pierBonus(Level level, BlockPos stand) {
        int sides = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(dir);
            if (isWater(level, n) || isWater(level, n.below())) sides++;
        }
        return sides;
    }

    private static boolean waterNearby(ServerLevel sl, BlockPos stand, int dx, int dz) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= 4; i++) {
            for (int dy = -2; dy <= 1; dy++) {
                p.set(stand.getX() + dx * i, stand.getY() + dy, stand.getZ() + dz * i);
                if (isSurfaceWater(sl, p)) return true;
            }
        }
        return false;
    }

    private Vec3 solveArc(ServerLevel sl, Vec3 from, BlockPos target) {
        double dx = target.getX() + 0.5 - from.x;
        double dz = target.getZ() + 0.5 - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0e-3) return null;
        double ux = dx / horiz;
        double uz = dz / horiz;
        Vec3 best = null;
        double bestErr = Double.MAX_VALUE;
        for (double vy : CAST_VY) {
            for (double sp : CAST_SPEED) {
                Vec3 v0 = new Vec3(ux * sp, vy, uz * sp);
                Landing land = simulateLanding(sl, from, v0);
                if (land == null) continue;
                double err = land.pos().distSqr(target);
                if (err <= 9.0 && err < bestErr) { bestErr = err; best = v0; }
            }
        }
        return best;
    }

    private record Landing(BlockPos pos, int ticks) {}

    private static Landing simulateLanding(ServerLevel sl, Vec3 from, Vec3 v0) {
        double x = from.x, y = from.y, z = from.z;
        double vx = v0.x, vy = v0.y, vz = v0.z;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int t = 1; t <= MAX_FLIGHT_TICKS; t++) {
            p.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (sl.getFluidState(p).is(FluidTags.WATER)) return new Landing(p.immutable(), t);
            if (!sl.getBlockState(p).getCollisionShape(sl, p).isEmpty()) return null;
            if (y < sl.getMinBuildHeight()) return null;
            vx *= BOBBER_DRAG;
            vz *= BOBBER_DRAG;
            vy -= BOBBER_GRAVITY;
            x += vx;
            y += vy;
            z += vz;
        }
        return null;
    }

    private boolean isField(ServerLevel level, Settlement settlement, BlockPos shore) {
        if (level.getBlockState(shore.below()).getBlock() instanceof net.minecraft.world.level.block.FarmBlock) return true;
        if (level.getBlockState(shore).getBlock() instanceof net.minecraft.world.level.block.CropBlock) return true;
        for (BlockSelection sel : BlockSelectionRegistry.get(level).getForSettlement(settlement.id())) {
            if (sel.completed() || sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!FarmerWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (shore.getX() >= sel.minX() && shore.getX() <= sel.maxX()
             && shore.getY() >= sel.minY() && shore.getY() <= sel.maxY()
             && shore.getZ() >= sel.minZ() && shore.getZ() <= sel.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean inClaim(Settlement settlement, BlockPos pos) {
        return settlement.claimedChunks().contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    private static int castScore(Level level, BlockPos surface) {
        int depth = Math.min(waterDepthBelow(level, surface), MAX_DEPTH_SCORE);
        return depth * 30 + openExpanse(level, surface) * 2;
    }

    private static int openExpanse(Level level, BlockPos surface) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isSurfaceWater(level, surface.offset(dx, 0, dz))) open++;
            }
        }
        for (int[] o : EXPANSE_SAMPLES) {
            if (isSurfaceWater(level, surface.offset(o[0], 0, o[1]))) open++;
        }
        return open;
    }

    private static boolean hasRaftRoom(Level level, BlockPos surface) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isSurfaceWater(level, surface.offset(dx, 0, dz))) return false;
            }
        }
        return true;
    }

    private static int waterDepthBelow(Level level, BlockPos surface) {
        int depth = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos().set(surface);
        while (depth < 8 && level.getFluidState(p).is(FluidTags.WATER)) {
            depth++;
            p.move(0, -1, 0);
        }
        return depth;
    }

    private static boolean isWater(Level level, BlockPos pos) {
        return pos != null && level.getFluidState(pos).is(FluidTags.WATER);
    }

    private static boolean isSurfaceWater(Level level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return false;
        BlockState above = level.getBlockState(pos.above());
        return above.isAir() || above.getCollisionShape(level, pos.above()).isEmpty();
    }

    private static boolean isOpenWater(Level level, BlockPos bobber) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = bobber.offset(dx, 0, dz);
                boolean watery = level.getFluidState(p).is(FluidTags.WATER) || level.getBlockState(p).isAir();
                if (!watery) return false;
                if (!level.getBlockState(p.above()).isAir()) return false;
            }
        }
        return true;
    }
}
