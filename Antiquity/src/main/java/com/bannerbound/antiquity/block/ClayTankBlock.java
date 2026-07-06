package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Clay Tank: a vertical modular pillar (up to MAX_PIECES blocks) holding curing liquid for the
 * tannery. Placed piece-by-piece -- each block stacked on a tank takes the next PART index; the
 * bottom (PART 0) is the controller and carries the ClayTankBlockEntity (the liquid). Stacking past
 * MAX_PIECES is refused (getStateForPlacement returns null). Right-click a water bucket to fill, an
 * empty bucket to drain, quicklime to convert held water into hide-curing liquid, or a scraped hide
 * on a curing-charged tank to make a cured hide (consumes one bucket of curing liquid).
 *
 * <p>Shapes are hollow (walls 1px, cauldron-style) so entities fall inside: the base keeps its floor
 * (y 0..1), extensions are open tubes down to the base. Breaking any piece destroys the pieces above
 * it and clamps the controller's liquid to the remaining height.
 */
public class ClayTankBlock extends Block implements EntityBlock {
    public static final MapCodec<ClayTankBlock> CODEC = simpleCodec(ClayTankBlock::new);
    public static final int MAX_PIECES = 4;
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, MAX_PIECES - 1);

    private static final VoxelShape SHAPE_BASE = net.minecraft.world.phys.shapes.Shapes.join(
        net.minecraft.world.phys.shapes.Shapes.block(), Block.box(1, 1, 1, 15, 16, 15),
        net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST);
    private static final VoxelShape SHAPE_EXTENSION = net.minecraft.world.phys.shapes.Shapes.join(
        net.minecraft.world.phys.shapes.Shapes.block(), Block.box(1, 0, 1, 15, 16, 15),
        net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST);

    public ClayTankBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, 0));
    }

    @Override
    protected MapCodec<ClayTankBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    private static VoxelShape shapeFor(BlockState state) {
        return state.getValue(PART) == 0 ? SHAPE_BASE : SHAPE_EXTENSION;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    // Non-full collision box reads as walkable to vanilla nav and NPCs snag; must stay un-pathfindable.
    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState below = ctx.getLevel().getBlockState(ctx.getClickedPos().below());
        if (below.getBlock() instanceof ClayTankBlock) {
            int part = below.getValue(PART) + 1;
            if (part >= MAX_PIECES) return null;
            return defaultBlockState().setValue(PART, part);
        }
        return defaultBlockState().setValue(PART, 0);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == 0 ? new ClayTankBlockEntity(pos, state) : null;
    }

    private static BlockPos controllerPos(BlockPos pos, BlockState state) {
        return pos.below(state.getValue(PART));
    }

    @Nullable
    public static ClayTankBlockEntity getController(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ClayTankBlock)) return null;
        return level.getBlockEntity(controllerPos(pos, state)) instanceof ClayTankBlockEntity be ? be : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              net.minecraft.world.entity.player.Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        ClayTankBlockEntity controller = getController(level, pos);
        if (controller == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.is(Items.WATER_BUCKET)) {
            if (!level.isClientSide && controller.addWater()) {
                if (!player.hasInfiniteMaterials()) {
                    player.setItemInHand(hand, new ItemStack(Items.BUCKET));
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(Items.BUCKET)) {
            if (!level.isClientSide && controller.removeWater()) {
                if (!player.hasInfiniteMaterials()) {
                    player.setItemInHand(hand, new ItemStack(Items.WATER_BUCKET));
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(BannerboundAntiquity.QUICKLIME.get())) {
            if (!level.isClientSide && controller.convertToCuring() && !player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(BannerboundAntiquity.SCRAPED_HIDE.get()) && controller.hasCuring()) {
            if (!level.isClientSide && controller.drawCuring()) {
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
                ItemStack cured = new ItemStack(BannerboundAntiquity.CURED_HIDE.get());
                if (!player.getInventory().add(cured)) {
                    player.drop(cured, false);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              net.minecraft.world.entity.player.Player player,
                                              BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock()) && !level.isClientSide) {
            BlockPos above = pos.above();
            while (level.getBlockState(above).getBlock() instanceof ClayTankBlock) {
                level.destroyBlock(above, true);
                above = above.above();
            }
            int part = oldState.getValue(PART);
            if (part > 0 && level.getBlockEntity(pos.below(part)) instanceof ClayTankBlockEntity be) {
                be.clampBuckets(ClayTankBlockEntity.BUCKETS_PER_PIECE * part);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
