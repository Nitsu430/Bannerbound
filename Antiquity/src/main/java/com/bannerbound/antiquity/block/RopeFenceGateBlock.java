package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.rope.RopeAnchor;
import com.bannerbound.antiquity.rope.RopeTies;
import com.bannerbound.antiquity.block.entity.RopeFenceGateBlockEntity;
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
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A primitive lift-bar gate for the rope fence, predating wooden fence gates. It carries TWO rope
 * tie points - the left upright (slot 0) and right upright (slot 1) - so a rope fence line can run
 * through it: right-click with a fiber rope (non-shift) ties to the nearer upright via
 * {@code RopeTies.slotForHit}; any other click toggles OPEN (bar raised = empty collision, and,
 * like a vanilla fence gate, pathable to mobs ONLY while open). Ties are independent of open/closed
 * (the uprights never move, only the rod lifts); ROPED_LEFT/ROPED_RIGHT just pick the model variant
 * showing each upright's coil. Closed collision is 24px tall (jump-proof, vanilla fence
 * convention). Breaking the gate breaks and drops all its ropes server-side.
 */
public class RopeFenceGateBlock extends Block implements EntityBlock {
    public static final MapCodec<RopeFenceGateBlock> CODEC = simpleCodec(RopeFenceGateBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty ROPED_LEFT = BooleanProperty.create("roped_left");
    public static final BooleanProperty ROPED_RIGHT = BooleanProperty.create("roped_right");

    // Model-space X (0-1) of the uprights: left = "Post Left" spans X12-16 -> 14/16; right spans X0-4 -> 2/16.
    public static final double LEFT_X = 14.0 / 16.0;
    public static final double RIGHT_X = 2.0 / 16.0;

    private static final VoxelShape SHAPE_X = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);
    private static final VoxelShape COLLISION_X = Block.box(0.0, 0.0, 6.0, 16.0, 24.0, 10.0);
    private static final VoxelShape SHAPE_Z = Block.box(6.0, 0.0, 0.0, 10.0, 16.0, 16.0);
    private static final VoxelShape COLLISION_Z = Block.box(6.0, 0.0, 0.0, 10.0, 24.0, 16.0);

    public RopeFenceGateBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(OPEN, Boolean.FALSE)
            .setValue(ROPED_LEFT, Boolean.FALSE)
            .setValue(ROPED_RIGHT, Boolean.FALSE));
    }

    @Override
    protected MapCodec<RopeFenceGateBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, ROPED_LEFT, ROPED_RIGHT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RopeFenceGateBlockEntity(pos, state);
    }

    private static boolean alongX(BlockState state) {
        Direction f = state.getValue(FACING);
        return f == Direction.NORTH || f == Direction.SOUTH;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return alongX(state) ? SHAPE_X : SHAPE_Z;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (state.getValue(OPEN)) {
            return Shapes.empty();
        }
        return alongX(state) ? COLLISION_X : COLLISION_Z;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return state.getValue(OPEN);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(BannerboundAntiquity.FIBER_ROPE.get()) || player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        RopeAnchor anchor = new RopeAnchor(pos, RopeTies.slotForHit(level, pos, hit.getLocation()));
        if (level.isClientSide) {
            RopeTies.handleTieClient(level, anchor);
            return ItemInteractionResult.sidedSuccess(true);
        }
        if (RopeTies.handleTieServer(level, player, anchor) && !player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        return ItemInteractionResult.sidedSuccess(false);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        boolean open = !state.getValue(OPEN);
        level.setBlock(pos, state.setValue(OPEN, open), Block.UPDATE_ALL);
        level.playSound(player, pos, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
            SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
        level.gameEvent(player, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof RopeFenceGateBlockEntity be) {
            RopeTies.breakAllAndDrop(level, pos, be);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
