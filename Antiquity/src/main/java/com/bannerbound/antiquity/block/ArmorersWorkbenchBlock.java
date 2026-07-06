package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import com.bannerbound.antiquity.network.OpenArmorerPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Armorer's Workbench -- the player-designed-armor station (ARMOR_PLAN.md). A 2-cell multiblock laid
 * out exactly like the Carpenter's Table: the MAIN=true master cell renders the full 32px model
 * extending one block toward FACING (away from the placer), the MAIN=false secondary cell renders
 * nothing and forwards interactions to the master (masterPos anchors the GUI to the master cell).
 * There is no block entity -- the bench is a static JSON model and the armor design lives entirely
 * in the screen for now. Shift + right-click (empty hand) on either half opens the ArmorerScreen
 * design GUI via OpenArmorerPayload; a plain right-click deliberately PASSes, reserved for future
 * on-bench interactions. Placement is refused unless the secondary cell can be replaced, and
 * breaking either half tears down the other in onRemove. The shape is a tabletop slab (y 12-15px,
 * rotation-invariant so no per-facing variants); because that is not a full cube, vanilla would
 * classify the cell walkable and NPCs would path onto it and snag, so isPathfindable returns false
 * to make every pathfinder route around it.
 */
public class ArmorersWorkbenchBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ArmorersWorkbenchBlock> CODEC = simpleCodec(ArmorersWorkbenchBlock::new);
    public static final BooleanProperty MAIN = BooleanProperty.create("main");
    public static final VoxelShape SHAPE = Block.box(0.0, 12.0, 0.0, 16.0, 15.0, 16.0);

    public ArmorersWorkbenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MAIN, true));
    }

    @Override
    protected MapCodec<ArmorersWorkbenchBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MAIN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        BlockPos secondary = context.getClickedPos().relative(facing);
        if (!context.getLevel().getBlockState(secondary).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, facing).setValue(MAIN, true);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && state.getValue(MAIN)) {
            BlockPos secondary = pos.relative(state.getValue(FACING));
            level.setBlock(secondary, state.setValue(MAIN, false), Block.UPDATE_ALL);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    private static BlockPos masterPos(BlockPos pos, BlockState state) {
        return state.getValue(MAIN) ? pos : pos.relative(state.getValue(FACING).getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new OpenArmorerPayload(masterPos(pos, state)));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            Direction facing = oldState.getValue(FACING);
            boolean main = oldState.getValue(MAIN);
            // Recursion terminates: this cell is already air when the other half's onRemove runs.
            BlockPos other = main ? pos.relative(facing) : pos.relative(facing.getOpposite());
            if (level.getBlockState(other).is(this)) {
                level.removeBlock(other, false);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
