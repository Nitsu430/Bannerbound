package com.bannerbound.core.entity;

import com.bannerbound.core.api.entity.ForesterTreeRegistry;
import com.bannerbound.core.api.research.ResearchManager;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Forester gatherer - the original GathererWorkGoal. Loop: SCAN for a log -> walk to a stand pos
 * at the trunk base -> fake-chop with sound + client swing + particles -> fell the whole tree ->
 * route drops into the marked drop-off (or the anarchy carry pack) -> spend stamina (one point
 * per log felled). Yield gates that drop back to patrol: no job/tool/drop-off, unresolved depot,
 * exhausted stamina, no tree-that-fits in range. There is no "idle at station" middle state.
 *
 * Target selection (findNearestTree): all logs in a SEARCH_RADIUS x SEARCH_HEIGHT box are grouped
 * into connected trees via a 26-neighbor flood fill (diagonal adjacency is REQUIRED so fancy-oak
 * branches don't split into separate "trees"; touching canopies may fuse, which is harmless).
 * Trees are ranked distance-first from the CITIZEN (not the depot, so post-fell walks stay short);
 * preferred-species and frontier (unclaimed-land) preferences are small bounded nudges in real
 * block units, never absolute overrides - the forester never treks past a near tree for a far
 * "perfect" one. A tree is rejected if cobblestone-protected (a marker under any log protects the
 * whole trunk), claimed by another forester, or - unless the settlement has the Lumberjacking flag
 * (FLAG_LARGE_TREE_CUTTING) - mega-sized: over MEGA_TREE_THRESHOLD logs, a 2x2 trunk footprint, or
 * a 2x2 trunk found by scanning the seed's column. Target = the LOWEST log so the worker snaps to
 * the base instead of chopping mid-trunk. The whole tree is claimed via ForesterTreeRegistry
 * before committing (retry-soon on a lost race); the claim is released on stop/fell/abandon.
 *
 * Chop pacing: the base budget comes from the HELD axe's tool age (getByTool on the item, NOT the
 * settlement's best unlocked age), so a bone axe chops at bone speed; it is then scaled by the
 * anarchy no-tool factor, tool quality, and forester XP, and split into ~3 swings. Trees over
 * MEGA_TREE_THRESHOLD earn one extra swing interval per LARGE_TREE_INTERVAL_LOGS above it.
 *
 * Felling: prefers Pandas Falling Trees when installed (real container depots only - PFT's delayed
 * ground drops can't be funnelled into an anarchy carry pack, so that mode forces the in-house
 * path). PFT chop opens a CAPTURE_WINDOW so its trajectory drops are siphoned to the depot; the
 * in-house path flood-breaks every log and a 5x5x3 leaf box in one tick, routing drops directly
 * (logs must ALL fit per the per-tree fit check - clutter may spill; logs spill only on a lost
 * race). If the settlement has foresters_replant, a strict same-species sapling is placed at the
 * trunk (from depot then stockpile) when dirt is below and the spot is replaceable.
 *
 * JOB_TYPE_ID is the stable forester job id (workstation block is gone); SELECTION_TYPE
 * "forester_farm" is the Foreman's Rod plantation order - a forester bound to one yields to
 * ForesterPlantationGoal. Server-thread only. Open: lift rescan/target-timeout/phase skeleton
 * into GathererWorkGoal once a second gatherer's quirks settle.
 */
@ApiStatus.Internal
public class ForesterWorkGoal extends GathererWorkGoal {
    private static final int SEARCH_RADIUS = 64;
    private static final int SEARCH_HEIGHT = 16;
    private static final double HORIZONTAL_REACH_SQ = 4.5 * 4.5;
    private static final double VERTICAL_REACH = 12.0;
    private static final int MAX_TREE_SIZE = 200;
    private static final int MEGA_TREE_THRESHOLD = 60;
    private static final int DEFAULT_CHOP_TICKS = 80;
    private static final String FLAG_LARGE_TREE_CUTTING = "bannerbound.large_tree_cutting";
    private static final int LARGE_TREE_INTERVAL_LOGS = 2;
    private static final int RESCAN_COOLDOWN_TICKS = 60;
    private static final int TARGET_TIMEOUT_TICKS = 200;
    private static final int CAPTURE_WINDOW_TICKS = 200;

    private enum Phase { SCAN, CHOP }

    private Phase phase = Phase.SCAN;
    private BlockPos targetLog;
    private BlockPos standPos;
    private int chopTimer;
    private int rescanCooldown;
    private int targetAge;
    private Set<BlockPos> currentTreeLogs;

    public static final String JOB_TYPE_ID = "foresters_log";
    public static final String SELECTION_TYPE = "forester_farm";

    public ForesterWorkGoal(CitizenEntity citizen, double speedModifier) {
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
        if (ForesterPlantationGoal.hasPlantationOrder(citizen)) return false;
        citizen.validateJobStorage();
        if (!citizen.isForesterReady()) return false;
        Container depot = resolveDepot();
        if (depot == null) return false;

        if (targetLog != null && isLog(citizen.level(), targetLog)) {
            phase = Phase.CHOP;
            return true;
        }

        if (currentTreeLogs != null) {
            currentTreeLogs.removeIf(p -> !isLog(citizen.level(), p));
            if (!currentTreeLogs.isEmpty() && isTreeProtected(citizen.level(), currentTreeLogs)) {
                currentTreeLogs = null;
            }
            if (currentTreeLogs != null && !currentTreeLogs.isEmpty()) {
                BlockPos next = lowestLog(currentTreeLogs);
                targetLog = next;
                standPos = findChopStandPos(citizen.level(), next);
                targetAge = 0;
                phase = Phase.CHOP;
                return true;
            }
            currentTreeLogs = null;
        }

        if (rescanCooldown-- > 0) return false;
        TreePick pick = findNearestTree(citizen.level(), citizen.blockPosition(),
            citizen.getSettlement(), citizen, depot);
        if (pick == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        // Claim the whole tree before committing; a lost race retries soon on a different tree.
        if (!ForesterTreeRegistry.tryClaim(citizen.getUUID(), pick.tree())) {
            rescanCooldown = 5;
            return false;
        }
        targetLog = pick.log();
        currentTreeLogs = new HashSet<>(pick.tree());
        standPos = findChopStandPos(citizen.level(), targetLog);
        targetAge = 0;
        phase = Phase.CHOP;
        return true;
    }

    private void abandonTarget() {
        if (targetLog != null) {
            citizen.broadcastCannotReach(targetLog);
        }
        if (currentTreeLogs != null && targetLog != null) {
            currentTreeLogs.remove(targetLog);
        }
        ForesterTreeRegistry.release(citizen.getUUID());
        currentTreeLogs = null;
        targetLog = null;
        standPos = null;
        targetAge = 0;
        chopTimer = 0;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
        phase = Phase.SCAN;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isForesterReady()) return false;
        if (resolveDepot() == null) return false;
        return targetLog != null && isLog(citizen.level(), targetLog);
    }

    @Override
    public void start() {
        chopTimer = 0;
        citizen.setWorking(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            citizen.getJobTool().copy());
        if (phase == Phase.CHOP && standPos != null) {
            citizen.getNavigation().moveTo(
                standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, skilledSpeed());
        }
    }

    @Override
    public void tick() {
        if (!citizen.isForesterReady()) return;
        tickChop();
    }

    private void tickChop() {
        if (targetLog == null) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) {
            abandonTarget();
            return;
        }
        double dx = (targetLog.getX() + 0.5) - citizen.getX();
        double dz = (targetLog.getZ() + 0.5) - citizen.getZ();
        double horizSq = dx * dx + dz * dz;
        double dy = Math.abs((targetLog.getY() + 0.5) - citizen.getY());

        citizen.getLookControl().setLookAt(
            targetLog.getX() + 0.5, targetLog.getY() + 0.5, targetLog.getZ() + 0.5);

        if (horizSq <= HORIZONTAL_REACH_SQ && dy <= VERTICAL_REACH) {
            citizen.getNavigation().stop();
            chopTimer++;
            com.bannerbound.core.api.research.ToolAge toolAge =
                com.bannerbound.core.api.research.data.ToolAgeLoader.getByTool("axe", citizen.getJobTool().getItem());
            int baseBudget = toolAge != null ? toolAge.chopTicks().orElse(DEFAULT_CHOP_TICKS) : DEFAULT_CHOP_TICKS;
            baseBudget = (int) Math.round(baseBudget * citizen.anarchyWorkSpeedFactor());
            baseBudget = com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(
                citizen.getJobTool(), baseBudget);
            baseBudget = skilledWorkTicks(baseBudget);
            int interval = Math.max(1, baseBudget / 3);
            int treeSize = currentTreeLogs != null ? currentTreeLogs.size() : 0;
            int chopBudget = baseBudget;
            if (treeSize > MEGA_TREE_THRESHOLD) {
                int extraSwings = (treeSize - MEGA_TREE_THRESHOLD) / LARGE_TREE_INTERVAL_LOGS;
                chopBudget += extraSwings * interval;
            }
            if (chopTimer % interval == 0 && citizen.level() instanceof ServerLevel sl) {
                playSwing(sl, targetLog);
            }
            if (chopTimer >= chopBudget) {
                chopLog();
                chopTimer = 0;
            }
        } else {
            if (citizen.getNavigation().isDone() && standPos != null) {
                citizen.getNavigation().moveTo(
                    standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, skilledSpeed());
            }
            chopTimer = 0;
        }
    }

    private void playSwing(ServerLevel level, BlockPos log) {
        // Swing is driven client-side: each tracking client calls swing(MAIN_HAND) on this packet;
        // server-side swing pokes are redundant and unread. Don't add them back.
        net.minecraft.network.protocol.game.ClientboundAnimatePacket packet =
            new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0);
        level.getChunkSource().broadcastAndSend(citizen, packet);
        BlockState state = level.getBlockState(log);
        SoundType st = state.getSoundType();
        level.playSound(null, log,
            st.getHitSound(), SoundSource.BLOCKS, st.getVolume() * 0.5f, st.getPitch());
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            log.getX() + 0.5, log.getY() + 0.5, log.getZ() + 0.5,
            4, 0.3, 0.3, 0.3, 0.0);
    }

    private void chopLog() {
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!isLog(level, targetLog)) {
            abandonTarget();
            return;
        }
        Set<BlockPos> tree = collectConnectedTree(level, targetLog);
        if (isTreeProtected(level, tree)) {
            abandonTarget();
            return;
        }

        Container depot = resolveDepot();

        BlockPos felledAt = targetLog;
        // Sample the trunk species before the block is destroyed so replant can match it.
        net.minecraft.world.level.block.Block seedBlock = level.getBlockState(felledAt).getBlock();
        // Re-vet depot room: PFT delayed drops since canStartWork may have eaten the slot -> spill.
        if (depot != null && seedBlock != net.minecraft.world.level.block.Blocks.AIR) {
            net.minecraft.world.item.Item logItem = seedBlock.asItem();
            if (logItem != net.minecraft.world.item.Items.AIR
                    && DropOffContainers.roomFor(depot, new ItemStack(logItem)) < tree.size()) {
                abandonTarget();
                return;
            }
        }
        // Anarchy carry pack can't receive PFT's ground drops; force the in-house fell for it.
        boolean handledByPFT = depot != citizen.getAnarchyHaul()
            && com.bannerbound.core.compat.FallingTreesCompat.fellTree(
                serverLevel, felledAt, citizen.getX(), citizen.getY(), citizen.getZ());
        if (!handledByPFT) {
            fellTreeAndRouteToDepot(citizen, serverLevel, tree, depot);
        } else if (depot != null) {
            citizen.beginCaptureWindow(felledAt, CAPTURE_WINDOW_TICKS);
        }

        Settlement settlement = citizen.getSettlement();
        if (settlement != null && depot != null
                && com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, "bannerbound.foresters_replant")) {
            tryReplant(serverLevel, felledAt, seedBlock, depot);
        }

        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "wood");
        citizen.consumeStamina(Math.max(1, tree.size()));
        ForesterTreeRegistry.release(citizen.getUUID());
        currentTreeLogs = null;
        targetLog = null;
        standPos = null;
        targetAge = 0;
        phase = Phase.SCAN;
    }

    private void tryReplant(ServerLevel level, BlockPos felledAt,
                            net.minecraft.world.level.block.Block seedBlock,
                            Container depot) {
        net.minecraft.resources.ResourceLocation logId =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(seedBlock);
        if (logId == null) return;
        String saplingPath = logId.getPath().replace("_log", "_sapling");
        if (saplingPath.equals(logId.getPath())) return;
        net.minecraft.resources.ResourceLocation saplingId =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(logId.getNamespace(), saplingPath);
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(saplingId)) return;
        net.minecraft.world.level.block.Block sapling =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(saplingId);
        net.minecraft.world.item.Item saplingItem = sapling.asItem();
        if (saplingItem == net.minecraft.world.item.Items.AIR) return;

        int matchSlot = -1;
        for (int i = 0; i < depot.getContainerSize(); i++) {
            ItemStack s = depot.getItem(i);
            if (!s.isEmpty() && s.is(saplingItem)) { matchSlot = i; break; }
        }
        com.bannerbound.core.api.settlement.Settlement settlement = citizen.getSettlement();
        boolean fromStockpile = matchSlot < 0;
        if (fromStockpile && (settlement == null
                || com.bannerbound.core.stockpile.StockpileService.count(level, settlement, saplingItem) <= 0)) {
            return;
        }

        if (!level.getBlockState(felledAt.below()).is(BlockTags.DIRT)) return;

        BlockState here = level.getBlockState(felledAt);
        if (!here.isAir() && !here.canBeReplaced()) return;

        level.setBlock(felledAt, sapling.defaultBlockState(), 3);
        if (fromStockpile) {
            com.bannerbound.core.stockpile.StockpileService.withdraw(level, settlement, saplingItem, 1);
        } else {
            depot.getItem(matchSlot).shrink(1);
            depot.setChanged();
        }
    }

    private static final int LEAF_CUT_RADIUS = 2;
    private static final int LEAF_CUT_HEIGHT = 1;

    static void fellTreeAndRouteToDepot(CitizenEntity citizen, ServerLevel level,
                                        Set<BlockPos> tree, Container depot) {
        for (BlockPos p : tree) {
            BlockState state = level.getBlockState(p);
            if (!state.is(BlockTags.LOGS)) continue;
            List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                state, level, p, null);
            com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
            // false = we already feed getDrops ourselves; true would double-drop.
            level.destroyBlock(p, false, citizen);
            for (ItemStack drop : drops) {
                if (drop.isEmpty()) continue;
                if (depot == null) {
                    citizen.spawnAtLocation(drop);
                    continue;
                }
                ItemStack leftover = DropOffContainers.insert(depot, drop);
                // Only logs spill (racing drop ate a slot); clutter overflow is discarded.
                if (!leftover.isEmpty() && leftover.is(state.getBlock().asItem())) {
                    citizen.spawnAtLocation(leftover);
                }
            }
        }
        cutCanopyLeaves(citizen, level, tree, depot);
    }

    static void cutCanopyLeaves(CitizenEntity citizen, ServerLevel level,
                                Set<BlockPos> tree, Container depot) {
        BlockPos top = highestLog(tree);
        if (top == null) return;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int dx = -LEAF_CUT_RADIUS; dx <= LEAF_CUT_RADIUS; dx++) {
            for (int dz = -LEAF_CUT_RADIUS; dz <= LEAF_CUT_RADIUS; dz++) {
                for (int dy = -LEAF_CUT_HEIGHT; dy <= LEAF_CUT_HEIGHT; dy++) {
                    c.set(top.getX() + dx, top.getY() + dy, top.getZ() + dz);
                    BlockState state = level.getBlockState(c);
                    if (!state.is(BlockTags.LEAVES)) continue;
                    if (!citizen.foresterKeepsExtras()) {
                        level.destroyBlock(c, false, citizen);
                        continue;
                    }
                    List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                        state, level, c, null);
                    com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
                    level.destroyBlock(c, false, citizen);
                    for (ItemStack drop : drops) {
                        if (drop.isEmpty()) continue;
                        if (depot == null) {
                            citizen.spawnAtLocation(drop);
                        } else {
                            DropOffContainers.insert(depot, drop);
                        }
                    }
                }
            }
        }
    }

    private static BlockPos highestLog(Set<BlockPos> tree) {
        BlockPos high = null;
        int highestY = Integer.MIN_VALUE;
        for (BlockPos p : tree) {
            if (p.getY() > highestY) {
                highestY = p.getY();
                high = p;
            }
        }
        return high;
    }

    @Override
    public void stop() {
        chopTimer = 0;
        citizen.setWorking(false);
        ForesterTreeRegistry.release(citizen.getUUID());
        currentTreeLogs = null;
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.item.ItemStack.EMPTY);
    }

    static boolean isLog(Level level, BlockPos pos) {
        if (pos == null) return false;
        return level.getBlockState(pos).is(BlockTags.LOGS);
    }

    static BlockPos findChopStandPos(Level level, BlockPos log) {
        int x = log.getX();
        int z = log.getZ();
        int groundY = log.getY();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int y = log.getY() - 1; y >= log.getY() - 32; y--) {
            c.set(x, y, z);
            BlockState s = level.getBlockState(c);
            if (s.is(BlockTags.LOGS)) continue;
            if (s.isAir()) continue;
            if (s.isSolid()) {
                groundY = y + 1;
                break;
            }
        }
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adj = new BlockPos(x + dir.getStepX(), groundY, z + dir.getStepZ());
            BlockPos floor = adj.below();
            if (WorkerPathing.isPassable(level, adj)
                    && WorkerPathing.hasFloor(level, floor)
                    && !level.getBlockState(floor).is(BlockTags.LOGS)) {
                return adj;
            }
        }
        return new BlockPos(x, groundY, z);
    }

    private record TreePick(BlockPos log, Set<BlockPos> tree) {}

    private static TreePick findNearestTree(Level level, BlockPos origin, Settlement settlement,
                                            CitizenEntity citizen, Container depot) {
        boolean allowLarge = settlement != null
            && ResearchManager.hasFlag(settlement, FLAG_LARGE_TREE_CUTTING);
        Set<BlockPos> allLogs = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -SEARCH_HEIGHT; dy <= SEARCH_HEIGHT; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.getBlockState(cursor).is(BlockTags.LOGS)) {
                        allLogs.add(cursor.immutable());
                    }
                }
            }
        }
        if (allLogs.isEmpty()) return null;

        final double PREF_NUDGE = 20.0;
        final double FRONTIER_NUDGE = 12.0;
        net.minecraft.world.level.block.Block preferredLog = citizen.getPreferredLog();

        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> chosen = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos seed : allLogs) {
            if (visited.contains(seed)) continue;
            Set<BlockPos> tree = floodFillTree(level, seed, allLogs);
            visited.addAll(tree);
            if (isTreeProtected(level, tree)) continue;
            if (!allowLarge && tree.size() > MEGA_TREE_THRESHOLD) continue;
            if (!allowLarge && hasTwoByTwoTrunk(tree)) continue;
            if (!allowLarge && hasMegaTrunkInColumn(level, seed)) continue;
            if (ForesterTreeRegistry.isAnyLogClaimedByOther(tree, citizen.getUUID())) continue;
            if (depot != null) {
                net.minecraft.world.item.Item logItem = level.getBlockState(seed).getBlock().asItem();
                if (logItem != net.minecraft.world.item.Items.AIR
                        && DropOffContainers.roomFor(depot, new net.minecraft.world.item.ItemStack(logItem)) < tree.size()) {
                    continue;
                }
            }

            boolean matchesPref = preferredLog != null && level.getBlockState(seed).is(preferredLog);
            double minDistSq = Double.MAX_VALUE;
            boolean anyOutsideClaim = settlement == null;
            for (BlockPos p : tree) {
                double d = origin.distSqr(p);
                if (d < minDistSq) minDistSq = d;
                if (settlement != null && !settlement.claimedChunks().contains(new ChunkPos(p).toLong())) {
                    anyOutsideClaim = true;
                }
            }
            double score = Math.sqrt(minDistSq)
                + (matchesPref ? 0.0 : PREF_NUDGE)
                + (anyOutsideClaim ? 0.0 : FRONTIER_NUDGE);
            if (score < bestScore) {
                bestScore = score;
                chosen = tree;
            }
        }
        if (chosen == null) return null;
        return new TreePick(lowestLog(chosen), chosen);
    }

    private static BlockPos lowestLog(Set<BlockPos> tree) {
        BlockPos low = null;
        int lowestY = Integer.MAX_VALUE;
        for (BlockPos p : tree) {
            if (p.getY() < lowestY) {
                lowestY = p.getY();
                low = p;
            }
        }
        return low;
    }

    static Set<BlockPos> collectConnectedTree(Level level, BlockPos seed) {
        Set<BlockPos> tree = new HashSet<>();
        if (seed == null) return tree;
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(seed);
        tree.add(seed);
        while (!q.isEmpty() && tree.size() < MAX_TREE_SIZE) {
            BlockPos p = q.poll();
            for (BlockPos n : neighbors26(p)) {
                if (tree.contains(n)) continue;
                if (!level.getBlockState(n).is(BlockTags.LOGS)) continue;
                tree.add(n);
                q.add(n);
            }
        }
        return tree;
    }

    private static Set<BlockPos> floodFillTree(Level level, BlockPos seed, Set<BlockPos> candidates) {
        Set<BlockPos> tree = new HashSet<>();
        Deque<BlockPos> q = new ArrayDeque<>();
        q.add(seed);
        tree.add(seed);
        while (!q.isEmpty() && tree.size() < MAX_TREE_SIZE) {
            BlockPos p = q.poll();
            for (BlockPos n : neighbors26(p)) {
                if (tree.contains(n)) continue;
                if (!candidates.contains(n)) continue;
                tree.add(n);
                q.add(n);
            }
        }
        return tree;
    }

    private static Iterable<BlockPos> neighbors26(BlockPos p) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    out.add(p.offset(dx, dy, dz));
                }
            }
        }
        return out;
    }

    private static boolean hasTwoByTwoTrunk(Set<BlockPos> tree) {
        for (BlockPos p : tree) {
            if (tree.contains(p.east())
                && tree.contains(p.south())
                && tree.contains(p.east().south())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMegaTrunkInColumn(Level level, BlockPos log) {
        int range = 32;
        int x = log.getX();
        int z = log.getZ();
        BlockPos.MutableBlockPos a = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos b = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos d = new BlockPos.MutableBlockPos();
        for (int dy = -range; dy <= range; dy++) {
            int y = log.getY() + dy;
            for (int xo = -1; xo <= 0; xo++) {
                for (int zo = -1; zo <= 0; zo++) {
                    a.set(x + xo, y, z + zo);
                    if (!level.getBlockState(a).is(BlockTags.LOGS)) continue;
                    b.set(a.getX() + 1, y, a.getZ());
                    c.set(a.getX(), y, a.getZ() + 1);
                    d.set(a.getX() + 1, y, a.getZ() + 1);
                    if (level.getBlockState(b).is(BlockTags.LOGS)
                        && level.getBlockState(c).is(BlockTags.LOGS)
                        && level.getBlockState(d).is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean isTreeProtected(Level level, Set<BlockPos> tree) {
        for (BlockPos p : tree) {
            if (level.getBlockState(p.below()).is(Blocks.COBBLESTONE)) {
                return true;
            }
        }
        return false;
    }
}
