package com.bannerbound.core.entity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.farmer.SeedCandidates;
import com.bannerbound.core.territory.ChunkResource;
import com.bannerbound.core.territory.ChunkResources;
import com.bannerbound.core.territory.CropChunks;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Farmer {@link OrderedWorkGoal}: an ORDERED worker that tends player-marked <b>fields</b>. Each
 * Foreman's-Rod selection of type {@code "farmer"} is a PERMANENT field -- unlike a digger's selection
 * it is never unregistered when momentarily idle -- carrying a chosen seed
 * ({@link BlockSelection#seedItemId}). Within a field the farmer runs the full cycle: till bare soil
 * into farmland, plant the field's seed (pulled from its marked seed source, cache first) on empty
 * farmland, and harvest mature crops into its marked drop-off (which may be the same block). A
 * banner-owned {@code "crop_outpost"} field is tended by the same goal ({@link #isFarmerField}) but is
 * never rod-editable.
 *
 * <p>Movement mirrors {@link DiggerWorkGoal}: walk to a standable tile near the work block and act on
 * any block within {@link #REACH} with a clear line of sight; per-block {@link DiggerClaims} (shared
 * across ordered workers) keep two farmers off the same spot. Work tempo is {@link #WORK_TICKS} scaled
 * by tool quality and the farmer's XP.
 *
 * <p>Harvest storage is gated in two stages so a crop is never destroyed only to spill: the WALK is
 * gated on the crop's THEORETICAL MAX yield ({@link #harvestRoomForField}, computed once per field) so
 * a near-full drop-off leaves crops standing instead of luring the farmer over; the exact at-harvest
 * gate ({@link #harvestFits}, a non-mutating {@link SlotSnapshot} dry run) then confirms every stack
 * fits before {@code destroyBlock}. Yield splits into PRODUCE (drop-off only, full count must fit) and
 * cache-eligible byproduct SEEDS (drop-off, overflowing to the seed cache): wheat/beetroot drop
 * distinct produce plus extra seeds; a self-seeding crop (carrots/potatoes) is ALL produce and is
 * never shunted into the cache. A matching seed harvested on its own crop chunk yields 2x produce,
 * doubled BEFORE the fit gate so the reservation covers it.
 *
 * <p>A field with tillable/plantable ground but no seeds sets {@link #idleNeedsSeeds}, surfaced as the
 * {@link CitizenWorkStatus#NEEDS_SEEDS} Job-tab flag. Harvest counts feed town-hall stats via
 * {@code addFoodProduced("farming", ...)}. Crop-outpost fields (in-chunk seed chest + drop-off + site)
 * are re-bound each cycle in {@link #resolveOutpostField}.
 */
@ApiStatus.Internal
public class FarmerWorkGoal extends OrderedWorkGoal {
    public static final String JOB_TYPE_ID = "farmers_granary";
    public static final String SELECTION_TYPE = "farmer";
    public static final String OUTPOST_SELECTION_TYPE = "crop_outpost";

    public static boolean isFarmerField(BlockSelection sel) {
        String t = sel.workstationType();
        return SELECTION_TYPE.equals(t) || OUTPOST_SELECTION_TYPE.equals(t);
    }

    private static final double REACH = 4.0;
    private static final double REACH_SQ = REACH * REACH;
    private static final double IMMEDIATE_PREFILTER_SQ = 30.0;
    private static final double CONTEST_RADIUS = 2.5;
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    private static final int TARGET_TIMEOUT_TICKS = 160;
    private static final int FAILED_TTL_TICKS = 80;
    private static final int STAGNATION_LIMIT = 30;
    private static final int WORK_TICKS = 24;

    private enum Action { TILL, PLANT, HARVEST }

    private BlockPos targetPos;
    private Action targetAction;
    private java.util.List<BlockPos> approaches = java.util.List.of();
    private int approachIdx;
    private BlockPos approachPos;
    private int workTimer;
    private int targetAge;
    private int rescanCooldown;
    private double bestApproachDistSq = Double.MAX_VALUE;
    private int stagnation;
    private boolean idleNeedsSeeds;
    private final java.util.Map<BlockPos, Integer> recentlyFailed = new java.util.HashMap<>();

    public FarmerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDrop() {
        return DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
    }

    private Container resolveSeeds() {
        return DropOffContainers.resolveSupply(citizen, citizen.getSeedSource());
    }

    private boolean harvestRoomForField(Container drop, Container cache, Item seed, boolean cropBonus) {
        if (drop == null || seed == Items.AIR) return false;
        net.minecraft.resources.ResourceLocation seedId =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(seed);
        Item produceItem = seedId != null
            ? com.bannerbound.core.farmer.SeedCandidates.outputFor(seedId.toString()) : Items.AIR;
        if (produceItem == Items.AIR) produceItem = seed;
        if (DropOffContainers.roomFor(drop, new ItemStack(produceItem)) < maxProduceYield(seed, cropBonus)) return false;
        int maxSeed = maxSeedYield(seed);
        if (maxSeed > 0) {
            int seedRoom = DropOffContainers.roomFor(drop, new ItemStack(seed))
                         + DropOffContainers.roomFor(cache, new ItemStack(seed));
            if (seedRoom < maxSeed) return false;
        }
        return true;
    }

    private static int maxProduceYield(Item seed, boolean cropBonus) {
        int base = (seed == Items.WHEAT_SEEDS || seed == Items.BEETROOT_SEEDS) ? 1 : 5;
        return cropBonus ? base * 2 : base;
    }

    private static boolean cropChunkBonusAt(ServerLevel sl, BlockPos g, Item seed) {
        ChunkResource want = CropChunks.cropChunkFor(seed);
        return want != ChunkResource.NONE && ChunkResources.typeAt(sl, new ChunkPos(g)) == want;
    }

    private static boolean fieldHasCropBonus(ServerLevel sl, BlockSelection sel, Item seed) {
        ChunkResource want = CropChunks.cropChunkFor(seed);
        if (want == ChunkResource.NONE) return false;
        for (int cx = sel.minX() >> 4; cx <= (sel.maxX() >> 4); cx++) {
            for (int cz = sel.minZ() >> 4; cz <= (sel.maxZ() >> 4); cz++) {
                if (ChunkResources.typeAt(sl, new ChunkPos(cx, cz)) == want) return true;
            }
        }
        return false;
    }

    private static int maxSeedYield(Item seed) {
        if (seed == Items.WHEAT_SEEDS || seed == Items.BEETROOT_SEEDS) return 3;
        return 0;
    }

    private static boolean harvestFits(Container drop, Container cache, List<ItemStack> produce, List<ItemStack> seeds) {
        SlotSnapshot d = SlotSnapshot.of(drop);
        for (ItemStack p : produce) {
            if (d.place(p, p.getCount()) > 0) return false;
        }
        SlotSnapshot c = SlotSnapshot.of(cache);
        for (ItemStack s : seeds) {
            int left = d.place(s, s.getCount());
            if (left > 0 && c.place(s, left) > 0) return false;
        }
        return true;
    }

    private static final class SlotSnapshot {
        private final Item[] item;
        private final int[] count;
        private final int containerMax;

        private SlotSnapshot(Item[] item, int[] count, int containerMax) {
            this.item = item;
            this.count = count;
            this.containerMax = containerMax;
        }

        static SlotSnapshot of(Container c) {
            if (c == null) return new SlotSnapshot(new Item[0], new int[0], 64);
            int n = c.getContainerSize();
            Item[] it = new Item[n];
            int[] ct = new int[n];
            for (int i = 0; i < n; i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) { it[i] = s.getItem(); ct[i] = s.getCount(); }
            }
            return new SlotSnapshot(it, ct, c.getMaxStackSize());
        }

        int place(ItemStack stack, int amount) {
            int cap = Math.min(containerMax, stack.getMaxStackSize());
            Item it = stack.getItem();
            for (int i = 0; i < item.length && amount > 0; i++) {
                if (item[i] == it) {
                    int add = Math.min(cap - count[i], amount);
                    if (add > 0) { count[i] += add; amount -= add; }
                }
            }
            for (int i = 0; i < item.length && amount > 0; i++) {
                if (item[i] == null) {
                    item[i] = it;
                    int add = Math.min(cap, amount);
                    count[i] = add;
                    amount -= add;
                }
            }
            return amount;
        }
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        resolveOutpostField();
        if (!citizen.isFarmerReady() || resolveDrop() == null || resolveSeeds() == null) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
            return false;
        }

        if (targetPos != null && isStillOrdered(targetPos) && actionStillValid()) {
            claimWorkArea();
            citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
            return true;
        }
        if (targetPos != null) { DiggerClaims.releaseAll(citizen.getId()); targetPos = null; targetAction = null; }
        if (rescanCooldown-- > 0) return false;
        Pick pick = findTarget();
        if (pick == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            citizen.setCurrentWorkStatus(idleNeedsSeeds
                ? CitizenWorkStatus.NEEDS_SEEDS : CitizenWorkStatus.IDLE);
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
        targetAction = pick.action();
        claimWorkArea();
        resetApproachWatchdog();
        targetAge = 0;
        citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isFarmerReady()) return false;
        if (resolveDrop() == null || resolveSeeds() == null) return false;
        return targetPos != null && isStillOrdered(targetPos) && actionStillValid();
    }

    private boolean actionStillValid() {
        if (targetAction == null) return false;
        if (targetAction == Action.HARVEST) {
            Item seed = fieldSeedAt(targetPos);
            boolean bonus = citizen.level() instanceof ServerLevel sl && cropChunkBonusAt(sl, targetPos, seed);
            if (!harvestRoomForField(resolveDrop(), citizen.getSeedCache(), seed, bonus)) return false;
        }
        Action now = actionFor(citizen.level(), targetPos, fieldSeedAt(targetPos));
        return now == targetAction;
    }

    @Override
    public void start() {
        workTimer = 0;
        citizen.setWorking(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
        resetApproachWatchdog();
        navigateToTarget();
    }

    @Override
    public void stop() {
        workTimer = 0;
        citizen.setWorking(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        DiggerClaims.releaseAll(citizen.getId());
        targetPos = null;
        targetAction = null;
    }

    @Override
    public void tick() {
        if (!citizen.isFarmerReady() || targetPos == null) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) { markFailedAndAbandon(); return; }
        citizen.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (canReach(targetPos)) {
            citizen.getNavigation().stop();
            workTimer++;
            if (workTimer % 8 == 0 && citizen.level() instanceof ServerLevel sl) {
                sl.getChunkSource().broadcastAndSend(citizen,
                    new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0));
            }
            if (workTimer >= skilledWorkTicks(com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(
                    citizen.getJobTool(), WORK_TICKS))) {
                performAction();
                workTimer = 0;
            }
        } else {
            if (approachPos != null) {
                double d = citizen.position().distanceToSqr(
                    approachPos.getX() + 0.5, approachPos.getY() + 0.5, approachPos.getZ() + 0.5);
                if (d + 0.05 < bestApproachDistSq) { bestApproachDistSq = d; stagnation = 0; }
                else if (++stagnation > STAGNATION_LIMIT) { advanceApproachOrAbandon(); return; }
            }
            if (citizen.getNavigation().isDone()) navigateToTarget();
            workTimer = 0;
        }
    }

    private void performAction() {
        Level level = citizen.level();
        if (!(level instanceof ServerLevel sl)) return;
        if (!isStillOrdered(targetPos)) { clearTarget(); return; }
        BlockPos above = targetPos.above();
        Item seed = fieldSeedAt(targetPos);
        Action act = actionFor(level, targetPos, seed);
        if (act != targetAction || act == null) { clearTarget(); return; }

        switch (act) {
            case HARVEST -> {
                BlockState cropState = level.getBlockState(above);
                List<ItemStack> drops = new java.util.ArrayList<>(Block.getDrops(cropState, sl, above, null));
                com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(cropState.getBlock()), drops);
                Item fieldSeed = fieldSeedAt(targetPos);
                List<ItemStack> seedMatching = new java.util.ArrayList<>();
                List<ItemStack> nonSeed = new java.util.ArrayList<>();
                for (ItemStack d : drops) {
                    if (d.isEmpty()) continue;
                    if (fieldSeed != Items.AIR && d.is(fieldSeed)) seedMatching.add(d); else nonSeed.add(d);
                }
                List<ItemStack> produce;
                List<ItemStack> seedDrops;
                if (!nonSeed.isEmpty()) {
                    produce = nonSeed;
                    seedDrops = seedMatching;
                } else {
                    produce = seedMatching;
                    seedDrops = java.util.List.of();
                }
                // double BEFORE the harvestFits gate so the reservation covers the bigger yield
                if (cropChunkBonusAt(sl, targetPos, fieldSeed)) {
                    for (ItemStack p : produce) {
                        p.setCount(Math.min(p.getMaxStackSize(), p.getCount() * 2));
                    }
                }
                Container drop = resolveDrop();
                Container cache = citizen.getSeedCache();
                // gate BEFORE destroy: never break a crop we cannot fully store
                if (!harvestFits(drop, cache, produce, seedDrops)) { markFailedAndAbandon(); return; }
                level.destroyBlock(above, false, citizen);
                int producedCount = 0;
                for (ItemStack p : produce) {
                    producedCount += p.getCount();
                    ItemStack leftover = drop == null ? p : DropOffContainers.insert(drop, p);
                    if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
                }
                if (producedCount > 0 && citizen.getSettlement() != null) {
                    citizen.getSettlement().addFoodProduced("farming", producedCount);
                }
                for (ItemStack s : seedDrops) {
                    ItemStack leftover = drop == null ? s : DropOffContainers.insert(drop, s);
                    if (!leftover.isEmpty()) leftover = DropOffContainers.insert(cache, leftover);
                    if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
                }
                sl.playSound(null, above, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.7f, 1.0f);
                citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "grain");
            }
            case TILL -> {
                level.setBlock(targetPos, Blocks.FARMLAND.defaultBlockState(), 3);
                sl.playSound(null, targetPos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            case PLANT -> {
                Block crop = SeedCandidates.cropFor(seed);
                if (crop != null) {
                    boolean took = DropOffContainers.extractOne(citizen.getSeedCache(), seed) != ItemStack.EMPTY;
                    if (!took) {
                        Container seeds = resolveSeeds();
                        took = seeds != null && DropOffContainers.extractOne(seeds, seed) != ItemStack.EMPTY;
                    }
                    if (took) {
                        level.setBlock(above, crop.defaultBlockState(), 3);
                        sl.playSound(null, above, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8f, 1.0f);
                    }
                }
            }
        }
        citizen.consumeStamina(1);
        clearTarget();
    }

    private void clearTarget() {
        DiggerClaims.releaseAll(citizen.getId());
        targetPos = null;
        targetAction = null;
        targetAge = 0;
        workTimer = 0;
    }

    private record Pick(BlockPos block, Action action, boolean immediate) {}

    private Pick findTarget() {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        BlockPos origin = citizen.blockPosition();
        Pick best = null;
        double bestDistSq = Double.MAX_VALUE;
        Pick bestImm = null;
        double bestImmDistSq = Double.MAX_VALUE;
        Container drop = resolveDrop();
        Container cache = citizen.getSeedCache();
        idleNeedsSeeds = false;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isFarmerField(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            Item seed = seedItem(sel.seedItemId());
            boolean hasSeed = hasSeedInStock(seed);
            boolean harvestRoom = harvestRoomForField(drop, cache, seed, fieldHasCropBonus(sl, sel, seed));
            for (int y = sel.maxY(); y >= sel.minY(); y--) {
                for (int x = sel.minX(); x <= sel.maxX(); x++) {
                    for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                        c.set(x, y, z);
                        if (!sl.isLoaded(c)) continue;
                        Action a = actionIgnoringSeedStock(sl, c, seed);
                        if (a == null) continue;
                        if (isRecentlyFailed(c)) continue;
                        if (DiggerClaims.isClaimedByOther(sl, c, citizen.getId())) continue;
                        if (a == Action.HARVEST) {
                            if (!harvestRoom) continue;
                        } else if (!hasSeed) {
                            if (hasUsableWorkAccess(sl, c, origin)) {
                                idleNeedsSeeds = true;
                            }
                            continue;
                        }
                        double d = origin.distSqr(c);
                        if (d < bestDistSq) { bestDistSq = d; best = new Pick(c.immutable(), a, false); }
                        if (d < bestImmDistSq && d <= IMMEDIATE_PREFILTER_SQ && canReach(c)) {
                            bestImmDistSq = d; bestImm = new Pick(c.immutable(), a, true);
                        }
                    }
                }
            }
        }
        if (bestImm != null) return bestImm;
        return best;
    }

    private Action actionFor(Level level, BlockPos g, Item seed) {
        Action a = actionIgnoringSeedStock(level, g, seed);
        if (a == null || a == Action.HARVEST) return a;
        return hasSeedInStock(seed) ? a : null;
    }

    private Action actionIgnoringSeedStock(Level level, BlockPos g, Item seed) {
        BlockState above = level.getBlockState(g.above());
        if (above.getBlock() instanceof CropBlock crop && crop.isMaxAge(above)) return Action.HARVEST;
        if (seed == Items.AIR || SeedCandidates.cropFor(seed) == null) return null;
        if (!above.isAir() || !level.getFluidState(g.above()).isEmpty()
                || !level.getFluidState(g).isEmpty()) return null;
        BlockState gs = level.getBlockState(g);
        if (gs.is(Blocks.FARMLAND)) return Action.PLANT;
        if (isTillable(gs)) return Action.TILL;
        return null;
    }

    private boolean hasSeedInStock(Item seed) {
        if (DropOffContainers.contains(citizen.getSeedCache(), seed)) return true;
        Container seeds = resolveSeeds();
        return seeds != null && DropOffContainers.contains(seeds, seed);
    }

    private static boolean isTillable(BlockState s) {
        return s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT_PATH)
            || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT);
    }

    private Item fieldSeedAt(BlockPos g) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return Items.AIR;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.completed() || sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isFarmerField(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (g.getX() >= sel.minX() && g.getX() <= sel.maxX()
             && g.getY() >= sel.minY() && g.getY() <= sel.maxY()
             && g.getZ() >= sel.minZ() && g.getZ() <= sel.maxZ()) {
                return seedItem(sel.seedItemId());
            }
        }
        return Items.AIR;
    }

    private void resolveOutpostField() {
        // only farmers: else every citizen's FarmerWorkGoal would wipe its outpostSite each tick (walk-away)
        if (!JOB_TYPE_ID.equals(citizen.getJobType())) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) { citizen.setOutpostSite(null); return; }
        BlockSelection field = findOutpostField(sl, settlement);
        if (field == null) { citizen.setOutpostSite(null); return; }
        ChunkPos cp = new ChunkPos(new BlockPos(field.minX(), field.minY(), field.minZ()));
        if (!settlement.workingClaims().contains(cp.toLong())) { citizen.setOutpostSite(null); return; }
        BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, field.minY(), cp.getMinBlockZ() + 8);
        citizen.setOutpostSite(anchor);
        BlockPos storage = MinerWorkGoal.findOutpostStorage(sl, cp, anchor);
        if (storage != null) {
            if (!storage.equals(citizen.getDropOff())) citizen.setDropOff(storage);
            if (!storage.equals(citizen.getSeedSource())) citizen.setSeedSource(storage);
        }
    }

    private BlockSelection findOutpostField(ServerLevel sl, Settlement settlement) {
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!OUTPOST_SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (sel.targetsCitizen(citizen.getUUID())) return sel;
        }
        return null;
    }

    private static Item seedItem(String id) {
        if (id == null || id.isEmpty()) return Items.AIR;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Items.AIR;
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
    }

    private boolean isStillOrdered(BlockPos pos) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.completed() || sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isFarmerField(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (pos.getX() >= sel.minX() && pos.getX() <= sel.maxX()
             && pos.getY() >= sel.minY() && pos.getY() <= sel.maxY()
             && pos.getZ() >= sel.minZ() && pos.getZ() <= sel.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUsableWorkAccess(Level level, BlockPos target, BlockPos origin) {
        return canReach(target) || !findApproaches(level, target.immutable(), origin).isEmpty();
    }

    private boolean canReach(BlockPos t) {
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

    private void advanceApproachOrAbandon() {
        approachIdx++;
        if (approachIdx >= approaches.size()) { markFailedAndAbandon(); return; }
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

    private boolean contestedTile(BlockPos tile) {
        return citizen.level() instanceof ServerLevel sl
            && DiggerClaims.hasOtherClaimNear(sl, tile, citizen.getId(), CONTEST_RADIUS);
    }

    private void claimWorkArea() {
        int id = citizen.getId();
        if (targetPos != null) DiggerClaims.claim(targetPos, id);
        DiggerClaims.claim(approachPos != null ? approachPos : citizen.blockPosition(), id);
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
        targetPos = null;
        targetAction = null;
        targetAge = 0;
        workTimer = 0;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }
}
