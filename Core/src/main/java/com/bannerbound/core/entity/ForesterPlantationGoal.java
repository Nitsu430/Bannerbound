package com.bannerbound.core.entity;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Forester plantation goal -- the ordered counterpart to the free-roaming {@link ForesterWorkGoal}
 * gatherer. Once Silviculture research is done a player marks a rectangle with the Foreman's Rod
 * (type {@link ForesterWorkGoal#SELECTION_TYPE}); the bound forester plants a {@link #SPACING}-spaced
 * sapling grid across it, tends it, and harvests mature trees inside the plot only -- replanting on
 * the grid forever, like the farmer's permanent field.
 *
 * <p>Mode is implicit: a forester is "in plantation mode" iff such a selection targets it (see
 * {@link #hasPlantationOrder}); the gatherer yields whenever that is true so a managed grove is
 * never clear-cut, and a plantation forester works even under anarchy -- it supersedes the
 * anarchy-auto gatherer, so binding a plot must not make the forester less active than clear-cutting
 * would. While saplings grow the goal reports WAITING rather than roaming.
 *
 * <p>SPACING is 3 so trunks/canopies never merge into 2x2/mega trees; a tree reads as harvest-ready
 * at MIN_MATURE_LOGS contiguous trunk logs plus a canopy leaf (a fresh single log must not count).
 * Bone-mealing is gated on the Fertilization flag -- the same settlement upgrade that lets farmers
 * fertilize crops and that unlocks the bone-meal item; natural growth only until then. A scan commits
 * the nearest actionable cell with harvest > plant > bonemeal priority, capped at MAX_CELLS_PER_SCAN
 * cells so a maxed plot can't stall one tick. Felled trees are constrained to the plot (inflated 1
 * block horizontally for overhang) so a plantation never chains into a wild tree over the border.
 *
 * <p>Movement reuses the gatherer's reach model (walk to a stand tile, act on the cell within reach);
 * felling reuses {@link ForesterWorkGoal#fellTreeAndRouteToDepot}; sourcing reuses
 * {@link ForesterSupplies}.
 */
@ApiStatus.Internal
public class ForesterPlantationGoal extends OrderedWorkGoal {
    private static final int SPACING = 3;
    private static final int MIN_MATURE_LOGS = 4;
    private static final double HORIZONTAL_REACH_SQ = 4.5 * 4.5;
    private static final double VERTICAL_REACH = 12.0;
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    private static final int TARGET_TIMEOUT_TICKS = 200;
    private static final int PLANT_TICKS = 20;
    private static final int DEFAULT_CHOP_TICKS = 80;
    private static final String FLAG_FERTILIZING = "bannerbound.allow_fertilizing";
    private static final int MAX_CELLS_PER_SCAN = 4096;

    private enum Action { PLANT, HARVEST, BONEMEAL }
    private enum Cell { BLOCKED, EMPTY, SAPLING, GROWING_TREE, MATURE_TREE }

    private Action action;
    private BlockPos groundCell;
    private BlockPos standPos;
    private Item plantSpecies = Items.AIR;
    private int workTimer;
    private int targetAge;
    private int rescanCooldown;

    public ForesterPlantationGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return ForesterWorkGoal.JOB_TYPE_ID;
    }

    @Override
    protected boolean isAnarchyAutoEligible() {
        return true;
    }

    static boolean hasPlantationOrder(CitizenEntity c) {
        if (!(c.level() instanceof ServerLevel sl)) return false;
        Settlement s = c.getSettlement();
        if (s == null) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(s.id())) {
            if (sel.completed()) continue;
            if (!ForesterWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (sel.targetsCitizen(c.getUUID())) return true;
        }
        return false;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    @Override
    protected boolean canStartWork() {
        if (!(citizen.level() instanceof ServerLevel level)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        citizen.validateJobStorage();
        if (!citizen.isForesterReady()) return false;
        Container depot = resolveDepot();
        if (depot == null) return false;
        if (groundCell != null && targetStillValid(level)) return true;
        if (rescanCooldown-- > 0) return false;
        return findTarget(level, settlement, depot);
    }

    @Override
    protected boolean canKeepWorking() {
        if (!(citizen.level() instanceof ServerLevel level)) return false;
        if (!citizen.isForesterReady()) return false;
        if (resolveDepot() == null) return false;
        return groundCell != null && targetStillValid(level);
    }

    private boolean targetStillValid(ServerLevel level) {
        if (groundCell == null || action == null) return false;
        Cell c = classify(level, groundCell);
        return switch (action) {
            case PLANT -> c == Cell.EMPTY;
            case BONEMEAL -> c == Cell.SAPLING;
            case HARVEST -> c == Cell.MATURE_TREE;
        };
    }

    private boolean findTarget(ServerLevel level, Settlement settlement, Container depot) {
        boolean anyPlot = false;
        boolean anyEmptyNoSapling = false;
        boolean anyMatureNoRoom = false;
        boolean anyGrowing = false;

        BlockPos origin = citizen.blockPosition();
        Action bestAction = null;
        BlockPos bestGround = null;
        double bestDistSq = Double.MAX_VALUE;

        boolean bonemealEligible = ResearchManager.hasFlag(settlement, FLAG_FERTILIZING)
            && ForesterSupplies.hasOne(level, settlement, depot, Items.BONE_MEAL);
        Item species = ForesterSupplies.pickSpecies(citizen, level, settlement, depot);

        int visited = 0;
        for (BlockSelection sel : BlockSelectionRegistry.get(level).getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (!ForesterWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            anyPlot = true;
            for (int gx = sel.minX(); gx <= sel.maxX(); gx += SPACING) {
                for (int gz = sel.minZ(); gz <= sel.maxZ(); gz += SPACING) {
                    if (visited++ > MAX_CELLS_PER_SCAN) break;
                    BlockPos ground = resolveGround(level, sel, gx, gz);
                    if (ground == null) continue;
                    Cell c = classify(level, ground);
                    Action a = switch (c) {
                        case MATURE_TREE -> Action.HARVEST;
                        case EMPTY -> species != Items.AIR ? Action.PLANT : null;
                        case SAPLING -> bonemealEligible ? Action.BONEMEAL : null;
                        default -> null;
                    };
                    if (c == Cell.EMPTY && species == Items.AIR) anyEmptyNoSapling = true;
                    if (c == Cell.SAPLING || c == Cell.GROWING_TREE) anyGrowing = true;
                    if (c == Cell.MATURE_TREE && !harvestFits(level, ground, depot)) {
                        anyMatureNoRoom = true;
                        a = null;
                    }
                    if (a == null) continue;
                    double d = origin.distSqr(ground);
                    if (bestAction == null || priority(a) < priority(bestAction)
                            || (a == bestAction && d < bestDistSq)) {
                        bestAction = a;
                        bestGround = ground;
                        bestDistSq = d;
                    }
                }
            }
        }

        if (bestAction != null && bestGround != null) {
            action = bestAction;
            groundCell = bestGround;
            plantSpecies = species;
            standPos = ForesterWorkGoal.findChopStandPos(level, bestGround.above());
            targetAge = 0;
            workTimer = 0;
            citizen.setCurrentWorkStatus(switch (bestAction) {
                case HARVEST -> CitizenWorkStatus.HARVESTING;
                case PLANT, BONEMEAL -> CitizenWorkStatus.PLANTING;
            });
            return true;
        }

        rescanCooldown = RESCAN_COOLDOWN_TICKS;
        clearTarget();
        if (!anyPlot) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        } else if (anyEmptyNoSapling) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.NEEDS_SAPLINGS);
        } else if (anyMatureNoRoom && !anyGrowing) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.AREA_FULL);
        } else if (anyGrowing) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.WAITING);
        } else {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        }
        return false;
    }

    private static int priority(Action a) {
        return switch (a) {
            case HARVEST -> 0;
            case PLANT -> 1;
            case BONEMEAL -> 2;
        };
    }

    private static BlockPos resolveGround(ServerLevel level, BlockSelection sel, int gx, int gz) {
        for (int y = sel.maxY(); y >= sel.minY(); y--) {
            BlockPos p = new BlockPos(gx, y, gz);
            if (!level.isLoaded(p)) return null;
            if (level.getBlockState(p).is(BlockTags.DIRT)) return p;
        }
        return null;
    }

    private static Cell classify(ServerLevel level, BlockPos ground) {
        if (!level.getBlockState(ground).is(BlockTags.DIRT)) return Cell.BLOCKED;
        BlockPos above = ground.above();
        BlockState a = level.getBlockState(above);
        if (a.is(BlockTags.LOGS)) {
            return isMature(level, above) ? Cell.MATURE_TREE : Cell.GROWING_TREE;
        }
        if (a.is(BlockTags.SAPLINGS)) return Cell.SAPLING;
        if ((a.isAir() || a.canBeReplaced()) && a.getFluidState().isEmpty()) return Cell.EMPTY;
        return Cell.BLOCKED;
    }

    private static boolean isMature(ServerLevel level, BlockPos firstLog) {
        int logs = 0;
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        int y = firstLog.getY();
        while (logs < 32) {
            c.set(firstLog.getX(), y, firstLog.getZ());
            if (!level.getBlockState(c).is(BlockTags.LOGS)) break;
            logs++;
            y++;
        }
        if (logs < MIN_MATURE_LOGS) return false;
        int topY = firstLog.getY() + logs;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                c.set(firstLog.getX() + dx, topY, firstLog.getZ() + dz);
                if (level.getBlockState(c).is(BlockTags.LEAVES)) return true;
            }
        }
        return false;
    }

    private boolean harvestFits(ServerLevel level, BlockPos ground, Container depot) {
        if (depot == null) return false;
        BlockPos firstLog = ground.above();
        Item logItem = level.getBlockState(firstLog).getBlock().asItem();
        if (logItem == Items.AIR) return false;
        Set<BlockPos> tree = ForesterWorkGoal.collectConnectedTree(level, firstLog);
        return DropOffContainers.roomFor(depot, new ItemStack(logItem)) >= tree.size();
    }

    @Override
    public void start() {
        workTimer = 0;
        citizen.setWorking(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            citizen.getJobTool().copy());
        if (standPos != null) {
            citizen.getNavigation().moveTo(
                standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public void tick() {
        if (groundCell == null || action == null) return;
        if (!(citizen.level() instanceof ServerLevel level)) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) { abandonTarget(); return; }

        BlockPos work = groundCell.above();
        citizen.getLookControl().setLookAt(work.getX() + 0.5, work.getY() + 0.5, work.getZ() + 0.5);
        double dx = (work.getX() + 0.5) - citizen.getX();
        double dz = (work.getZ() + 0.5) - citizen.getZ();
        double horizSq = dx * dx + dz * dz;
        double dy = Math.abs((work.getY() + 0.5) - citizen.getY());

        if (horizSq <= HORIZONTAL_REACH_SQ && dy <= VERTICAL_REACH) {
            citizen.getNavigation().stop();
            workTimer++;
            int budget = action == Action.HARVEST ? chopBudget() : PLANT_TICKS;
            int interval = Math.max(1, budget / 3);
            if (workTimer % interval == 0) playSwing(level, work);
            if (workTimer >= budget) {
                performAction(level);
                workTimer = 0;
            }
        } else if (citizen.getNavigation().isDone() && standPos != null) {
            citizen.getNavigation().moveTo(
                standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, speedModifier);
        }
    }

    private int chopBudget() {
        ToolAge toolAge = ToolAgeLoader.getByTool("axe", citizen.getJobTool().getItem());
        int base = toolAge != null ? toolAge.chopTicks().orElse(DEFAULT_CHOP_TICKS) : DEFAULT_CHOP_TICKS;
        int budget = (int) Math.round(base * citizen.anarchyWorkSpeedFactor());
        return com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(citizen.getJobTool(), budget);
    }

    private void performAction(ServerLevel level) {
        if (groundCell == null || action == null) { abandonTarget(); return; }
        Settlement settlement = citizen.getSettlement();
        Container depot = resolveDepot();
        switch (action) {
            case PLANT -> doPlant(level, settlement, depot);
            case BONEMEAL -> doBonemeal(level, settlement, depot);
            case HARVEST -> doHarvest(level, settlement, depot);
        }
        clearTarget();
    }

    private void doPlant(ServerLevel level, Settlement settlement, Container depot) {
        BlockPos at = groundCell.above();
        if (classify(level, groundCell) != Cell.EMPTY) return;
        Item sapling = plantSpecies != Items.AIR
            ? plantSpecies : ForesterSupplies.pickSpecies(citizen, level, settlement, depot);
        if (sapling == Items.AIR) return;
        Block saplingBlock = ForesterSupplies.saplingBlock(sapling);
        if (saplingBlock == net.minecraft.world.level.block.Blocks.AIR) return;
        if (!ForesterSupplies.takeOne(level, settlement, depot, sapling)) return;
        level.setBlock(at, saplingBlock.defaultBlockState(), 3);
        SoundType st = saplingBlock.defaultBlockState().getSoundType();
        level.playSound(null, at, st.getPlaceSound(), SoundSource.BLOCKS, st.getVolume(), st.getPitch());
        citizen.consumeStamina(1);
    }

    private void doBonemeal(ServerLevel level, Settlement settlement, Container depot) {
        BlockPos at = groundCell.above();
        BlockState state = level.getBlockState(at);
        if (!state.is(BlockTags.SAPLINGS)) return;
        if (!(state.getBlock() instanceof BonemealableBlock bm)) return;
        if (!ForesterSupplies.takeOne(level, settlement, depot, Items.BONE_MEAL)) return;
        if (bm.isValidBonemealTarget(level, at, state) && bm.isBonemealSuccess(level, level.random, at, state)) {
            bm.performBonemeal(level, level.random, at, state);
        }
        level.levelEvent(1505, at, 0);   // vanilla bonemeal-particle event id
        citizen.consumeStamina(1);
    }

    private void doHarvest(ServerLevel level, Settlement settlement, Container depot) {
        BlockPos firstLog = groundCell.above();
        if (!ForesterWorkGoal.isLog(level, firstLog)) return;
        Set<BlockPos> tree = ForesterWorkGoal.collectConnectedTree(level, firstLog);
        tree = constrainToPlot(level, tree, firstLog);
        if (tree.isEmpty()) return;
        if (ForesterWorkGoal.isTreeProtected(level, tree)) return;
        net.minecraft.world.level.block.Block logBlock = level.getBlockState(firstLog).getBlock();
        if (depot != null) {
            Item logItem = logBlock.asItem();
            if (logItem != Items.AIR && DropOffContainers.roomFor(depot, new ItemStack(logItem)) < tree.size()) {
                return;
            }
        }
        ForesterWorkGoal.fellTreeAndRouteToDepot(citizen, level, tree, depot);
        Item sapling = ForesterSupplies.saplingForLog(logBlock);
        if (sapling == Items.AIR) sapling = ForesterSupplies.pickSpecies(citizen, level, settlement, depot);
        if (sapling != Items.AIR
                && level.getBlockState(firstLog).isAir()
                && level.getBlockState(groundCell).is(BlockTags.DIRT)
                && ForesterSupplies.takeOne(level, settlement, depot, sapling)) {
            level.setBlock(firstLog, ForesterSupplies.saplingBlock(sapling).defaultBlockState(), 3);
        }
        citizen.consumeStamina(Math.max(1, tree.size()));
    }

    private Set<BlockPos> constrainToPlot(ServerLevel level, Set<BlockPos> tree, BlockPos anchor) {
        BlockSelection plot = plotContaining(level, anchor);
        if (plot == null) return tree;
        Set<BlockPos> out = new HashSet<>();
        for (BlockPos p : tree) {
            if (p.getX() >= plot.minX() - 1 && p.getX() <= plot.maxX() + 1
                    && p.getZ() >= plot.minZ() - 1 && p.getZ() <= plot.maxZ() + 1) {
                out.add(p);
            }
        }
        return out;
    }

    private BlockSelection plotContaining(ServerLevel level, BlockPos anchor) {
        Settlement s = citizen.getSettlement();
        if (s == null) return null;
        for (BlockSelection sel : BlockSelectionRegistry.get(level).getForSettlement(s.id())) {
            if (sel.completed()) continue;
            if (!ForesterWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            // X/Z only: anchor.Y may sit just above the box top, so a Y bounds check would drop valid cells.
            if (anchor.getX() >= sel.minX() && anchor.getX() <= sel.maxX()
                    && anchor.getZ() >= sel.minZ() && anchor.getZ() <= sel.maxZ()) {
                return sel;
            }
        }
        return null;
    }

    private void playSwing(ServerLevel level, BlockPos at) {
        net.minecraft.network.protocol.game.ClientboundAnimatePacket packet =
            new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0);
        level.getChunkSource().broadcastAndSend(citizen, packet);
        BlockState state = level.getBlockState(at);
        SoundType st = state.getSoundType();
        level.playSound(null, at, st.getHitSound(), SoundSource.BLOCKS,
            st.getVolume() * 0.4f, st.getPitch());
    }

    private void abandonTarget() {
        clearTarget();
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    private void clearTarget() {
        action = null;
        groundCell = null;
        standPos = null;
        targetAge = 0;
        workTimer = 0;
    }

    @Override
    public void stop() {
        clearTarget();
        citizen.setWorking(false);
        citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }
}
