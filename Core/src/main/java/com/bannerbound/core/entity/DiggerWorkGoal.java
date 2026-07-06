package com.bannerbound.core.entity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.territory.ChunkResource;
import com.bannerbound.core.territory.MaterialDepositLayout;
import com.bannerbound.core.world.SelectionBroadcaster;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Digger {@link OrderedWorkGoal} -- an ORDERED worker (never digs on its own, unlike the free-scanning
 * Forester). It only mines blocks inside areas the player marked with the Foreman's Rod, stored in
 * {@link BlockSelectionRegistry} with workstationType == "digger"; a selection is bound to one citizen
 * or open to "all diggers" (see {@link BlockSelection#targetsCitizen}). Two work modes share this goal:
 * ordinary terrain selections (findTarget) and material-deposit outposts (findDepositSite), whose faces
 * are worked via a state-swap to MaterialDepositLayout.workedBlock so the deposit's mass is never
 * destroyed.
 *
 * <p>It mines like a player: it walks loosely toward the work and breaks any selected block within
 * {@link #REACH} it has a clear line of sight to ({@link #canMineFromHere}). The raycast -- not raw
 * distance -- is what stops it digging through walls/floors, so REACH is deliberately generous and there
 * is no brittle "navigate to an exact adjacent tile" step. To reach a block it navigates to an APPROACH
 * tile: a standable spot near the target (never the solid block itself) so the navigator walks DOWN into
 * a pit instead of parking on the rim. Each approach is pre-vetted for line of sight, tried nearest-first,
 * and a no-progress watchdog abandons a stuck approach (then the block) so a better-placed digger can take
 * it. DiggerClaims reserves both the target block AND the standing tile so two diggers won't crowd one
 * tunnel ({@link #CONTEST_RADIUS}) yet still share an open pit in parallel.
 *
 * <p>Targeting is top-down (highest block, then nearest); an in-reach "immediate" block is always cleared
 * before pathing anywhere. It reads its tool + drop-off off the {@link CitizenEntity} (job tab), routes
 * drops into the marked chest/basket (spilling valuable overflow at its feet), and paces by tool-age
 * mine_speed, quality, and worker XP. Soil-tier needs the shovel (primary tool); stone-tier (stone/ores/
 * coal) needs a pickaxe AND the Quarry research ({@link #FLAG_QUARRY}); ProspectingQuarry can add a
 * daily-capped bonus ore on natural stone.
 *
 * <p>Two ordering traps this class exists to avoid: (1) a selection is cleared only when FULLY LOADED and
 * holding no terrain -- an unloaded chunk reads as air, which would vanish the order mid-dig (the "random
 * clearing" bug); (2) outpost commute is bootstrapped from the registry only while the outpost chunk is
 * unloaded (findDepositSite/wireDepositStorage need it loaded), mirroring the miner/herder ordering.
 * targetDrops is computed once when a target is chosen (the block can't change before we break it) so the
 * depot-room gate keys to the REAL drop, while DropOffContainers.roomFor is still re-checked live so a
 * chest filling mid-job still stops us.
 */
@ApiStatus.Internal
public class DiggerWorkGoal extends OrderedWorkGoal {
    public static final String JOB_TYPE_ID = "diggers_slab";
    public static final String SELECTION_TYPE = "digger";

    private static final int DEFAULT_MINE_TICKS = 80;
    private static final String FLAG_QUARRY = "bannerbound.unlock_quarry";
    private static final int DEPOSIT_MINE_NUMERATOR = 3;
    private static final int DEPOSIT_MINE_DENOMINATOR = 2;
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    private static final double REACH = 4.0;
    private static final double REACH_SQ = REACH * REACH;
    private static final int TARGET_TIMEOUT_TICKS = 160;
    private static final int FAILED_TTL_TICKS = 80;
    private static final int STAGNATION_LIMIT = 30;
    private static final double CONTEST_RADIUS = 2.5;

    private BlockPos targetPos;
    private boolean targetDeposit;
    private BlockPos depositAnchor;
    private ChunkResource depositResource = ChunkResource.NONE;
    private int depositBaseY = Integer.MIN_VALUE;
    private List<ItemStack> targetDrops = List.of();
    private int mineTimer;
    private int targetAge;
    private int rescanCooldown;
    private java.util.List<BlockPos> approaches = java.util.List.of();
    private int approachIdx;
    private BlockPos approachPos;
    private double bestApproachDistSq = Double.MAX_VALUE;
    private int stagnation;
    private final java.util.Map<BlockPos, Integer> recentlyFailed = new java.util.HashMap<>();

    public DiggerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
    }

    private List<ItemStack> computeDrops(BlockPos pos) {
        if (pos == null || !(citizen.level() instanceof ServerLevel sl)) return List.of();
        BlockState state = sl.getBlockState(pos);
        List<ItemStack> drops = Block.getDrops(state, sl, pos, sl.getBlockEntity(pos));
        com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
        return drops;
    }

    private List<ItemStack> computeDepositDrops(ChunkResource type) {
        List<ItemStack> drops = new java.util.ArrayList<>(MaterialDepositLayout.dropsFor(type));
        SettlementDropFilter.filterStacks(citizen.getSettlement(),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                MaterialDepositLayout.sourceBlock(type).getBlock()),
            drops);
        return drops;
    }

    private boolean depotHasRoomFor(Container depot, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            if (DropOffContainers.roomFor(depot, drop) < drop.getCount()) return false;
        }
        return true;
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!JOB_TYPE_ID.equals(citizen.getJobType()) || !citizen.hasJobTool()) return false;

        maybeBootstrapOutpostCommute();

        if (targetPos != null) {
            Container depot = resolveDepot();
            if (targetDeposit) {
                if (depot != null && markerStillDeposit() && isDepositSource(targetPos)
                        && canMineRole(MaterialDepositLayout.requiredRole(depositResource))
                        && depotHasRoomFor(depot, targetDrops)) {
                    claimWorkArea();
                    return true;
                }
            } else if (citizen.isJobReady(JOB_TYPE_ID) && depot != null
                    && isStillOrdered(targetPos) && isMineable(citizen.level(), targetPos)
                    && depotHasRoomFor(depot, targetDrops)) {
                claimWorkArea();
                return true;
            }
        }
        if (targetPos != null) clearTargetState();
        if (rescanCooldown-- > 0) return false;

        DepositSite boundDeposit = findDepositSite(true);
        if (boundDeposit != null) return tryStartDeposit(boundDeposit);

        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null) return false;

        TargetPick pick = findTarget();
        if (pick == null) {
            DepositSite openDeposit = findDepositSite(false);
            if (openDeposit != null) return tryStartDeposit(openDeposit);
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        List<ItemStack> pickDrops = computeDrops(pick.block());
        if (!depotHasRoomFor(depot, pickDrops)) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        if (pick.immediate()) {
            approaches = java.util.List.of();
            approachIdx = 0;
            approachPos = null;
        } else {
            java.util.List<BlockPos> appr = findApproaches(citizen.level(), pick.block(), citizen.blockPosition());
            if (appr.isEmpty()) {
                recentlyFailed.put(pick.block().immutable(), citizen.tickCount + FAILED_TTL_TICKS);
                rescanCooldown = RESCAN_COOLDOWN_TICKS;
                return false;
            }
            approaches = appr;
            approachIdx = 0;
            approachPos = appr.get(0);
        }
        targetPos = pick.block();
        targetDeposit = false;
        depositAnchor = null;
        depositResource = ChunkResource.NONE;
        depositBaseY = Integer.MIN_VALUE;
        citizen.setOutpostSite(null);
        targetDrops = pickDrops;
        claimWorkArea();
        resetApproachWatchdog();
        targetAge = 0;
        return true;
    }

    private record DepositSite(BlockSelection selection, BlockPos anchor,
                               ChunkResource resource, int baseY) {}

    private boolean isMaterialDepositSelection(BlockSelection sel) {
        return sel != null
            && SELECTION_TYPE.equals(sel.workstationType())
            && MaterialDepositLayout.isMaterialPacked(sel.seedItemId());
    }

    private DepositSite findDepositSite(boolean boundOnly) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        DepositSite best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (boundOnly && sel.targetsAllWorkers()) continue;
            if (!boundOnly && !sel.targetsAllWorkers()) continue;
            ChunkResource type = MaterialDepositLayout.materialResource(sel.seedItemId());
            int baseY = MaterialDepositLayout.materialBaseY(sel.seedItemId());
            if (!MaterialDepositLayout.isMaterialChunk(type) || baseY == Integer.MIN_VALUE) continue;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;
            double d = citizen.distanceToSqr(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = new DepositSite(sel, anchor, type, baseY);
            }
        }
        return best;
    }

    private boolean tryStartDeposit(DepositSite site) {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!wireDepositStorage(sl, site)) return false;
        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null) return false;
        String role = MaterialDepositLayout.requiredRole(site.resource());
        if (!canMineRole(role)) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        List<ItemStack> drops = computeDepositDrops(site.resource());
        if (!depotHasRoomFor(depot, drops)) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        TargetPick pick = findDepositTarget(sl, site);
        if (pick == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS * 2;
            return false;
        }
        if (pick.immediate()) {
            approaches = java.util.List.of();
            approachIdx = 0;
            approachPos = null;
        } else {
            java.util.List<BlockPos> appr = findApproaches(citizen.level(), pick.block(), citizen.blockPosition());
            if (appr.isEmpty()) {
                recentlyFailed.put(pick.block().immutable(), citizen.tickCount + FAILED_TTL_TICKS);
                rescanCooldown = RESCAN_COOLDOWN_TICKS;
                return false;
            }
            approaches = appr;
            approachIdx = 0;
            approachPos = appr.get(0);
        }
        targetPos = pick.block();
        targetDeposit = true;
        depositAnchor = site.anchor();
        depositResource = site.resource();
        depositBaseY = site.baseY();
        targetDrops = drops;
        claimWorkArea();
        resetApproachWatchdog();
        targetAge = 0;
        return true;
    }

    private void maybeBootstrapOutpostCommute() {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID()) || sel.targetsAllWorkers()) continue;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) return;
            if (settlement.workingClaims().contains(new ChunkPos(anchor).toLong())) {
                citizen.setOutpostSite(anchor.immutable());
            }
            return;
        }
    }

    private boolean wireDepositStorage(ServerLevel sl, DepositSite site) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        ChunkPos cp = new ChunkPos(site.anchor());
        boolean outpost = settlement.workingClaims().contains(cp.toLong());
        citizen.setOutpostSite(outpost ? site.anchor().immutable() : null);
        if (!outpost) return true;
        BlockPos storage = MinerWorkGoal.findOutpostStorage(sl, cp, site.anchor());
        if (storage == null) return false;
        if (!storage.equals(citizen.getDropOff())) citizen.setDropOff(storage);
        return true;
    }

    private TargetPick findDepositTarget(ServerLevel sl, DepositSite site) {
        BlockPos origin = citizen.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos bestImm = null;
        double bestImmD = Double.MAX_VALUE;
        for (MaterialDepositLayout.Spot s
                : MaterialDepositLayout.spots(sl.getSeed(), new ChunkPos(site.anchor()), site.baseY(), site.resource())) {
            if (!s.source()) continue;
            BlockPos pos = s.pos();
            if (!isDepositSource(site.resource(), pos)) continue;
            if (!isExposed(sl, pos)) continue;
            if (isRecentlyFailed(pos)) continue;
            if (DiggerClaims.isClaimedByOther(sl, pos, citizen.getId())) continue;
            double d = origin.distSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
            if (d < bestImmD && d <= IMMEDIATE_PREFILTER_SQ && canMineFromHere(pos)) {
                bestImmD = d;
                bestImm = pos.immutable();
            }
        }
        if (bestImm != null) return new TargetPick(bestImm, true);
        return best == null ? null : new TargetPick(best, false);
    }

    private boolean markerStillDeposit() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || depositAnchor == null) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.completed() || sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (sel.minX() == depositAnchor.getX()
                    && sel.minY() == depositAnchor.getY()
                    && sel.minZ() == depositAnchor.getZ()
                    && MaterialDepositLayout.materialResource(sel.seedItemId()) == depositResource
                    && MaterialDepositLayout.materialBaseY(sel.seedItemId()) == depositBaseY) {
                return true;
            }
        }
        return false;
    }

    private boolean isDepositSource(BlockPos pos) {
        return isDepositSource(depositResource, pos);
    }

    private boolean isDepositSource(ChunkResource type, BlockPos pos) {
        if (pos == null || !(citizen.level() instanceof ServerLevel sl)) return false;
        return sl.getBlockState(pos).is(MaterialDepositLayout.sourceBlock(type).getBlock());
    }

    private void clearTargetState() {
        DiggerClaims.releaseAll(citizen.getId());
        targetPos = null;
        targetDrops = List.of();
        targetDeposit = false;
        depositAnchor = null;
        depositResource = ChunkResource.NONE;
        depositBaseY = Integer.MIN_VALUE;
    }

    private void claimWorkArea() {
        int id = citizen.getId();
        if (targetPos != null) DiggerClaims.claim(targetPos, id);
        DiggerClaims.claim(approachPos != null ? approachPos : citizen.blockPosition(), id);
    }

    private boolean contestedTile(BlockPos tile) {
        return citizen.level() instanceof ServerLevel sl
            && DiggerClaims.hasOtherClaimNear(sl, tile, citizen.getId(), CONTEST_RADIUS);
    }

    private void advanceApproachOrAbandon() {
        approachIdx++;
        if (approachIdx >= approaches.size()) {
            markFailedAndAbandon();
            return;
        }
        approachPos = approaches.get(approachIdx);
        resetApproachWatchdog();
        navigateToTarget();
    }

    private java.util.List<BlockPos> findApproaches(Level level, BlockPos target, BlockPos origin) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos f = target.offset(dx, dy, dz);
                    if (WorkerPathing.isWalkable(level, f) && hasSightFrom(level, f, target)
                        && !contestedTile(f)) {
                        out.add(f);
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(origin::distSqr));
        return out;
    }

    private boolean hasSightFrom(Level level, BlockPos stand, BlockPos target) {
        Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
        Vec3 closest = new Vec3(
            Mth.clamp(eye.x, target.getX(), target.getX() + 1.0),
            Mth.clamp(eye.y, target.getY(), target.getY() + 1.0),
            Mth.clamp(eye.z, target.getZ(), target.getZ() + 1.0));
        BlockHitResult hit = level.clip(new ClipContext(
            eye, closest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, citizen));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    @Override
    protected boolean canKeepWorking() {
        Container depot = resolveDepot();
        if (depot == null || !depotHasRoomFor(depot, targetDrops)) return false;
        if (targetDeposit) {
            if (!JOB_TYPE_ID.equals(citizen.getJobType()) || !citizen.hasJobTool()) return false;
            return targetPos != null
                && markerStillDeposit()
                && isDepositSource(targetPos)
                && canMineRole(MaterialDepositLayout.requiredRole(depositResource));
        }
        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        return targetPos != null && isStillOrdered(targetPos) && isMineable(citizen.level(), targetPos);
    }

    @Override
    public void start() {
        mineTimer = 0;
        citizen.setWorking(true);
        // vanilla won't path a drop > 3, stranding the worker at the rim of any deeper pit.
        citizen.setDeepDigDescent(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
        resetApproachWatchdog();
        navigateToTarget();
    }

    @Override
    public void stop() {
        mineTimer = 0;
        citizen.setWorking(false);
        citizen.setDeepDigDescent(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        DiggerClaims.releaseAll(citizen.getId());
        clearTargetState();
    }

    @Override
    public void tick() {
        if (!citizen.isJobReady(JOB_TYPE_ID)) return;
        if (targetPos == null) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) { markFailedAndAbandon(); return; }
        citizen.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (canMineFromHere(targetPos)) {
            citizen.getNavigation().stop();
            mineTimer++;
            String role = targetDeposit
                ? MaterialDepositLayout.requiredRole(depositResource)
                : requiredRole(citizen.level().getBlockState(targetPos));
            ItemStack tool = toolForRole(role);
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool.copy());
            ToolAge toolAge = ToolAgeLoader.getByTool(role, tool.getItem());
            int budget = toolAge != null ? toolAge.mineTicks().orElse(DEFAULT_MINE_TICKS) : DEFAULT_MINE_TICKS;
            if (targetDeposit) {
                budget = Math.max(1, (budget * DEPOSIT_MINE_NUMERATOR + DEPOSIT_MINE_DENOMINATOR - 1)
                    / DEPOSIT_MINE_DENOMINATOR);
            }
            budget = com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(tool, budget);
            budget = skilledWorkTicks(budget);
            int interval = Math.max(1, budget / 3);
            if (mineTimer % interval == 0 && citizen.level() instanceof ServerLevel sl) {
                playSwing(sl, targetPos);
            }
            if (mineTimer >= budget) {
                if (targetDeposit) mineDepositBlock(); else mineBlock();
                mineTimer = 0;
            }
        } else {
            if (approachPos != null) {
                double d = citizen.position().distanceToSqr(
                    approachPos.getX() + 0.5, approachPos.getY() + 0.5, approachPos.getZ() + 0.5);
                if (d + 0.05 < bestApproachDistSq) {
                    bestApproachDistSq = d;
                    stagnation = 0;
                } else if (++stagnation > STAGNATION_LIMIT) {
                    advanceApproachOrAbandon();
                    return;
                }
            }
            if (citizen.getNavigation().isDone()) {
                navigateToTarget();
            }
            mineTimer = 0;
        }
    }

    private boolean canMineFromHere(BlockPos t) {
        Vec3 eye = citizen.getEyePosition();
        Vec3 closest = new Vec3(
            Mth.clamp(eye.x, t.getX(), t.getX() + 1.0),
            Mth.clamp(eye.y, t.getY(), t.getY() + 1.0),
            Mth.clamp(eye.z, t.getZ(), t.getZ() + 1.0));
        if (eye.distanceToSqr(closest) > REACH_SQ) return false;
        BlockHitResult hit = citizen.level().clip(new ClipContext(
            eye, closest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, citizen));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(t);
    }

    private void navigateToTarget() {
        if (approachPos == null) return;
        citizen.getNavigation().moveTo(
            approachPos.getX() + 0.5, approachPos.getY(), approachPos.getZ() + 0.5, skilledSpeed());
    }

    private void resetApproachWatchdog() {
        bestApproachDistSq = Double.MAX_VALUE;
        stagnation = 0;
    }

    private boolean isRecentlyFailed(BlockPos pos) {
        Integer expiry = recentlyFailed.get(pos);
        if (expiry == null) return false;
        if (citizen.tickCount >= expiry) { recentlyFailed.remove(pos); return false; }
        return true;
    }

    private void markFailedAndAbandon() {
        if (targetPos != null) {
            recentlyFailed.put(targetPos.immutable(), citizen.tickCount + FAILED_TTL_TICKS);
            DiggerClaims.releaseAll(citizen.getId());
        }
        clearTargetState();
        targetAge = 0;
        mineTimer = 0;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    private void mineDepositBlock() {
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!isDepositSource(targetPos) || !markerStillDeposit()) {
            clearTargetState();
            targetAge = 0;
            mineTimer = 0;
            return;
        }
        Container depot = resolveDepot();
        BlockState before = level.getBlockState(targetPos);
        List<ItemStack> drops = computeDepositDrops(depositResource);
        level.setBlock(targetPos, MaterialDepositLayout.workedBlock(depositResource), 3);
        serverLevel.playSound(null, targetPos, before.getSoundType().getBreakSound(),
            SoundSource.BLOCKS, 0.8f, 1.0f);
        serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before),
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
            10, 0.3, 0.3, 0.3, 0.0);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            serverLevel.getServer(), citizen.getSettlement(), "mine_block",
            com.bannerbound.core.api.research.InsightManager.matcherFor(before.getBlock()), 1);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            if (depot == null) {
                citizen.spawnAtLocation(drop);
                continue;
            }
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F,
            depositResource.name().toLowerCase(java.util.Locale.ROOT));
        citizen.consumeStamina(1);
        clearTargetState();
        targetAge = 0;
    }

    private void mineBlock() {
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!isMineable(level, targetPos)) {
            DiggerClaims.releaseAll(citizen.getId());
            targetPos = null; targetAge = 0; mineTimer = 0;
            return;
        }
        Container depot = resolveDepot();
        BlockState state = level.getBlockState(targetPos);
        List<ItemStack> drops = Block.getDrops(state, serverLevel, targetPos, level.getBlockEntity(targetPos));
        com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
        ItemStack prospected = ProspectingQuarry.tryBonus(serverLevel, citizen.getSettlement(),
            state, requiredRole(state));
        if (!prospected.isEmpty()) drops.add(prospected);
        level.destroyBlock(targetPos, false, citizen);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            serverLevel.getServer(), citizen.getSettlement(), "mine_block",
            com.bannerbound.core.api.research.InsightManager.matcherFor(state.getBlock()), 1);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            if (depot == null) { citizen.spawnAtLocation(drop); continue; }
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "stone");
        citizen.consumeStamina(1);
        DiggerClaims.releaseAll(citizen.getId());
        targetPos = null;
        targetAge = 0;
    }

    private record TargetPick(BlockPos block, boolean immediate) {}

    private TargetPick findTarget() {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return null;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(serverLevel);
        BlockPos origin = citizen.blockPosition();
        BlockPos best = null;
        int bestY = Integer.MIN_VALUE;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos bestImm = null;
        double bestImmDistSq = Double.MAX_VALUE;
        boolean removedAny = false;
        for (BlockSelection sel : registry.getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            Scan scan = scanSelection(level, sel, origin);
            // Clear a zone only when FULLY LOADED and empty; an unloaded chunk reads as air and would falsely vanish the order.
            if (scan.allLoaded() && !scan.anyTerrain()) {
                registry.unregister(sel.rodId());
                removedAny = true;
                continue;
            }
            if (scan.immediate() != null && scan.immediateDistSq() < bestImmDistSq) {
                bestImmDistSq = scan.immediateDistSq();
                bestImm = scan.immediate();
            }
            if (scan.nearest() != null && isBetterTarget(scan.bestY(), scan.bestDistSq(), bestY, bestDistSq)) {
                bestY = scan.bestY();
                bestDistSq = scan.bestDistSq();
                best = scan.nearest();
            }
        }
        if (removedAny) SelectionBroadcaster.broadcast(serverLevel.getServer());
        if (bestImm != null) return new TargetPick(bestImm, true);
        if (best != null) return new TargetPick(best, false);
        return null;
    }

    private static boolean isBetterTarget(int y, double distSq, int bestY, double bestDistSq) {
        if (y != bestY) return y > bestY;
        return distSq < bestDistSq;
    }

    private boolean isStillOrdered(BlockPos pos) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (!(citizen.level() instanceof ServerLevel serverLevel)) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(serverLevel).getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (pos.getX() >= sel.minX() && pos.getX() <= sel.maxX()
             && pos.getY() >= sel.minY() && pos.getY() <= sel.maxY()
             && pos.getZ() >= sel.minZ() && pos.getZ() <= sel.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private record Scan(BlockPos immediate, double immediateDistSq,
                        BlockPos nearest, int bestY, double bestDistSq,
                        boolean anyTerrain, boolean allLoaded) {}

    private static final double IMMEDIATE_PREFILTER_SQ = 30.0;

    private Scan scanSelection(Level level, BlockSelection sel, BlockPos origin) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        int bestY = Integer.MIN_VALUE;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos imm = null;
        double immDistSq = Double.MAX_VALUE;
        boolean anyTerrain = false;
        boolean allLoaded = true;
        for (int y = sel.maxY(); y >= sel.minY(); y--) {
            for (int x = sel.minX(); x <= sel.maxX(); x++) {
                for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                    c.set(x, y, z);
                    // Don't read (or force-load) unloaded chunks - note them unresolved (a read here would cascade-load).
                    if (!level.isLoaded(c)) { allLoaded = false; continue; }
                    if (!isTerrain(level, c)) continue;
                    anyTerrain = true;
                    if (!isMineable(level, c)) continue;
                    if (!isExposed(level, c)) continue;
                    if (isRecentlyFailed(c)) continue;
                    if (level instanceof ServerLevel sl
                        && DiggerClaims.isClaimedByOther(sl, c, citizen.getId())) continue;
                    double d = origin.distSqr(c);
                    if (isBetterTarget(y, d, bestY, bestDistSq)) { bestY = y; bestDistSq = d; best = c.immutable(); }
                    if (d < immDistSq && d <= IMMEDIATE_PREFILTER_SQ && canMineFromHere(c)) {
                        immDistSq = d; imm = c.immutable();
                    }
                }
            }
        }
        return new Scan(imm, immDistSq, best, bestY, bestDistSq, anyTerrain, allLoaded);
    }

    private static boolean isExposed(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(pos, d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    private boolean isTerrain(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getDestroySpeed(level, pos) < 0) return false;
        if (state.getCollisionShape(level, pos).isEmpty()) return false;
        return requiredRole(state) != null;
    }

    private boolean isMineable(Level level, BlockPos pos) {
        if (pos == null) return false;
        if (pos.equals(citizen.getDropOff())) return false;
        if (!isTerrain(level, pos)) return false;
        return canMineRole(requiredRole(level.getBlockState(pos)));
    }

    private static String requiredRole(BlockState state) {
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        return null;
    }

    private boolean canMineRole(String role) {
        if ("shovel".equals(role)) return true;
        if ("pickaxe".equals(role)) {
            Settlement s = citizen.getSettlement();
            return citizen.hasJobPickaxe() && s != null
                && com.bannerbound.core.api.research.ResearchManager.hasFlag(s, FLAG_QUARRY);
        }
        return false;
    }

    private ItemStack toolForRole(String role) {
        return "pickaxe".equals(role) ? citizen.getJobPickaxe() : citizen.getJobTool();
    }

    private void playSwing(ServerLevel level, BlockPos pos) {
        net.minecraft.network.protocol.game.ClientboundAnimatePacket packet =
            new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0);
        level.getChunkSource().broadcastAndSend(citizen, packet);
        BlockState state = level.getBlockState(pos);
        SoundType st = state.getSoundType();
        level.playSound(null, pos, st.getHitSound(), SoundSource.BLOCKS, st.getVolume() * 0.5f, st.getPitch());
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
    }
}
