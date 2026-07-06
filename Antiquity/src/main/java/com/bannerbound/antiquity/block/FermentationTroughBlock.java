package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Fermentation Trough: a hollowed-log vessel for the earliest alcohol (grog), carved in place from a
 * log with a bone knife once the civ knows the Fermentation research. One block class per wood (see
 * BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD). Right-click a water bucket to fill it; the client
 * FermentationTroughRenderer draws the liquid surface and, once fermenting, the bubbling.
 *
 * <p>Adjacent troughs of the same wood + facing connect along the facing's clockwise axis into a longer
 * run (cosmetic, chest-style: each block keeps its own block entity and its own liquid, like the Drying
 * Rack). LEFT = a connecting trough on the clockwise side (the open connected_left end); RIGHT = one on
 * the counter-clockwise side. A connected POOL shares one liquid and one ferment batch but caps at
 * MAX_RUN (3) cells; a physical line longer than 3 is split into back-to-back pools of <= 3, grouped
 * from the counter-clockwise end. Because inserting a trough anywhere shifts that grouping, onPlace and
 * onRemove rewrite the ENTIRE contiguous line (refreshLine), not just the touched cell, then re-sync
 * each pool's ferment state (consolidatePool). refreshLine writes with UPDATE_CLIENTS (no neighbour
 * notify) so it does not re-enter via shape updates. Pool capacity = cells x UNITS_PER_CELL, and
 * runFraction reports a single shared fill so every cell renders its surface at the same level.
 *
 * <p>Water enters by bucket (one cell's worth), by empty-hand scoop from water within the 3x3x3, or by
 * rain; a fermentable item charges the pool, and warmth (fire/campfire/lava/magma in the 3x3x3) cuts
 * the ferment to 60%. Finished grog is drunk straight from the trough (empty hand) or poured into a
 * mug/horn; draining the last unit clears the batch. The static helpers here (npcAddWater, npcCharge,
 * findScoopWater, poolCapacity, hasReadyServing, takeServing) are the seams the BrewerExecutor and the
 * citizen TavernGoal drive; a charged pool refuses more water so a fermenting batch is never diluted.
 */
public class FermentationTroughBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<FermentationTroughBlock> CODEC = simpleCodec(FermentationTroughBlock::new);
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    private static final VoxelShape SHAPE = Block.box(0, 0, 1, 16, 8, 15);

    public FermentationTroughBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH).setValue(LEFT, false).setValue(RIGHT, false));
    }

    @Override
    protected MapCodec<FermentationTroughBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEFT, RIGHT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    private boolean isPartner(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() == this && s.getValue(FACING) == facing;
    }

    public static final int MAX_RUN = 3;

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!oldState.is(state.getBlock()) && !level.isClientSide) {
            refreshLine(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        boolean removed = !oldState.is(newState.getBlock());
        super.onRemove(oldState, level, pos, newState, moved);
        if (removed && !level.isClientSide && oldState.getBlock() instanceof FermentationTroughBlock) {
            Direction facing = oldState.getValue(FACING);
            refreshLine(level, pos.relative(facing.getClockWise()));
            refreshLine(level, pos.relative(facing.getCounterClockWise()));
        }
    }

    public static void refreshLine(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FermentationTroughBlock self)) return;
        Direction facing = state.getValue(FACING);
        Direction cw = facing.getClockWise();
        Direction ccw = facing.getCounterClockWise();

        BlockPos start = pos;
        while (self.isPartner(level, start.relative(ccw), facing)) start = start.relative(ccw);

        BlockPos p = start;
        int offset = 0;
        while (self.isPartner(level, p, facing)) {
            int idx = offset % MAX_RUN;
            boolean cwPartner = self.isPartner(level, p.relative(cw), facing);
            boolean left;
            boolean right;
            if (idx == 0)      { left = cwPartner; right = false; }
            else if (idx == 1) { left = cwPartner; right = true; }
            else               { left = false;     right = true; }
            BlockState cur = level.getBlockState(p);
            BlockState updated = cur.setValue(LEFT, left).setValue(RIGHT, right);
            if (updated != cur) level.setBlock(p, updated, Block.UPDATE_CLIENTS); // no neighbour notify -> no shape-update re-entry
            p = p.relative(cw);
            offset++;
        }

        BlockPos q = start;
        while (self.isPartner(level, q, facing)) {
            if (!level.getBlockState(q).getValue(RIGHT)) consolidatePool(level, q);
            q = q.relative(cw);
        }
    }

    private static void consolidatePool(Level level, BlockPos poolStart) {
        java.util.List<BlockPos> cells = runCells(level, poolStart);
        String id = "";
        long start = 0L;
        int ticks = 0;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.isCharged()) {
                id = be.grogRecipeId();
                start = be.fermentStart();
                ticks = be.fermentTicks();
                break;
            }
        }
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                be.setFerment(id, start, ticks);
            }
        }
    }

    public static java.util.List<BlockPos> runCells(BlockGetter level, BlockPos pos) {
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FermentationTroughBlock self)) return cells;
        Direction facing = state.getValue(FACING);
        cells.add(pos);
        BlockPos cur = pos;
        BlockState cs = state;
        while (cs.getValue(LEFT)) {
            BlockPos n = cur.relative(facing.getClockWise());
            if (!self.isPartner(level, n, facing)) break;
            cells.add(n);
            cur = n;
            cs = level.getBlockState(n);
        }
        cur = pos;
        cs = state;
        while (cs.getValue(RIGHT)) {
            BlockPos n = cur.relative(facing.getCounterClockWise());
            if (!self.isPartner(level, n, facing)) break;
            cells.add(n);
            cur = n;
            cs = level.getBlockState(n);
        }
        return cells;
    }

    public static float runFraction(BlockGetter level, BlockPos pos) {
        java.util.List<BlockPos> cells = runCells(level, pos);
        if (cells.isEmpty()) return 0.0F;
        int total = 0;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) total += be.units();
        }
        int cap = cells.size() * FermentationTroughBlockEntity.UNITS_PER_CELL;
        return cap <= 0 ? 0.0F : Math.min(1.0F, total / (float) cap);
    }

    private static boolean addWaterToRun(Level level, BlockPos pos, int units) {
        int remaining = units;
        for (BlockPos c : runCells(level, pos)) {
            if (remaining <= 0) break;
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                remaining -= be.addUnits(remaining);
            }
        }
        return remaining < units;
    }

    private static boolean chargePool(Level level, BlockPos pos, String recipeId,
                                      com.bannerbound.antiquity.recipe.GrogRecipe recipe) {
        java.util.List<BlockPos> cells = runCells(level, pos);
        int water = 0;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                if (be.isCharged()) return false;
                water += be.units();
            }
        }
        if (water < recipe.minWaterUnits()) return false;
        int ticks = fermentTicksWithWarmth(level, pos, recipe.fermentTicks());
        long now = level.getGameTime();
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                be.charge(recipeId, now, ticks);
            }
        }
        return true;
    }

    private static int fermentTicksWithWarmth(Level level, BlockPos pos, int baseTicks) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            BlockState s = level.getBlockState(p);
            boolean hot = s.is(net.minecraft.world.level.block.Blocks.FIRE)
                || s.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || s.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)
                || (s.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock
                    && s.getValue(net.minecraft.world.level.block.CampfireBlock.LIT));
            if (hot) return Math.max(1, (int) (baseTicks * 0.6F));
        }
        return baseTicks;
    }

    public static int poolCapacity(Level level, BlockPos pos) {
        return runCells(level, pos).size() * FermentationTroughBlockEntity.UNITS_PER_CELL;
    }

    public static boolean npcAddWater(Level level, BlockPos pos, int units) {
        if (isPoolCharged(level, pos)) return false;
        return addWaterToRun(level, pos, units);
    }

    public static boolean npcCharge(Level level, BlockPos pos, net.minecraft.world.item.Item input) {
        java.util.Map.Entry<net.minecraft.resources.ResourceLocation,
            com.bannerbound.antiquity.recipe.GrogRecipe> match =
            com.bannerbound.antiquity.recipe.GrogRecipeManager.findForInput(input);
        return match != null && chargePool(level, pos, match.getKey().toString(), match.getValue());
    }

    @Nullable
    public static BlockPos findScoopWater(Level level, BlockPos pos) {
        for (BlockPos cell : runCells(level, pos)) {
            for (BlockPos p : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                    return cell.immutable();
                }
            }
        }
        return null;
    }

    public static int poolUnits(Level level, BlockPos pos) {
        int total = 0;
        for (BlockPos c : runCells(level, pos)) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) total += be.units();
        }
        return total;
    }

    public static boolean isPoolCharged(Level level, BlockPos pos) {
        for (BlockPos c : runCells(level, pos)) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.isCharged()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static com.bannerbound.antiquity.recipe.GrogRecipe readyGrog(Level level, BlockPos pos) {
        long now = level.getGameTime();
        for (BlockPos c : runCells(level, pos)) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.grogReady(now)) {
                return com.bannerbound.antiquity.recipe.GrogRecipeManager.byId(be.grogRecipeId());
            }
        }
        return null;
    }

    private static void drainServing(Level level, BlockPos pos) {
        java.util.List<BlockPos> cells = runCells(level, pos);
        boolean drained = false;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.removeUnit()) {
                drained = true;
                break;
            }
        }
        if (drained && poolUnits(level, pos) <= 0) {
            for (BlockPos c : cells) {
                if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                    be.setFerment("", 0L, 0);
                }
            }
        }
    }

    public static boolean hasReadyServing(Level level, BlockPos pos) {
        return readyGrog(level, pos) != null && poolUnits(level, pos) > 0;
    }

    public static boolean takeServing(Level level, BlockPos pos) {
        if (!hasReadyServing(level, pos)) return false;
        drainServing(level, pos);
        return true;
    }

    private static void fillVessel(Player player, InteractionHand hand, ItemStack stack,
                                   com.bannerbound.antiquity.recipe.GrogRecipe grog) {
        ItemStack filled = new ItemStack(stack.getItem());
        filled.set(BannerboundAntiquity.GROG_CONTENTS.get(), new com.bannerbound.antiquity.item.GrogContents(
            grog.name(), grog.tint(), grog.strength(), grog.foodValue(), grog.effects()));
        if (player.hasInfiniteMaterials()) {
            if (!player.getInventory().add(filled)) player.drop(filled, false);
        } else if (stack.getCount() == 1) {
            player.setItemInHand(hand, filled);
        } else {
            stack.shrink(1);
            if (!player.getInventory().add(filled)) player.drop(filled, false);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FermentationTroughBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == BannerboundAntiquity.FERMENTATION_TROUGH_BE.get()
            ? (lvl, pos, st, be) -> FermentationTroughBlockEntity.serverTick(
                lvl, pos, st, (FermentationTroughBlockEntity) be)
            : null;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos,
                            net.minecraft.util.RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof FermentationTroughBlockEntity be)
                || !be.fermenting(level.getGameTime())) {
            return;
        }
        double surfaceY = pos.getY() + (2.0 + runFraction(level, pos) * 4.5) / 16.0 + 0.02;
        if (random.nextInt(2) == 0) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE_POP,
                pos.getX() + 0.25 + random.nextDouble() * 0.5, surfaceY,
                pos.getZ() + 0.25 + random.nextDouble() * 0.5, 0.0, 0.0, 0.0);
        }
        if (random.nextInt(50) == 0) {
            level.playLocalSound(pos.getX() + 0.5, surfaceY, pos.getZ() + 0.5,
                SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS,
                0.4F, 0.7F + random.nextFloat() * 0.3F, false);
        }
    }

    @Nullable
    private static FermentationTroughBlockEntity trough(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FermentationTroughBlockEntity be ? be : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        FermentationTroughBlockEntity be = trough(level, pos);
        if (be == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        boolean vanillaWater = stack.is(Items.WATER_BUCKET);
        boolean clayWater = stack.is(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
        if ((vanillaWater || clayWater) && !isPoolCharged(level, pos)) {
            if (!level.isClientSide
                    && addWaterToRun(level, pos, FermentationTroughBlockEntity.UNITS_PER_CELL)) {
                if (!player.hasInfiniteMaterials()) {
                    ItemStack empty = new ItemStack(vanillaWater
                        ? Items.BUCKET : BannerboundAntiquity.CLAY_FIRED_BUCKET.get());
                    player.setItemInHand(hand, empty);
                }
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        java.util.Map.Entry<net.minecraft.resources.ResourceLocation,
            com.bannerbound.antiquity.recipe.GrogRecipe> match =
            com.bannerbound.antiquity.recipe.GrogRecipeManager.findForInput(stack.getItem());
        if (match != null) {
            if (!level.isClientSide
                    && chargePool(level, pos, match.getKey().toString(), match.getValue())) {
                if (!player.hasInfiniteMaterials()) stack.shrink(1);
                level.playSound(null, pos, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.6F, 0.8F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        if (stack.getItem() instanceof com.bannerbound.antiquity.item.GrogVesselItem
                && !stack.has(BannerboundAntiquity.GROG_CONTENTS.get())) {
            com.bannerbound.antiquity.recipe.GrogRecipe grog = readyGrog(level, pos);
            if (grog != null && poolUnits(level, pos) > 0) {
                if (!level.isClientSide) {
                    fillVessel(player, hand, stack, grog);
                    drainServing(level, pos);
                    level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
        FermentationTroughBlockEntity be = trough(level, pos);
        if (be == null) return InteractionResult.PASS;

        com.bannerbound.antiquity.recipe.GrogRecipe grog = readyGrog(level, pos);
        if (grog != null && poolUnits(level, pos) > 0) {
            if (!level.isClientSide) {
                com.bannerbound.antiquity.item.Intoxication.sip(
                    player, grog.effects(), grog.strength(), grog.foodValue());
                drainServing(level, pos);
                level.playSound(null, pos, SoundEvents.GENERIC_DRINK, SoundSource.BLOCKS, 0.6F, 1.0F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (!isPoolCharged(level, pos) && hasNearbyWater(level, pos)) {
            if (!level.isClientSide && addWaterToRun(level, pos, 1)) {
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.7F, 1.1F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    private static boolean hasNearbyWater(Level level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handlePrecipitation(BlockState state, Level level, BlockPos pos,
                                    net.minecraft.world.level.biome.Biome.Precipitation precipitation) {
        if (precipitation == net.minecraft.world.level.biome.Biome.Precipitation.RAIN
                && level.getRandom().nextFloat() < 0.25F) {
            addWaterToRun(level, pos, 1);
        }
    }
}
