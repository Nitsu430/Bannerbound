package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.forager.ForageCategory;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.territory.ChunkResource;
import com.bannerbound.core.territory.ChunkResources;
import com.bannerbound.core.territory.CropChunks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Forager GathererWorkGoal -- gathers wild growth (flowers, mushrooms, berries, wild crops, and,
 * once Shearing is researched, vines/grass/leaves) from unclaimed wilderness near its drop-off.
 * Unlike the fisher (which works only inside claims), the forager works only OUTSIDE every
 * settlement's claims, so decorative flowers and hedges a player plants inside their own (or a
 * neighbour's) territory are never stripped. The player picks which categories it gathers in the
 * Job tab; that set is a per-citizen bitmask on CitizenEntity, intersected with research unlocks by
 * activeCategories().
 *
 * <p>Roam-and-gather: a 64-block box scan for flora would be enormous, so the forager roams instead.
 * It walks to a point in the forage band within LEASH_RADIUS of the drop-off and scans only a small
 * box around itself (findTargetNear) for enabled growth; finding some, it harvests the patch then
 * roams on. Empty roams accumulate a barrenStreak, and after BARREN_YIELD_STREAK in a row it rests
 * (yields to patrol) for BARREN_COOLDOWN_TICKS rather than pacing forever. The forage band is the
 * ring of chunks claimed by NO settlement yet within FORAGE_BAND_CHUNKS (4 = 64 blocks) of one of
 * OUR claimed chunks -- measured from the border, so it shifts outward automatically as claims grow.
 *
 * <p>Harvest kinds: berries are picked and the bush/cave-vine left to regrow; wild crops are reset to
 * a seedling (sustainable, farmland kept); everything else is broken and its drops routed into the
 * drop-off. All drops are filtered by the settlement known-set (what research gates produce), like
 * every worker. Shear-gated categories (vines/grass/leaves) yield what SHEARS would (the block
 * itself); scavenging (STICKS_FIBERS) adds sticks-from-leaves / fibers-from-grass raws on top via
 * ForagerHooks. Wild crops may be picked only in a genuine crop chunk that no outpost is working:
 * the deterministic crop-chunk test is cached per goal (cropChunkCache), but the working-claim check
 * must stay live because an outpost can be planted at any time. The forager has no tool slot, so it
 * gathers bare-handed at the reduced anarchy rate and speeds up to the base wind-up the moment a
 * government is enacted (anarchyWorkSpeedFactor -> 1.0). Foraged food no longer grants a live status
 * pulse; what is deposited feeds the town via the larder (COOKING_PLAN.md Part 1), and addFoodProduced
 * only records a descriptive stat.
 */
@ApiStatus.Internal
public class ForagerWorkGoal extends GathererWorkGoal {
    public static final String JOB_TYPE_ID = "foragers_basket";

    private static final int LEASH_RADIUS = 64;
    private static final int FORAGE_BAND_CHUNKS = 4;
    private static final int SCAN_RADIUS = 8;
    private static final int SCAN_HEIGHT = 4;
    private static final double REACH_SQ = 3.0 * 3.0;
    private static final int HARVEST_WINDUP_TICKS = 30;
    private static final int SWING_INTERVAL = 12;
    private static final int TARGET_TIMEOUT_TICKS = 120;
    private static final int RESCAN_COOLDOWN_TICKS = 20;
    private static final int ROAM_TIMEOUT_TICKS = 200;
    private static final double ARRIVE_SQ = 2.2 * 2.2;
    private static final int BARREN_YIELD_STREAK = 4;
    private static final int BARREN_COOLDOWN_TICKS = 200;

    private enum Phase { ROAM, GATHER }

    private Phase phase = Phase.ROAM;
    private BlockPos target;
    private BlockPos standPos;
    private BlockPos roamPos;
    private int harvestTimer;
    private int targetAge;
    private int roamAge;
    private int rescanCooldown;
    private int barrenStreak;
    private final java.util.Map<Long, Boolean> cropChunkCache = new java.util.HashMap<>();

    public ForagerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!citizen.isForagerReady()) return false;
        if (resolveDepot() == null) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        List<ForageCategory> active = activeCategories();
        if (active.isEmpty()) return false;

        if (target != null && isForageable(sl, target, active) && inForageBand(sl, citizen.getSettlement(), target)) {
            phase = Phase.GATHER;
            return true;
        }
        target = null;
        if (rescanCooldown-- > 0) return false;

        BlockPos near = findTargetNear(sl, citizen.blockPosition(), active);
        if (near != null) {
            setTarget(sl, near);
            phase = Phase.GATHER;
            return true;
        }
        BlockPos roam = pickRoamPos(sl);
        if (roam == null) {
            rescanCooldown = BARREN_COOLDOWN_TICKS;
            return false;
        }
        roamPos = roam;
        roamAge = 0;
        phase = Phase.ROAM;
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isForagerReady()) return false;
        if (resolveDepot() == null) return false;
        return !activeCategories().isEmpty();
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(true);
        harvestTimer = 0;
        if (phase == Phase.GATHER && standPos != null) {
            moveTo(standPos);
        } else if (roamPos != null) {
            moveTo(roamPos);
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(false);
        target = null;
        standPos = null;
        roamPos = null;
        phase = Phase.ROAM;
    }

    @Override
    public void tick() {
        if (!citizen.isForagerReady() || !(citizen.level() instanceof ServerLevel sl)) return;
        switch (phase) {
            case ROAM -> tickRoam(sl);
            case GATHER -> tickGather(sl);
        }
    }

    private void tickRoam(ServerLevel sl) {
        if (rescanCooldown-- <= 0) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            BlockPos near = findTargetNear(sl, citizen.blockPosition(), activeCategories());
            if (near != null) {
                setTarget(sl, near);
                phase = Phase.GATHER;
                barrenStreak = 0;
                return;
            }
        }
        if (roamPos == null) { roamPos = pickRoamPos(sl); roamAge = 0; return; }
        double d = citizen.position().distanceToSqr(roamPos.getX() + 0.5, roamPos.getY(), roamPos.getZ() + 0.5);
        if (d <= ARRIVE_SQ || ++roamAge > ROAM_TIMEOUT_TICKS) {
            if (++barrenStreak >= BARREN_YIELD_STREAK) {
                barrenStreak = 0;
                roamPos = null;
                rescanCooldown = BARREN_COOLDOWN_TICKS;
                return;
            }
            roamPos = pickRoamPos(sl);
            roamAge = 0;
            if (roamPos != null) moveTo(roamPos);
            return;
        }
        if (citizen.getNavigation().isDone()) moveTo(roamPos);
    }

    private BlockPos pickRoamPos(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        BlockPos near = pickBandPointNear(sl, settlement, citizen.blockPosition());
        if (near != null) return near;
        BlockPos drop = citizen.getDropOff();
        return drop != null ? pickBandPointNear(sl, settlement, drop) : null;
    }

    private BlockPos pickBandPointNear(ServerLevel sl, Settlement settlement, BlockPos anchor) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = anchor.getX() + citizen.getRandom().nextInt(LEASH_RADIUS * 2 + 1) - LEASH_RADIUS;
            int z = anchor.getZ() + citizen.getRandom().nextInt(LEASH_RADIUS * 2 + 1) - LEASH_RADIUS;
            if (!inForageBandColumn(sl, settlement, x, z)) continue;
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
                return p;
            }
        }
        return null;
    }

    private void tickGather(ServerLevel sl) {
        if (target == null) { phase = Phase.ROAM; return; }
        if (++targetAge > TARGET_TIMEOUT_TICKS || !depotHasRoom()
                || !isForageable(sl, target, activeCategories())
                || !inForageBand(sl, citizen.getSettlement(), target)) {
            clearTarget();
            phase = Phase.ROAM;
            return;
        }
        citizen.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        double dx = (target.getX() + 0.5) - citizen.getX();
        double dz = (target.getZ() + 0.5) - citizen.getZ();
        double horizSq = dx * dx + dz * dz;
        double dy = Math.abs((target.getY() + 0.5) - citizen.getY());
        if (horizSq <= REACH_SQ && dy <= 3.0) {
            citizen.getNavigation().stop();
            harvestTimer++;
            if (harvestTimer % SWING_INTERVAL == 0) playSwing(sl, target);
            int windup = skilledWorkTicks(
                (int) Math.round(HARVEST_WINDUP_TICKS * citizen.anarchyWorkSpeedFactor()));
            if (harvestTimer >= windup) {
                harvest(sl, target);
                harvestTimer = 0;
                clearTarget();
                phase = Phase.GATHER;
            }
        } else {
            if (citizen.getNavigation().isDone() && standPos != null) moveTo(standPos);
            harvestTimer = 0;
        }
    }

    private void harvest(ServerLevel sl, BlockPos pos) {
        BlockState state = sl.getBlockState(pos);
        ForageCategory cat = categoryOf(state, activeCategories());
        if (cat == null) return;
        Container depot = resolveDepot();
        Settlement settlement = citizen.getSettlement();

        if (cat == ForageCategory.WILD_CROPS) {
            if (!isWildCropChunk(sl, pos)) return;
            pickWildCrop(sl, pos, state, depot, settlement);
        } else if (cat.sustainable()) {
            pickBerries(sl, pos, state, depot, settlement);
        } else {
            BlockPos breakPos = lowerHalf(state, pos);
            BlockState breakState = sl.getBlockState(breakPos);
            ItemStack tool = cat.usesShears() ? new ItemStack(Items.SHEARS) : ItemStack.EMPTY;
            List<ItemStack> drops = new ArrayList<>(Block.getDrops(breakState, sl, breakPos, null, citizen, tool));
            if (cat == ForageCategory.STICKS_FIBERS) {
                drops.addAll(com.bannerbound.core.api.forager.ForagerHooks
                    .scavenge(sl, breakState, sl.random));
            }
            SettlementDropFilter.filterStacks(settlement,
                BuiltInRegistries.BLOCK.getKey(breakState.getBlock()), drops);
            sl.destroyBlock(breakPos, false, citizen);
            deposit(depot, drops);
        }
        playSwing(sl, pos);
        citizen.consumeStamina(1);
    }

    private void pickBerries(ServerLevel sl, BlockPos pos, BlockState state, Container depot, Settlement settlement) {
        List<ItemStack> drops = new ArrayList<>(1);
        if (state.is(Blocks.SWEET_BERRY_BUSH)) {
            int age = state.getValue(SweetBerryBushBlock.AGE);
            int count = 1 + sl.random.nextInt(2) + (age >= 3 ? 1 : 0);
            drops.add(new ItemStack(Items.SWEET_BERRIES, count));
            sl.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), 2);
        } else if (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
            drops.add(new ItemStack(Items.GLOW_BERRIES, 1));
            sl.setBlock(pos, state.setValue(CaveVines.BERRIES, Boolean.FALSE), 2);
        }
        SettlementDropFilter.filterStacks(settlement,
            BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
        deposit(depot, drops);
    }

    private void pickWildCrop(ServerLevel sl, BlockPos pos, BlockState state, Container depot, Settlement settlement) {
        if (!(state.getBlock() instanceof CropBlock crop)) return;
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, sl, pos, null, citizen, ItemStack.EMPTY));
        SettlementDropFilter.filterStacks(settlement,
            BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
        sl.setBlock(pos, crop.getStateForAge(0), 2);
        deposit(depot, drops);
    }

    private boolean isWildCropChunk(ServerLevel sl, BlockPos pos) {
        long ckey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        Boolean cached = cropChunkCache.get(ckey);
        if (cached == null) {
            ChunkResource type = ChunkResources.typeAt(sl, new ChunkPos(pos));
            cached = CropChunks.isCropChunk(type);
            cropChunkCache.put(ckey, cached);
        }
        if (!cached) return false;
        return SettlementData.get(sl).getByWorkingClaim(ckey) == null;   // working-claim check must stay live; never cache it (an outpost can appear anytime)
    }

    private void deposit(Container depot, List<ItemStack> drops) {
        boolean movedAny = false;
        int foodStored = 0;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            movedAny = true;
            if (depot == null) { citizen.spawnAtLocation(drop); continue; }
            ItemStack original = drop.copy();
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (isFood(original)) foodStored += original.getCount() - leftover.getCount();
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        if (movedAny) citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "forage");
        if (foodStored > 0 && citizen.getSettlement() != null) {
            citizen.getSettlement().addFoodProduced("foraging", foodStored);
        }
    }

    private boolean isFood(ItemStack stack) {
        return com.bannerbound.core.api.settlement.data.FoodValueLoader.base(stack.getItem()) > 0.0f;
    }

    private void grantFoodPulse(ItemStack stack) {
    }

    private static boolean insertedSome(ItemStack original, ItemStack remainder) {
        if (original.isEmpty()) return false;
        return remainder.isEmpty() || remainder.getCount() < original.getCount();
    }

    private BlockPos findTargetNear(ServerLevel sl, BlockPos center, List<ForageCategory> active) {
        if (active.isEmpty() || !depotHasRoom()) return null;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dy = -SCAN_HEIGHT; dy <= SCAN_HEIGHT; dy++) {
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    c.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!sl.isLoaded(c)) continue;
                    BlockState s = sl.getBlockState(c);
                    if (!matchesAny(s, active)) continue;
                    if (!inForageBand(sl, settlement, c)) continue;
                    if (s.getBlock() instanceof CropBlock && !isWildCropChunk(sl, c)) continue;
                    double d = center.distSqr(c);
                    if (d < bestSq) { bestSq = d; best = c.immutable(); }
                }
            }
        }
        return best;
    }

    private boolean depotHasRoom() {
        Container d = resolveDepot();
        return d != null && DropOffContainers.hasFreeSlot(d);
    }

    private void setTarget(ServerLevel sl, BlockPos pos) {
        target = pos;
        targetAge = 0;
        harvestTimer = 0;
        standPos = standBeside(sl, pos);
        moveTo(standPos != null ? standPos : pos);
    }

    private void clearTarget() {
        target = null;
        standPos = null;
        targetAge = 0;
        harvestTimer = 0;
    }

    private static BlockPos standBeside(Level level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = pos.relative(dir);
            if (WorkerPathing.isWalkable(level, adj)) return adj;
            if (WorkerPathing.isWalkable(level, adj.above())) return adj.above();
            if (WorkerPathing.isWalkable(level, adj.below())) return adj.below();
        }
        return null;
    }

    private List<ForageCategory> activeCategories() {
        Settlement settlement = citizen.getSettlement();
        int enabled = citizen.getForageTargetBits();
        List<ForageCategory> out = new ArrayList<>();
        for (ForageCategory cat : ForageCategory.values()) {
            if ((enabled & cat.bit()) != 0 && cat.isUnlocked(settlement)) out.add(cat);
        }
        return out;
    }

    private static boolean matchesAny(BlockState state, List<ForageCategory> active) {
        for (ForageCategory cat : active) if (cat.matches(state)) return true;
        return false;
    }

    private static ForageCategory categoryOf(BlockState state, List<ForageCategory> active) {
        for (ForageCategory cat : active) if (cat.matches(state)) return cat;
        return null;
    }

    private boolean isForageable(ServerLevel sl, BlockPos pos, List<ForageCategory> active) {
        return matchesAny(sl.getBlockState(pos), active);
    }

    private static boolean inForageBand(ServerLevel sl, Settlement settlement, BlockPos pos) {
        return inForageBandColumn(sl, settlement, pos.getX(), pos.getZ());
    }

    private static boolean inForageBandColumn(ServerLevel sl, Settlement settlement, int x, int z) {
        if (settlement == null) return false;
        int cx = x >> 4;
        int cz = z >> 4;
        if (SettlementData.get(sl).getByChunk(ChunkPos.asLong(cx, cz)) != null) return false;
        java.util.Set<Long> ours = settlement.claimedChunks();
        for (int dx = -FORAGE_BAND_CHUNKS; dx <= FORAGE_BAND_CHUNKS; dx++) {
            for (int dz = -FORAGE_BAND_CHUNKS; dz <= FORAGE_BAND_CHUNKS; dz++) {
                if (ours.contains(ChunkPos.asLong(cx + dx, cz + dz))) return true;
            }
        }
        return false;
    }

    private static BlockPos lowerHalf(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof DoublePlantBlock
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        return pos;
    }

    private void moveTo(BlockPos p) {
        if (p == null) return;
        citizen.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, skilledSpeed());
    }

    private void playSwing(ServerLevel level, BlockPos pos) {
        level.getChunkSource().broadcastAndSend(citizen,
            new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0));
        BlockState state = level.getBlockState(pos);
        SoundType st = state.getSoundType();
        level.playSound(null, pos, st.getHitSound(), SoundSource.BLOCKS, st.getVolume() * 0.4f, st.getPitch());
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.25, 0.25, 0.25, 0.0);
    }
}
