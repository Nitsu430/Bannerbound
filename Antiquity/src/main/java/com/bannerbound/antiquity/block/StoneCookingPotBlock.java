package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
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
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The stone cooking pot - a single placeable block (5 cobblestone + 1 stick at the crafting stone).
 * Fill it with water (held pot on a water source, or a vanilla/fired-clay water bucket on the placed
 * pot), set it on a lit campfire that isn't the town hall, add food, and it cooks into a stew (logic
 * in {@link StoneCookingPotBlockEntity}). Empty-hand right-click eats a serving; citizens eat via
 * hasReadyServing/takeServing (StewEatGoal); the settlement larder eats the rest over time.
 * FILLED picks the water/stew model over the empty pot; ON_FIRE (true when a campfire sits directly
 * below, kept in sync by updateShape) drops both render and shape by VISUAL_DROP so the pot sits down
 * on the fire instead of floating - the dropped shape reaches into the campfire's space, whose own
 * short-slab collision still lets clicks reach the pot. While a pot sits on a vanilla campfire,
 * hideCampfireFlame swaps it for the flame-less CookingFireBlock (facing/lit/signal/waterlogged
 * preserved) so no flame pokes up through the pot; a real block change always re-meshes, so this works
 * under any renderer (Sodium/Iris). The swap is server-only and idempotent, run on pot placement AND
 * from the pot's tick so already-placed pots in a loaded world get swapped; onRemove restores the
 * normal campfire.
 */
public class StoneCookingPotBlock extends Block implements EntityBlock {
    public static final MapCodec<StoneCookingPotBlock> CODEC = simpleCodec(StoneCookingPotBlock::new);
    public static final BooleanProperty FILLED = BooleanProperty.create("filled");
    public static final BooleanProperty ON_FIRE = BooleanProperty.create("on_fire");

    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 11.0, 15.0);
    private static final double DROP_PX = StoneCookingPotBlockEntity.VISUAL_DROP * 16.0;
    private static final VoxelShape SHAPE_ON_FIRE =
        Block.box(1.0, -DROP_PX, 1.0, 15.0, 11.0 - DROP_PX, 15.0);

    public StoneCookingPotBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FILLED, false).setValue(ON_FIRE, false));
    }

    @Override
    protected MapCodec<StoneCookingPotBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILLED, ON_FIRE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(ON_FIRE) ? SHAPE_ON_FIRE : SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        // Partial-collision block: without this override NPCs classify the cell walkable and snag on it.
        return false;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(ON_FIRE, isOnCampfire(ctx.getLevel(), ctx.getClickedPos()));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir == Direction.DOWN) {
            boolean onFire = neighbor.getBlock() instanceof CampfireBlock;
            if (state.getValue(ON_FIRE) != onFire) {
                return state.setValue(ON_FIRE, onFire);
            }
        }
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    private static boolean isOnCampfire(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof CampfireBlock;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        hideCampfireFlame(level, pos);
    }

    public static void hideCampfireFlame(Level level, BlockPos potPos) {
        if (level.isClientSide) return;
        BlockPos cf = potPos.below();
        BlockState below = level.getBlockState(cf);
        if (below.getBlock() == net.minecraft.world.level.block.Blocks.CAMPFIRE) {
            BlockState cfState = copyCampfireState(
                BannerboundAntiquity.COOKING_FIRE.get().defaultBlockState(), below);
            level.setBlock(cf, cfState, Block.UPDATE_ALL);
            // Swap LOWERS light emission (10 vs 15); without checkBlock the stale light field leaves the area dark.
            level.getLightEngine().checkBlock(cf);
            BannerboundAntiquity.LOGGER.info("[cookingfire] swapped: lit={} emission={}",
                cfState.getValue(CampfireBlock.LIT), cfState.getLightEmission());
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        super.onRemove(state, level, pos, newState, moved);
        if (!level.isClientSide && !newState.is(this)) {
            BlockPos cf = pos.below();
            BlockState below = level.getBlockState(cf);
            if (below.getBlock() instanceof CookingFireBlock) {
                level.setBlock(cf, copyCampfireState(
                    net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(), below),
                    Block.UPDATE_ALL);
            }
        }
    }

    private static BlockState copyCampfireState(BlockState target, BlockState src) {
        return target
            .setValue(CampfireBlock.FACING, src.getValue(CampfireBlock.FACING))
            .setValue(CampfireBlock.LIT, src.getValue(CampfireBlock.LIT))
            .setValue(CampfireBlock.SIGNAL_FIRE, src.getValue(CampfireBlock.SIGNAL_FIRE))
            .setValue(CampfireBlock.WATERLOGGED, src.getValue(CampfireBlock.WATERLOGGED));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide
                && com.bannerbound.antiquity.item.StoneCookingPotItem.isFilled(stack)
                && level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be) {
            be.setWater(true);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StoneCookingPotBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.STONE_COOKING_POT_BE.get()) return null;
        return (lvl, pos, st, be) -> StoneCookingPotBlockEntity.tick(lvl, pos, st, (StoneCookingPotBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (be.hasStew()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!be.hasWater()) {
            ItemStack emptied = emptiedWaterBucket(stack);
            if (emptied != null) {
                if (!level.isClientSide) {
                    be.setWater(true);
                    if (!player.hasInfiniteMaterials()) {
                        player.setItemInHand(hand, emptied);
                    }
                    level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!stack.isEmpty()) {
            if (!level.isClientSide && be.addIngredient(stack)) {
                if (!player.hasInfiniteMaterials()) stack.shrink(1);
                return ItemInteractionResult.sidedSuccess(false);
            }
            return level.isClientSide ? ItemInteractionResult.sidedSuccess(true)
                                      : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be) || !be.hasStew()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return be.eatServing(player) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    public static boolean hasReadyServing(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be
            && be.hasStew() && !be.stew().poisoned();
    }

    public static boolean takeServing(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be && be.takeServing();
    }

    @Nullable
    private static ItemStack emptiedWaterBucket(ItemStack stack) {
        if (stack.is(Items.WATER_BUCKET)) {
            return new ItemStack(Items.BUCKET);
        }
        if (stack.is(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get())) {
            return new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get());
        }
        return null;
    }
}
