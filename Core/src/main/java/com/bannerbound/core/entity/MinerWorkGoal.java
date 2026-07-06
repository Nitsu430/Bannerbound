package com.bannerbound.core.entity;

import java.util.ArrayList;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.territory.BoulderLayout;
import com.bannerbound.core.territory.ChunkResource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Miner OrderedWorkGoal -- works the surface ore boulder of a marked resource chunk with the chip
 * cycle: swing at an ORE-state boulder block, swap it to the type's chipped/body state (NEVER
 * destroy it -- block states change, the boulder's mass doesn't), and route the yield straight into
 * the marked drop-off (remote insert, like every other worker). The vein-regen ticker
 * (MinerVeinRegen) slowly swaps chipped faces back to ore, so the boulder is a permanent,
 * self-refreshing work site and the chunk's identity marker forever. The miner's hard rule is the
 * opposite of the digger's REMOVE-terrain job: zero terrain edits beyond the ore<->chipped swap.
 *
 * <p>Markers are committed by the Foreman's Rod (single click in an ore chunk -> point selection of
 * type "miner"; the packed seed carries resource + boulder base height, packMine/mineResource/
 * mineBaseY). Discovery mirrors the herder: bound markers are private, open markers are reserved via
 * MinerClaims so miners spread across deposits. The chip mechanics (reach + line-of-sight, approach
 * tiles, the two-track "chip everything in reach before moving" selection, swing pacing by tool age)
 * mirror DiggerWorkGoal.
 *
 * <p>Outpost sites manage their own storage: the OUTPOST auto-assigns the nearest drop-off container
 * inside the working-claimed chunk as the citizen's drop-off (the Job tab greys its button), so
 * every downstream system sees an ordinary drop-off. ORDER TRAP in canStartWork: outpost detection
 * is pure settlement data and MUST run before the chunk-load gate -- an appointed miner standing at
 * home has to learn its remote site while that chunk is still unloaded, else SettlementPatrolGoal
 * never commutes it out and the site never loads (the deadlock the old after-hasChunk order hit;
 * HerderWorkGoal orders it the same way). A momentarily null settlement lookup must never clear the
 * site, and a single marker miss must never forget it (only MARKER_MISS_FORGET consecutive confirmed
 * recalls do) -- either would drop an actively-assigned miner to patrol and walk it all the way home.
 *
 * <p>Tool-tier gate: the pickaxe must be correct-tool for the ore block (vanilla needs_*_tool tags,
 * same progression players obey) -- a bone pick never chips iron; tool age and quality then set
 * SPEED on top, experience faster still. Status is published for the Job tab (WAITING when the vein
 * is briefly worked out, BLOCKED on persistent reach trouble, NO_DROPOFF/NO_TOOL/STORAGE_FULL on the
 * matching gate); stop() resets it so a re-jobbed worker can't carry a stale verdict. ORE_HARDNESS
 * scales chip duration off the tool age's mine ticks and is tuned against MinerVeinRegen's interval
 * so a working boulder stays mostly speckled instead of stripping bare.
 */
@ApiStatus.Internal
public class MinerWorkGoal extends OrderedWorkGoal {
    public static final String JOB_TYPE_ID = "miners_claim";
    public static final String SELECTION_TYPE = "miner";

    private static final int DEFAULT_CHIP_TICKS = 80;
    private static final int ORE_HARDNESS = 3;
    private static final int RESCAN_COOLDOWN_TICKS = 60;
    private static final int MARKER_MISS_FORGET = 4;
    private static final double REACH = 4.0;
    private static final double REACH_SQ = REACH * REACH;
    private static final int TARGET_TIMEOUT_TICKS = 200;
    private static final int STAGNATION_LIMIT = 30;
    private static final int FAILED_TTL_TICKS = 80;
    private static final double IMMEDIATE_PREFILTER_SQ = 30.0;

    private BlockPos anchor;
    private ChunkResource resource = ChunkResource.NONE;
    private int baseY;
    private BlockPos targetPos;
    private int chipTimer;
    private int targetAge;
    private int rescanCooldown;
    private int markerMisses;
    private int abandons;
    private final java.util.Map<BlockPos, Integer> recentlyFailed = new java.util.HashMap<>();
    private java.util.List<BlockPos> approaches = java.util.List.of();
    private int approachIdx;
    private BlockPos approachPos;
    private double bestApproachDistSq = Double.MAX_VALUE;
    private int stagnation;

    public MinerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    public static String packMine(ChunkResource type, int baseY) {
        return type.name() + "|" + baseY;
    }

    public static ChunkResource mineResource(String packed) {
        if (packed == null || packed.isEmpty()) return ChunkResource.NONE;
        int i = packed.indexOf('|');
        String name = i < 0 ? packed : packed.substring(0, i);
        try {
            return ChunkResource.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ChunkResource.NONE;
        }
    }

    public static int mineBaseY(String packed) {
        if (packed == null) return Integer.MIN_VALUE;
        int i = packed.indexOf('|');
        if (i < 0 || i + 1 >= packed.length()) return Integer.MIN_VALUE;
        try {
            return Integer.parseInt(packed.substring(i + 1));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!JOB_TYPE_ID.equals(citizen.getJobType()) || !citizen.hasJobTool()) return false;
        if (rescanCooldown-- > 0) return false;

        MinerClaims.releaseAll(citizen.getId());
        BlockSelection sel = findMineMarker(sl);
        if (sel == null) {
            if (citizen.getOutpostSite() != null && citizen.getSettlement() != null
                    && ++markerMisses >= MARKER_MISS_FORGET) {
                citizen.setOutpostSite(null);
                markerMisses = 0;
            }
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        markerMisses = 0;
        anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());

        // ORDER: outpost detection (settlement data, no level read) MUST precede the hasChunk gate,
        // else the miner can never learn a remote site while it is unloaded and the commute deadlocks.
        Settlement settlement = citizen.getSettlement();
        ChunkPos siteChunk = new ChunkPos(anchor);
        boolean outpost = settlement != null && settlement.workingClaims().contains(siteChunk.toLong());
        // A null settlement lookup must NOT clear the site (would send the worker home).
        if (settlement != null) citizen.setOutpostSite(outpost ? anchor.immutable() : null);

        if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) return false;
        resource = mineResource(sel.seedItemId());
        baseY = mineBaseY(sel.seedItemId());
        if (!BoulderLayout.isOreChunk(resource) || baseY == Integer.MIN_VALUE) return false;
        Item drop = BoulderLayout.dropFor(resource).orElse(null);
        if (drop == null) return false;

        if (outpost) {
            BlockPos storage = findOutpostStorage(sl, siteChunk, anchor);
            if (storage == null) {
                citizen.setCurrentWorkStatus(CitizenWorkStatus.NO_DROPOFF);
                rescanCooldown = RESCAN_COOLDOWN_TICKS * 2;
                return false;
            }
            if (!storage.equals(citizen.getDropOff())) citizen.setDropOff(storage);
        }

        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.NO_DROPOFF);
            return false;
        }
        if (!canChipWithTool()) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.NO_TOOL);
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        if (DropOffContainers.roomFor(depot, new ItemStack(drop)) < 1) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.STORAGE_FULL);
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }

        ChipPick pick = findChipPick(sl);
        if (pick == null) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.WAITING);
            rescanCooldown = RESCAN_COOLDOWN_TICKS * 2;
            return false;
        }
        if (!commitTarget(sl, pick)) {
            markFailed(pick.pos());
            abandons++;
            citizen.setCurrentWorkStatus(CitizenWorkStatus.BLOCKED);
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        citizen.setCurrentWorkStatus(abandons >= 2 ? CitizenWorkStatus.BLOCKED : CitizenWorkStatus.IDLE);
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        if (resolveDepot() == null) return false;
        if (!canChipWithTool()) return false;
        return targetPos != null && anchor != null && markerStillMine();
    }

    private boolean canChipWithTool() {
        return citizen.getJobTool().isCorrectToolForDrops(BoulderLayout.oreBlock(resource));
    }

    private boolean markerStillMine() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (sel.minX() == anchor.getX() && sel.minY() == anchor.getY() && sel.minZ() == anchor.getZ()) {
                return sel.targetsCitizen(citizen.getUUID());
            }
        }
        return false;
    }

    @Override
    public void start() {
        chipTimer = 0;
        citizen.setWorking(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
        resetApproachWatchdog();
        navigateToTarget();
    }

    @Override
    public void stop() {
        chipTimer = 0;
        citizen.setWorking(false);
        citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        MinerClaims.releaseAll(citizen.getId());
        targetPos = null;
    }

    @Override
    public void tick() {
        if (!citizen.isJobReady(JOB_TYPE_ID)) return;
        if (targetPos == null) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) { abandonTarget(); return; }
        citizen.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (!(citizen.level() instanceof ServerLevel sl)) return;
        if (!isChippable(sl, targetPos)) { nextTarget(sl); return; }

        if (canMineFromHere(targetPos)) {
            citizen.getNavigation().stop();
            chipTimer++;
            ItemStack tool = citizen.getJobTool();
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool.copy());
            ToolAge toolAge = ToolAgeLoader.getByTool("pickaxe", tool.getItem());
            int budget = ORE_HARDNESS
                * (toolAge != null ? toolAge.mineTicks().orElse(DEFAULT_CHIP_TICKS) : DEFAULT_CHIP_TICKS);
            budget = com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(tool, budget);
            budget = skilledWorkTicks(budget);
            int interval = Math.max(1, budget / 6);
            if (chipTimer % interval == 0) playSwing(sl, targetPos);
            if (chipTimer >= budget) {
                chipBlock(sl);
                chipTimer = 0;
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
            if (citizen.getNavigation().isDone()) navigateToTarget();
            chipTimer = 0;
        }
    }

    private void chipBlock(ServerLevel sl) {
        if (!isChippable(sl, targetPos)) { nextTarget(sl); return; }
        BlockState before = sl.getBlockState(targetPos);
        sl.setBlock(targetPos, BoulderLayout.chippedBlock(resource), 3);
        sl.playSound(null, targetPos, before.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before),
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.0);

        Item drop = BoulderLayout.dropFor(resource).orElse(null);
        if (drop != null) {
            ItemStack one = new ItemStack(drop);
            if (SettlementDropFilter.shouldDrop(citizen.getSettlement(), null, one)) {
                Container depot = resolveDepot();
                if (depot == null) {
                    citizen.spawnAtLocation(one);
                } else {
                    ItemStack leftover = DropOffContainers.insert(depot, one);
                    if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
                }
            }
        }
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "ore");
        citizen.consumeStamina(1);
        abandons = 0;
        citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        targetPos = null;
        targetAge = 0;
        nextTarget(sl);
    }

    private void nextTarget(ServerLevel sl) {
        ChipPick next = findChipPick(sl);
        if (next != null && commitTarget(sl, next)) {
            chipTimer = 0;
            return;
        }
        if (next != null) markFailed(next.pos());
        targetPos = null;
    }

    private record ChipPick(BlockPos pos, boolean immediate) {}

    private ChipPick findChipPick(ServerLevel sl) {
        ChunkPos cp = new ChunkPos(anchor);
        BlockPos origin = citizen.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos bestImm = null;
        double bestImmD = Double.MAX_VALUE;
        for (BoulderLayout.Spot s : BoulderLayout.spots(sl.getSeed(), cp, baseY)) {
            if (!s.ore()) continue;
            BlockPos pos = s.pos();
            if (!isChippable(sl, pos)) continue;
            if (!isExposed(sl, pos)) continue;
            if (isRecentlyFailed(pos)) continue;
            double d = origin.distSqr(pos);
            if (d < bestD) { bestD = d; best = pos.immutable(); }
            if (d < bestImmD && d <= IMMEDIATE_PREFILTER_SQ && canMineFromHere(pos)) {
                bestImmD = d;
                bestImm = pos.immutable();
            }
        }
        if (bestImm != null) return new ChipPick(bestImm, true);
        return best == null ? null : new ChipPick(best, false);
    }

    private boolean commitTarget(ServerLevel sl, ChipPick pick) {
        targetPos = pick.pos();
        if (pick.immediate()) {
            approaches = java.util.List.of();
            approachIdx = 0;
            approachPos = null;
        } else {
            approaches = findApproaches(sl, pick.pos(), citizen.blockPosition());
            approachIdx = 0;
            approachPos = approaches.isEmpty() ? null : approaches.get(0);
            if (approachPos == null && !canMineFromHere(pick.pos())) {
                return false;
            }
        }
        resetApproachWatchdog();
        targetAge = 0;
        return true;
    }

    private boolean isRecentlyFailed(BlockPos pos) {
        Integer expiry = recentlyFailed.get(pos);
        if (expiry == null) return false;
        if (citizen.tickCount >= expiry) { recentlyFailed.remove(pos); return false; }
        return true;
    }

    private void markFailed(BlockPos pos) {
        if (pos != null) recentlyFailed.put(pos.immutable(), citizen.tickCount + FAILED_TTL_TICKS);
    }

    private boolean isChippable(ServerLevel sl, BlockPos pos) {
        return pos != null && sl.getBlockState(pos).is(BoulderLayout.oreBlock(resource).getBlock());
    }

    private BlockSelection findMineMarker(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        BlockSelection best = null;
        int bestScore = Integer.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            BlockPos a = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            boolean open = sel.targetsAllWorkers();
            int score;
            if (!open) {
                score = 0;
            } else {
                if (MinerClaims.isClaimedByOther(sl, a, citizen.getId())) continue;
                score = MinerClaims.ownedBy(a, citizen.getId()) ? 1 : 2;
            }
            double d = citizen.distanceToSqr(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
            if (score < bestScore || (score == bestScore && d < bestD)) {
                best = sel; bestScore = score; bestD = d;
            }
        }
        if (best != null && best.targetsAllWorkers()) {
            MinerClaims.claim(new BlockPos(best.minX(), best.minY(), best.minZ()), citizen.getId());
        }
        return best;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
    }

    public static BlockPos findOutpostStorage(ServerLevel sl, ChunkPos cp, BlockPos anchor) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (var e : sl.getChunk(cp.x, cp.z).getBlockEntities().entrySet()) {
            BlockPos pos = e.getKey();
            if (!(e.getValue() instanceof Container)) continue;
            if (!DropOffContainers.isDropOffBlock(sl, pos)) continue;
            if (DropOffContainers.isWildStorage(sl, pos)) continue;
            double d = anchor.distSqr(pos);
            if (d < bestD) { bestD = d; best = pos.immutable(); }
        }
        return best;
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

    private java.util.List<BlockPos> findApproaches(Level level, BlockPos target, BlockPos origin) {
        java.util.List<BlockPos> out = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos f = target.offset(dx, dy, dz);
                    if (WorkerPathing.isWalkable(level, f) && hasSightFrom(level, f, target)) {
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

    private static boolean isExposed(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(pos, d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    private void navigateToTarget() {
        if (approachPos == null) return;
        citizen.getNavigation().moveTo(
            approachPos.getX() + 0.5, approachPos.getY(), approachPos.getZ() + 0.5, skilledSpeed());
    }

    private void advanceApproachOrAbandon() {
        approachIdx++;
        if (approachIdx >= approaches.size()) {
            abandonTarget();
            return;
        }
        approachPos = approaches.get(approachIdx);
        resetApproachWatchdog();
        navigateToTarget();
    }

    private void abandonTarget() {
        if (targetPos != null) markFailed(targetPos);
        abandons++;
        targetPos = null;
        targetAge = 0;
        chipTimer = 0;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    private void resetApproachWatchdog() {
        bestApproachDistSq = Double.MAX_VALUE;
        stagnation = 0;
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
