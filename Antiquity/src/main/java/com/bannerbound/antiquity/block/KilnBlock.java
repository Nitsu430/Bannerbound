package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The formed Kiln - a 2x2x2 multiblock created by claying eight cobblestone into a cube
 * ({@code KilnFormation} detects and forms it). It occupies eight block positions; {@link #PART}
 * encodes each cell's offset from the controller, and only the controller (the min-corner,
 * {@code PART == 0}) carries the {@code KilnBlockEntity} and ticker. Every cell renders INVISIBLE:
 * the client KilnRenderer draws the whole dome from the controller, rotated about the 2x2x2 centre
 * to {@link #FACING} (the controller's authored model is still baked for the renderer and
 * particles). {@link #lightEmission} is wired in at registration via
 * {@code BlockBehaviour.Properties.lightLevel(KilnBlock::lightEmission)}; only the two bottom
 * cells on the facing side (the mouth) emit {@link #LIT_LIGHT} while {@link #LIT}, so the glow
 * reads as coming from the opening. {@link #DESTROY_TIME} (8.0) keeps mining slow - it is a
 * substantial fired-clay structure.
 *
 * <p>Mechanically the early-game cousin of the Bloomery, with no door: right-click with an item
 * puts the whole stack inside (when the kiln is empty); right-click empty-handed takes the held
 * stack back out; flint and steel ignites it; coal or charcoal stokes an already-lit fire (in
 * place of the bloomery's bellows). Fire Sticks are handled by the item itself (a held charge), so
 * the block skips its own interaction for them - aiming at the kiln starts the wind-up directly.
 * Breaking any cell pops the held item, then tears down the other seven cells with drops
 * suppressed; the eight cobblestone come only from the directly-broken cell's loot table
 * (pickaxe-gated).
 */
public class KilnBlock extends Block implements EntityBlock {
    public static final MapCodec<KilnBlock> CODEC = simpleCodec(KilnBlock::new);
    // PART = dx*4 + dy*2 + dz (each 0 or 1) from the min-corner; KilnFormation and controllerPos must agree.
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 7);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public static final float DESTROY_TIME = 8.0F;
    public static final int LIT_LIGHT = 13;

    public KilnBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(PART, 0)
            .setValue(FACING, Direction.NORTH)
            .setValue(LIT, false));
    }

    public static int lightEmission(BlockState state) {
        if (!state.getValue(LIT)) {
            return 0;
        }
        int part = state.getValue(PART);
        int dx = (part >> 2) & 1;
        int dy = (part >> 1) & 1;
        int dz = part & 1;
        if (dy != 0) {
            return 0;
        }
        Direction facing = state.getValue(FACING);
        boolean onMouthSide = switch (facing) {
            case NORTH -> dz == 0;
            case SOUTH -> dz == 1;
            case WEST -> dx == 0;
            case EAST -> dx == 1;
            default -> false;
        };
        return onMouthSide ? LIT_LIGHT : 0;
    }

    @Override
    protected MapCodec<KilnBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, FACING, LIT);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == 0 ? new KilnBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (state.getValue(PART) != 0 || type != BannerboundAntiquity.KILN_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> KilnBlockEntity.tick(lvl, pos, st, (KilnBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        KilnBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (stack.is(Items.FLINT_AND_STEEL)) {
            if (!level.isClientSide) {
                controller.ignite();
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Fire Sticks must SKIP (not PASS) so the item's own use() runs and the wind-up starts here.
        if (stack.is(BannerboundAntiquity.FIRE_STICKS.get())) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }

        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) {
            if (!controller.isLit()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide && controller.stoke() && !player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        if (stack.isEmpty() || !controller.getHeldItem().isEmpty()) {
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
            return InteractionResult.PASS;
        }
        KilnBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        if (!controller.getHeldItem().isEmpty()) {
            giveOrDrop(player, controller.extract());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private static BlockPos controllerPos(BlockPos pos, BlockState state) {
        int part = state.getValue(PART);
        return pos.offset(-((part >> 2) & 1), -((part >> 1) & 1), -(part & 1));
    }

    private static KilnBlockEntity getController(Level level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(controllerPos(pos, state)) instanceof KilnBlockEntity be ? be : null;
    }

    @Nullable
    public static KilnBlockEntity getController(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof KilnBlock ? getController(level, pos, state) : null;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            BlockPos controller = controllerPos(pos, oldState);
            // extract() must empty the BE first or the re-entrant onRemove from clearing cells drops it twice.
            if (!level.isClientSide && level.getBlockEntity(controller) instanceof KilnBlockEntity be) {
                ItemStack held = be.extract();
                if (!held.isEmpty()) {
                    Block.popResource(level, pos, held);
                }
            }
            // Teardown bounce terminates: each cleared cell's onRemove sees its neighbours already air.
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    for (int dz = 0; dz < 2; dz++) {
                        BlockPos cell = controller.offset(dx, dy, dz);
                        if (cell.equals(pos)) {
                            continue;
                        }
                        if (level.getBlockState(cell).is(this)) {
                            level.setBlock(cell, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                        }
                    }
                }
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
