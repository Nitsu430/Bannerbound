package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.BasketBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Basket -- a 9-slot storage block (part of the shared storage pool alongside stockpiles). The
 * body is a normal JSON block model; a block entity renderer (BasketRenderer) additionally draws
 * whatever sits in the first slot on top. Plain right-click opens the 3x3 storage screen; sneak +
 * right-click (empty hand) instantly picks the whole basket up -- block and contents -- into a
 * single item carrying a BASKET_CONTENTS component: markPickup() tells onRemove to skip spilling,
 * and setPlacedBy restores the items into the freshly-placed block entity. A normal break leaves
 * the flag unset and drops the contents loose. Rotates to face the player on placement. The
 * collision/outline shape is traced box-by-box from the model (basket.json) at its true extents for
 * the default NORTH facing -- floor slab, low open-to-reach-in front wall, tall back wall plus its
 * low rim, and two side walls; the 1px decorative handle-holes are deliberately filled -- then
 * rotateY spins it 90 degrees clockwise per quarter turn to match the blockstate's "y" model
 * rotation (north=0, east=90, south=180, west=270). Because the shape is not a full cube, vanilla
 * would classify the cell walkable and NPCs would path onto the basket and snag, so isPathfindable
 * returns false (previously special-cased by id in CitizenNodeEvaluator; now declared on the block).
 */
public class BasketBlock extends Block implements EntityBlock {
    public static final MapCodec<BasketBlock> CODEC = simpleCodec(BasketBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShape SHAPE_NORTH = Shapes.or(
        Block.box(2.0, 0.0, 5.0, 14.0, 1.0, 12.0),
        Block.box(2.0, 2.0, 3.0, 14.0, 8.0, 4.0),
        Block.box(2.0, 1.0, 12.0, 14.0, 2.0, 13.0),
        Block.box(2.0, 2.0, 13.0, 14.0, 8.0, 14.0),
        Block.box(1.0, 1.0, 4.0, 2.0, 8.0, 13.0),
        Block.box(14.0, 1.0, 4.0, 15.0, 8.0, 13.0));
    private static final VoxelShape SHAPE_EAST = rotateY(SHAPE_NORTH, 1);
    private static final VoxelShape SHAPE_SOUTH = rotateY(SHAPE_NORTH, 2);
    private static final VoxelShape SHAPE_WEST = rotateY(SHAPE_NORTH, 3);

    private static VoxelShape rotateY(VoxelShape shape, int quarterTurns) {
        VoxelShape[] buf = { shape, Shapes.empty() };
        for (int i = 0; i < quarterTurns; i++) {
            VoxelShape src = buf[0];
            buf[1] = Shapes.empty();
            src.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                buf[1] = Shapes.or(buf[1], Shapes.box(1 - maxZ, minY, minX, 1 - minZ, maxY, maxX)));
            buf[0] = buf[1];
        }
        return buf[0];
    }

    public BasketBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<BasketBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BasketBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case EAST -> SHAPE_EAST;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (player.isSecondaryUseActive()) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            if (level.getBlockEntity(pos) instanceof BasketBlockEntity basket) {
                ItemStack stack = new ItemStack(this);
                if (!basket.isEmpty()) {
                    stack.set(BannerboundAntiquity.BASKET_CONTENTS.get(),
                        ItemContainerContents.fromItems(basket.getItems()));
                }
                basket.markPickup(); // must precede removeBlock or onRemove spills the contents
                level.levelEvent(2001, pos, Block.getId(state)); // 2001 = vanilla break particles + sound
                level.removeBlock(pos, false);
                if (!player.addItem(stack)) {
                    player.drop(stack, false);
                }
            }
            return InteractionResult.CONSUME;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof BasketBlockEntity basket) {
            serverPlayer.openMenu(basket, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            // Pickup-flagged baskets already carry contents in their item; spilling here would dupe.
            if (level.getBlockEntity(pos) instanceof BasketBlockEntity basket && !basket.isPickupRequested()) {
                Containers.dropContents(level, pos, basket.getDroppableInventory());
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        ItemContainerContents contents = stack.get(BannerboundAntiquity.BASKET_CONTENTS.get());
        if (contents != null && !level.isClientSide()
                && level.getBlockEntity(pos) instanceof BasketBlockEntity basket) {
            basket.loadFromContents(contents);
        }
    }
}
