package com.bannerbound.antiquity.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.fisher.FisherShoreRegistry;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.entity.GathererWorkGoal;
import com.bannerbound.core.entity.WorkerPathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Spear-fisher {@link GathererWorkGoal} ("spear_fishers_post"), the primitive precursor to the rod
 * {@link com.bannerbound.core.entity.FisherWorkGoal}; registered into Core via CitizenJobRegistry in
 * BannerboundAntiquity. The citizen walks the shoreline to a stand beside open claimed water (reusing
 * the rod fisher's {@link FisherShoreRegistry} soft-claims and shore-walking nav; water pathing is
 * avoided so he keeps to the shore/pier), then instead of casting a bobber hunts a real
 * {@link AbstractFish}: he holds the vanilla raise-spear windup pose, throws a rope-tethered
 * {@link SpearProjectile}, and reels the resulting floating {@link SpearedFishEntity} home by the rope
 * (never walking to the body, exactly like player spear fishing); the catch deposits itself into the
 * drop-off on arrival, and a miss reels the empty tethered spear back after a settle window so it is
 * never lost. Phases: SEEK -> AIM_THROW -> WINDUP -> REEL.
 *
 * <p>The spear is a reusable equipped tool: each throw is a COPY of the job tool with the tether set
 * directly on the projectile (never via SpearItem.releaseUsing), so no fiber rope is consumed, and the
 * copy is marked non-recoverable because any recovered item would duplicate the tool; the hand is
 * emptied while the projectile flies and refilled by finishReel so it reads as one spear. Work only
 * starts with a guaranteed-free depot slot (a catch is unknown until reeled and would otherwise be
 * lost), and stop() starts the reel on a pending catch so an interrupted job never strands a speared
 * fish (the catch homes and deposits on its own tick), while a still-flying spear is simply discarded.
 * Yield is intentionally fish-gated: with no fish in throw range the worker periodically re-hunts a
 * stand beside actual fish and relocates there (without blacklisting the old spot), while an
 * unreachable stand is abandoned and blacklisted ~2 min, dropping him to patrol.
 *
 * <p>Aiming compensates gravity: aiming straight at the target lands ~2.5 blocks low at full range, so
 * solveThrowVelocity scans launch pitches -35..70 deg in 1-deg steps and keeps the pitch whose
 * simulated arc height at the target's range is closest; iterating low to high with
 * strict-improvement replacement makes the FLATTEST viable pitch win (direct shot preferred over a
 * lob), and slow swimmers are led by straight-line flight time. One constant-physics arc simulation
 * covers the whole flight because friction is 0.99 in BOTH air and water (the spear overrides its
 * water inertia to glide). The expensive shore-geometry scan is cached in standCache (best
 * STAND_CACHE_CAP stands by pier/openness/proximity score) and rebuilt only every STAND_CACHE_TICKS
 * or when the fisher leaves his 16-block cell (so ordinary shore walking never invalidates it), with
 * cheap per-use validity (walkable, unclaimed, separated, not blacklisted) re-checked in standValid;
 * the first scan is staggered by citizen id so batch-assigned fishers don't all scan the same tick.
 * Open: dbg() logging is temporary throttled diagnostics - remove once the AI is verified in-world.
 */
public class SpearFisherWorkGoal extends GathererWorkGoal {
    public static final String JOB_TYPE_ID = "spear_fishers_post";

    private static final int SCAN_RADIUS = 48;
    private static final int SCAN_VERT_UP = 8;
    private static final int SCAN_VERT_DOWN = 24;
    private static final int SHORE_SEPARATION = 3;
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    private static final double ARRIVE_SQ = 1.6 * 1.6;
    private static final int WALK_TIMEOUT_TICKS = 200;
    private static final int AVOID_TICKS = 2400;

    private static final double THROW_RANGE = 16.0;
    private static final double THROW_DAMAGE = 4.0;
    private static final double SPEAR_SPEED = 1.6;
    private static final double THROW_INACCURACY = 0.5;
    // AbstractArrow per-tick physics: move, then *0.99 friction (air AND the spear's overridden water inertia), then -0.05 gravity.
    private static final double THROW_FRICTION = 0.99;
    private static final double THROW_GRAVITY = 0.05;
    private static final int THROW_SIM_TICKS = 64;
    private static final int WINDUP_TICKS = 40;
    private static final int REEL_DELAY_TICKS = 20;
    private static final int THROW_COOLDOWN_TICKS = 30;
    private static final int THROW_SETTLE_TICKS = 30;
    private static final int STAMINA_PER_CATCH = 10;
    private static final int REEL_TIMEOUT_TICKS = 200;
    private static final double REEL_RANGE = 16.0;
    private static final int FISH_RESCAN_TICKS = 10;
    private static final int STAND_CACHE_TICKS = 1200;
    private static final int STAND_CACHE_CAP = 64;

    private enum Phase { SEEK, WINDUP, AIM_THROW, REEL }

    private Phase phase = Phase.SEEK;
    private BlockPos shorePos;
    private BlockPos waterPos;
    private int phaseAge;
    private int rescanCooldown;
    private int throwCooldown;
    private int noFishTicks;
    private BlockPos avoidStand;
    private int avoidTicks;
    private SpearProjectile spear;
    private boolean caught;
    private int catchSeenAge = -1;
    private boolean reelSoundPlayed;
    private AbstractFish windupTarget;
    private int windupTicks;
    private final List<Spot> standCache = new ArrayList<>();
    private int standCacheCooldown;
    private BlockPos cacheOrigin;
    private int dbgWaterSeen, dbgClaimedWater;

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private int dbgCooldown;

    private void dbg(String msg) {
        if (dbgCooldown > 0) { dbgCooldown--; return; }
        dbgCooldown = 40;
        LOG.info("[SpearFisher id={}] {}", citizen.getId(), msg);
    }

    public SpearFisherWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        this.rescanCooldown = citizen.getId() % 100;
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    private ItemStack currentSpearStack() {
        ItemStack tool = citizen.getJobTool();
        return tool.isEmpty() ? new ItemStack(BannerboundAntiquity.BONE_SPEAR.get()) : tool;
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) {
            dbg("not ready: job=" + citizen.getJobType() + " tool=" + citizen.hasJobTool()
                + " dropOff=" + citizen.getDropOff() + " anarchy=" + citizen.isAnarchy());
            return false;
        }
        Container depot = resolveDepot();
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) {
            dbg("no depot / full (depot=" + depot + ")");
            return false;
        }

        if (shorePos != null && WorkerPathing.isWalkable(citizen.level(), shorePos)
                && !FisherShoreRegistry.isClaimedByOther(shorePos, citizen.getUUID())) {
            FisherShoreRegistry.tryClaim(citizen.getUUID(), shorePos);
            return true;
        }
        shorePos = null;
        waterPos = null;
        if (rescanCooldown-- > 0) return false;

        Spot fishy = findStandNearFish();
        if (fishy != null) {
            return claimShore(fishy);
        }
        Spot wait = findGenericShore();
        if (wait == null) {
            dbg("findSpot=null: dropOff=" + citizen.getDropOff() + " cacheStands=" + standCache.size()
                + " surfaceWaterColumns=" + dbgWaterSeen + " (of those claimed=" + dbgClaimedWater + ")"
                + " | scan " + SCAN_RADIUS + "h x [-" + SCAN_VERT_DOWN + ",+" + SCAN_VERT_UP + "]v"
                + (dbgWaterSeen > 0 && dbgClaimedWater == 0
                    ? "  >> water found but UNCLAIMED — claim those chunks" : ""));
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        return claimShore(wait);
    }

    private boolean claimShore(Spot spot) {
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) {
            rescanCooldown = 5;
            return false;
        }
        shorePos = spot.stand();
        waterPos = spot.water();
        phase = Phase.SEEK;
        phaseAge = 0;
        dbg("claimed stand " + shorePos + " beside water " + waterPos + " → walking there");
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) return false;
        return shorePos != null && WorkerPathing.isWalkable(citizen.level(), shorePos);
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        citizen.setItemSlot(EquipmentSlot.MAINHAND, currentSpearStack().copy());
        citizen.setAvoidWaterPathing(true);
        phaseAge = 0;
        spear = null;
        caught = false;
        phase = Phase.SEEK;
        if (shorePos != null) {
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        stopWindupPose();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(false);
        if (spear != null && spear.isAlive()) spear.discard();
        SpearedFishEntity pendingCatch = findMyCatch();
        if (pendingCatch != null && !pendingCatch.isReeling()) {
            pendingCatch.startReeling();
            playReelSound();
        }
        spear = null;
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        phase = Phase.SEEK;
    }

    @Override
    public void tick() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID) || shorePos == null) return;
        phaseAge++;
        if (avoidTicks > 0) avoidTicks--;
        if (throwCooldown > 0) throwCooldown--;
        switch (phase) {
            case SEEK -> tickSeek();
            case AIM_THROW -> tickAimThrow();
            case WINDUP -> tickWindup();
            case REEL -> tickReel();
        }
    }

    private void tickSeek() {
        if (waterPos != null) {
            citizen.getLookControl().setLookAt(
                waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);
        }
        double d = citizen.position().distanceToSqr(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5);
        if (d <= ARRIVE_SQ && !citizen.isInWater()) {
            citizen.getNavigation().stop();
            phase = Phase.AIM_THROW;
            phaseAge = 0;
            return;
        }
        if (citizen.getNavigation().isDone()) {
            if (phaseAge > WALK_TIMEOUT_TICKS) { abandonSpot(); return; }
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    private void tickAimThrow() {
        if (throwCooldown > 0) return;
        AbstractFish fish = findTargetFish();
        if (fish != null) {
            noFishTicks = 0;
            windupTarget = fish;
            windupTicks = skilledWorkTicks(WINDUP_TICKS);
            citizen.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            citizen.getLookControl().setLookAt(fish);
            phase = Phase.WINDUP;
            phaseAge = 0;
            dbg("winding up at fish " + fish.getType() + " at " + fish.blockPosition());
            return;
        }
        noFishTicks++;
        if (noFishTicks % FISH_RESCAN_TICKS == 0) {
            Spot fishy = findStandNearFish();
            if (fishy != null && !fishy.stand().equals(shorePos)) {
                dbg("relocating toward fish: " + shorePos + " -> " + fishy.stand());
                relocateTo(fishy);
                return;
            }
            dbg("at stand " + shorePos + ", no fish reachable in range (waiting)");
        }
    }

    private void tickWindup() {
        if (windupTarget == null || !windupTarget.isAlive() || !windupTarget.isInWater()
                || windupTarget.distanceToSqr(citizen) > THROW_RANGE * THROW_RANGE) {
            stopWindupPose();
            phase = Phase.AIM_THROW;
            phaseAge = 0;
            return;
        }
        citizen.getLookControl().setLookAt(windupTarget);
        if (--windupTicks > 0) return;
        AbstractFish fish = windupTarget;
        stopWindupPose();
        dbg("throwing spear at fish " + fish.getType());
        throwSpearAt(fish);
        phase = Phase.REEL;
        phaseAge = 0;
    }

    private void stopWindupPose() {
        if (citizen.isUsingItem()) citizen.stopUsingItem();
        windupTarget = null;
        windupTicks = 0;
    }

    private void relocateTo(Spot spot) {
        stopWindupPose();
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) return;
        shorePos = spot.stand();
        waterPos = spot.water();
        noFishTicks = 0;
        phase = Phase.SEEK;
        phaseAge = 0;
        citizen.getNavigation().moveTo(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
    }

    private void throwSpearAt(AbstractFish fish) {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        citizen.getNavigation().stop();
        citizen.getLookControl().setLookAt(fish);
        SpearProjectile s = new SpearProjectile(sl, citizen, currentSpearStack().copy(), THROW_DAMAGE);
        Vec3 start = s.position();
        double dist = Math.sqrt(fish.distanceToSqr(citizen));
        double leadTicks = dist / SPEAR_SPEED;
        Vec3 aim = fish.position().add(0.0, fish.getBbHeight() * 0.5, 0.0)
            .add(fish.getDeltaMovement().scale(leadTicks));
        Vec3 launch = solveThrowVelocity(start, aim);
        if (launch == null) launch = aim.subtract(start);
        s.shoot(launch.x, launch.y, launch.z, (float) SPEAR_SPEED, (float) THROW_INACCURACY);
        s.setRopeTethered(true);
        s.markNoRecovery();   // projectile is a copy of the equipped tool - any recovery would dupe it
        sl.addFreshEntity(s);
        sl.playSound(null, citizen.blockPosition(),
            BannerboundAntiquity.SPEAR_THROW_ROPE_SOUND.get(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        this.spear = s;
        this.caught = false;
        this.reelSoundPlayed = false;
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private static Vec3 solveThrowVelocity(Vec3 start, Vec3 target) {
        Vec3 delta = target.subtract(start);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horiz < 1.0e-3) {
            return new Vec3(0.0, Math.signum(delta.y) * SPEAR_SPEED, 0.0);
        }
        Vec3 horizDir = new Vec3(delta.x / horiz, 0.0, delta.z / horiz);
        double bestErr = Double.MAX_VALUE;
        double bestPitch = Double.NaN;
        for (int deg = -35; deg <= 70; deg++) {
            double pitch = Math.toRadians(deg);
            double yAtTarget = simulateArcHeight(start.y, pitch, horiz);
            if (Double.isNaN(yAtTarget)) continue;
            double err = Math.abs(yAtTarget - target.y);
            if (err < bestErr - 1.0e-4) {
                bestErr = err;
                bestPitch = pitch;
            }
        }
        if (Double.isNaN(bestPitch)) return null;
        return horizDir.scale(Math.cos(bestPitch) * SPEAR_SPEED)
            .add(0.0, Math.sin(bestPitch) * SPEAR_SPEED, 0.0);
    }

    private static double simulateArcHeight(double startY, double pitch, double horizDist) {
        double vh = Math.cos(pitch) * SPEAR_SPEED;
        double vy = Math.sin(pitch) * SPEAR_SPEED;
        double h = 0.0;
        double y = startY;
        for (int t = 0; t < THROW_SIM_TICKS; t++) {
            double prevH = h;
            double prevY = y;
            h += vh;
            y += vy;
            if (h >= horizDist) {
                double frac = (horizDist - prevH) / Math.max(1.0e-6, h - prevH);
                return prevY + (y - prevY) * frac;
            }
            // Order must match AbstractArrow exactly: advance by velocity, THEN friction, THEN gravity.
            vh *= THROW_FRICTION;
            vy = vy * THROW_FRICTION - THROW_GRAVITY;
            if (vh < 1.0e-4) break;
        }
        return Double.NaN;
    }

    private void playReelSound() {
        if (reelSoundPlayed) return;
        reelSoundPlayed = true;
        if (citizen.level() instanceof ServerLevel sl) {
            sl.playSound(null, citizen.blockPosition(), BannerboundAntiquity.SPEAR_REEL_SOUND.get(),
                SoundSource.NEUTRAL, 0.9F, 0.8F + citizen.getRandom().nextFloat() * 0.2F);
        }
    }

    private void tickReel() {
        SpearedFishEntity catchEntity = findMyCatch();
        if (catchEntity != null) {
            caught = true;
            if (catchSeenAge < 0) catchSeenAge = phaseAge;
            if (phaseAge - catchSeenAge < REEL_DELAY_TICKS) return;
            if (!catchEntity.isReeling()) { catchEntity.startReeling(); playReelSound(); }
            if (phaseAge - catchSeenAge > REEL_TIMEOUT_TICKS) finishReel();
            return;
        }
        if (spear != null && spear.isAlive()) {
            if (phaseAge >= THROW_SETTLE_TICKS && !spear.isReeling()) { spear.startReeling(); playReelSound(); }
            if (phaseAge > REEL_TIMEOUT_TICKS) finishReel();
            return;
        }
        finishReel();
    }

    private void finishReel() {
        if (caught) {
            citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "fish");
            citizen.consumeStamina(STAMINA_PER_CATCH);
        }
        citizen.setItemSlot(EquipmentSlot.MAINHAND, currentSpearStack().copy());
        spear = null;
        caught = false;
        catchSeenAge = -1;
        reelSoundPlayed = false;
        phase = Phase.AIM_THROW;
        phaseAge = 0;
        throwCooldown = THROW_COOLDOWN_TICKS;
    }

    private SpearedFishEntity findMyCatch() {
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        AABB area = citizen.getBoundingBox().inflate(REEL_RANGE);
        for (SpearedFishEntity e : sl.getEntitiesOfClass(SpearedFishEntity.class, area,
                e -> e.isTethered()
                    && e.getOwnerUUID().map(citizen.getUUID()::equals).orElse(false))) {
            return e;
        }
        return null;
    }

    private AbstractFish findTargetFish() {
        if (!(citizen.level() instanceof ServerLevel sl) || shorePos == null) return null;
        AABB area = citizen.getBoundingBox().inflate(THROW_RANGE);
        AbstractFish best = null;
        double bestSq = Double.MAX_VALUE;
        for (AbstractFish f : sl.getEntitiesOfClass(AbstractFish.class, area,
                f -> f.isAlive() && f.isInWater())) {
            double dSq = f.distanceToSqr(citizen);
            if (dSq > THROW_RANGE * THROW_RANGE) continue;
            if (dSq < bestSq && citizen.hasLineOfSight(f)) {
                bestSq = dSq;
                best = f;
            }
        }
        return best;
    }

    private void abandonSpot() {
        stopWindupPose();
        if (spear != null && spear.isAlive()) spear.discard();
        spear = null;
        avoidStand = shorePos;
        avoidTicks = AVOID_TICKS;
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        noFishTicks = 0;
        phase = Phase.SEEK;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    private record Spot(BlockPos stand, BlockPos water, double score) {}

    private Spot findStandNearFish() {
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        BlockPos origin = citizen.blockPosition();
        ensureStandCache();
        if (standCache.isEmpty()) return null;
        Settlement settlement = citizen.getSettlement();
        List<AbstractFish> fish = sl.getEntitiesOfClass(AbstractFish.class,
            new AABB(origin).inflate(SCAN_RADIUS, 8.0, SCAN_RADIUS),
            f -> f.isAlive() && f.isInWater()
                && (settlement == null || inClaim(settlement, f.blockPosition())));
        if (fish.isEmpty()) return null;
        fish.sort(java.util.Comparator.comparingDouble(f -> f.blockPosition().distSqr(origin)));
        for (AbstractFish target : fish) {
            BlockPos fishPos = target.blockPosition();
            Spot best = null;
            double bestSq = Double.MAX_VALUE;
            for (Spot s : standCache) {
                double d = s.stand().distSqr(fishPos);
                if (d > THROW_RANGE * THROW_RANGE) continue;
                if (d < bestSq && standValid(s.stand())) { bestSq = d; best = s; }
            }
            if (best != null) return best;
        }
        return null;
    }

    private Spot findGenericShore() {
        ensureStandCache();
        for (Spot s : standCache) {
            if (standValid(s.stand())) return s;
        }
        return null;
    }

    private void ensureStandCache() {
        BlockPos origin = citizen.blockPosition();
        boolean sameCell = cacheOrigin != null
            && (origin.getX() >> 4) == (cacheOrigin.getX() >> 4)
            && (origin.getZ() >> 4) == (cacheOrigin.getZ() >> 4);
        if (standCacheCooldown > 0 && !standCache.isEmpty() && sameCell) {
            standCacheCooldown--;
            return;
        }
        rebuildStandCache();
        cacheOrigin = origin;
        standCacheCooldown = STAND_CACHE_TICKS;
    }

    private void rebuildStandCache() {
        standCache.clear();
        dbgWaterSeen = 0;
        dbgClaimedWater = 0;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return;
        BlockPos origin = citizen.blockPosition();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        Set<Long> tried = new HashSet<>();
        List<Spot> found = new ArrayList<>();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                boolean claimed = inClaim(settlement, c.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz));
                for (int dy = SCAN_VERT_UP; dy >= -SCAN_VERT_DOWN; dy--) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) continue;
                    if (!isSurfaceWater(sl, c)) continue;
                    dbgWaterSeen++;
                    if (claimed) {
                        dbgClaimedWater++;
                        for (BlockPos stand : standsBeside(sl, c)) {
                            if (!tried.add(stand.asLong())) continue;
                            double score = (pierBonus(sl, stand) * 8 + openWater3x3(sl, c)) * 100.0
                                         - origin.distSqr(stand) * 0.01;
                            found.add(new Spot(stand.immutable(), c.immutable(), score));
                        }
                    }
                    break;   // only the TOPMOST water surface of each column counts
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(Spot::score).reversed());
        for (int i = 0; i < Math.min(found.size(), STAND_CACHE_CAP); i++) {
            standCache.add(found.get(i));
        }
    }

    private boolean standValid(BlockPos stand) {
        if (avoidTicks > 0 && stand.equals(avoidStand)) return false;
        if (FisherShoreRegistry.isClaimedByOther(stand, citizen.getUUID())) return false;
        if (FisherShoreRegistry.isAnyClaimWithin(stand, citizen.getUUID(), SHORE_SEPARATION)) return false;
        return WorkerPathing.isWalkable(citizen.level(), stand);
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

    private static int pierBonus(Level level, BlockPos stand) {
        int sides = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(dir);
            if (isWater(level, n) || isWater(level, n.below())) sides++;
        }
        return sides;
    }

    private static int openWater3x3(Level level, BlockPos surface) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isSurfaceWater(level, surface.offset(dx, 0, dz))) open++;
            }
        }
        return open;
    }

    private static boolean inClaim(Settlement settlement, BlockPos pos) {
        return settlement.claimedChunks().contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    private static boolean isWater(Level level, BlockPos pos) {
        return pos != null && level.getFluidState(pos).is(FluidTags.WATER);
    }

    private static boolean isSurfaceWater(Level level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return false;
        return level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }
}
