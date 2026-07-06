package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The formed Bloomery -- a 1x1x2 multiblock created by stacking two mud-brick blocks and
 * right-clicking the lower one with a block of coal (AntiquityEvents handles that formation). It
 * occupies two positions via DoubleBlockHalf LOWER/UPPER: only the LOWER half carries the block
 * entity and ticker, and its renderer draws the whole model. All interactions resolve through
 * getController (the public overload resolves the controller from any position the multiblock
 * occupies and is what BellowsBlock calls to pump heat). Right-click with an item puts the whole
 * held stack inside (door open, bloomery empty); right-click empty-handed takes the held stack back
 * out (door open); flint and steel ignites it (door open, damages the item); shift + empty hand
 * toggles the door. Fire Sticks charge in the hand, so useItemOn answers
 * SKIP_DEFAULT_BLOCK_INTERACTION for them -- the item's own use() then runs and the wind-up starts
 * while aiming at the bloomery. Breaking either half makes the first segment clear its partner
 * (UPDATE_SUPPRESS_DROPS) and return the two mud bricks plus any item still held inside.
 */
public class BloomeryBlock extends Block implements EntityBlock {
    public static final MapCodec<BloomeryBlock> CODEC = simpleCodec(BloomeryBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BloomeryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(HALF, DoubleBlockHalf.LOWER)
            .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<BloomeryBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new BloomeryBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER
                || type != BannerboundAntiquity.BLOOMERY_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> BloomeryBlockEntity.tick(lvl, pos, st, (BloomeryBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        BloomeryBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (stack.is(Items.FLINT_AND_STEEL)) {
            if (!controller.isOpen()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                controller.ignite();
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        if (stack.is(BannerboundAntiquity.FIRE_STICKS.get())) {
            // SKIP (not PASS) so no block interaction runs and FireSticks.use() starts the wind-up.
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }

        if (stack.isEmpty() || !controller.isOpen() || !controller.getHeldItem().isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            controller.insert(stack.copy());
            if (!player.hasInfiniteMaterials()) {
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS; // an unhandled item fell through useItemOn
        }
        BloomeryBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            controller.toggle();
            return InteractionResult.CONSUME;
        }
        if (controller.isOpen() && !controller.getHeldItem().isEmpty()) {
            giveOrDrop(player, controller.extract());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private static BloomeryBlockEntity getController(Level level, BlockPos pos, BlockState state) {
        BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        return level.getBlockEntity(lower) instanceof BloomeryBlockEntity be ? be : null;
    }

    @Nullable
    public static BloomeryBlockEntity getController(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BloomeryBlock ? getController(level, pos, state) : null;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            BlockPos partner = oldState.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            if (level.getBlockState(partner).is(this)) {
                if (!level.isClientSide) {
                    Block.popResource(level, pos, new ItemStack(Items.MUD_BRICKS, 2));
                    if (level.getBlockEntity(
                            oldState.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : partner)
                            instanceof BloomeryBlockEntity be && !be.getHeldItem().isEmpty()) {
                        Block.popResource(level, pos, be.getHeldItem());
                    }
                }
                level.setBlock(partner, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
