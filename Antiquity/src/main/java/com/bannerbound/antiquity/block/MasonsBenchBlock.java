package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Masonry;
import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Mason's Bench - a 2-block multiblock and the stone analogue of the Carpenter's Table. The MASTER
 * cell (MAIN=true) holds the block entity and renders the full model extending one block toward
 * FACING (away from the placer); the SECONDARY cell at master+FACING renders nothing and forwards
 * every interaction to the master via masterPos. The item places both halves bed-style, and breaking
 * either half tears down the other, with drops and minigame-session cleanup running once from the
 * master side. Interactions (all refused with a "station busy" message while WorkBlockLocks says an
 * NPC holds the station): right-click with base stone (cobblestone, stone, sandstone, ...) puts one
 * on the budget pile (sneak = the whole stack); right-click with the stone chisel starts the
 * chisel-strike minigame (needs a queued build list); right-click empty-handed takes the last
 * uncommitted stone back (sneak = undo the last queued entry). SHAPE is the full 14px-tall bench
 * body shared by both cells - rotation-invariant, so no per-facing shape; it extends down with legs,
 * unlike the carpenter's floating-slab shape.
 */
public class MasonsBenchBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<MasonsBenchBlock> CODEC = simpleCodec(MasonsBenchBlock::new);
    public static final BooleanProperty MAIN = BooleanProperty.create("main");
    public static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 14.0, 16.0);

    public MasonsBenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MAIN, true));
    }

    @Override
    protected MapCodec<MasonsBenchBlock> codec() {
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
        return false; // non-full-cube shape: must stay un-pathfindable or NPCs path onto the bench and snag
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(MAIN) ? new MasonsBenchBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (!state.getValue(MAIN) || type != BannerboundAntiquity.MASONS_BENCH_BE.get()) return null;
        return (lvl, pos, st, be) ->
            MasonsBenchBlockEntity.tick(lvl, pos, st, (MasonsBenchBlockEntity) be);
    }

    private static BlockPos masterPos(BlockPos pos, BlockState state) {
        return state.getValue(MAIN) ? pos : pos.relative(state.getValue(FACING).getOpposite());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        BlockPos mp = masterPos(pos, state);
        if (!(level.getBlockEntity(mp) instanceof MasonsBenchBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (isBusy(level, mp, player)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(BannerboundAntiquity.STONE_CHISEL.get())) {
            return tryStartChiseling(level, mp, player, be)
                ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (MasonsBenchBlockEntity.isBudgetMaterial(stack)) {
            if (!level.isClientSide) {
                Direction from = player.getDirection().getOpposite();
                int added = player.isSecondaryUseActive()
                    ? be.insertStack(stack, from)
                    : (be.insertOne(stack, from) ? 1 : 0);
                if (added > 0) {
                    if (!player.hasInfiniteMaterials()) stack.shrink(added);
                    level.playSound(null, mp, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.7F, 1.1F);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        BlockPos mp = masterPos(pos, state);
        if (!(level.getBlockEntity(mp) instanceof MasonsBenchBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (isBusy(level, mp, player)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            if (player.isSecondaryUseActive()) {
                if (be.removeLastEntry()) {
                    level.playSound(null, mp, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
                }
            } else {
                ItemStack out = be.removeOne();
                if (!out.isEmpty()) {
                    giveOrDrop(player, out);
                    level.playSound(null, mp, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 0.9F);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean isBusy(Level level, BlockPos masterPos, Player player) {
        if (!level.isClientSide
                && com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(masterPos, player.getUUID())) {
            player.displayClientMessage(Component.translatable("bannerbound.workshop.station_busy")
                .withStyle(ChatFormatting.YELLOW), true);
            return true;
        }
        return false;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static boolean tryStartChiseling(Level level, BlockPos masterPos, Player player,
                                             MasonsBenchBlockEntity be) {
        if (!be.hasBuildList()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("bannerboundantiquity.masonry.empty_list")
                    .withStyle(ChatFormatting.YELLOW), true);
            }
            return false;
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            Masonry.startChiseling(sp, masterPos, be);
        }
        return true;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            Direction facing = oldState.getValue(FACING);
            boolean main = oldState.getValue(MAIN);
            if (main) {
                if (level.getBlockEntity(pos) instanceof MasonsBenchBlockEntity be) {
                    be.dropStones(level);
                }
                Masonry.abortSessionAt(pos);
            }
            // Removing the other half recurses into onRemove; terminates because this cell is already air.
            BlockPos other = main ? pos.relative(facing) : pos.relative(facing.getOpposite());
            if (level.getBlockState(other).is(this)) {
                level.removeBlock(other, false);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
