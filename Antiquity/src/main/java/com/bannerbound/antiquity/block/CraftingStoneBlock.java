package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Crafting Stone -- a knapping workbench carved out of cobblestone/sandstone/red_sandstone by a
 * flint knife. Items are piled on it one at a time (mixed types allowed), held by the
 * {@link CraftingStoneBlockEntity}; when the pile matches a recipe a floating spinning result shows
 * above it. Right-click with an item adds ONE to the pile; an empty-handed click takes the last item
 * back out; ONLY shift-right-click crafts -- a plain click never does, so a finished pile is never
 * consumed by accident (the floating ghost preview is the no-shift feedback). Both interaction paths
 * check {@code WorkBlockLocks}: a citizen mid-craft owns the stone, so players cannot insert into or
 * pull the pile out from under them. The MATERIAL property records which rock the stone was carved
 * from and drives its texture variant + what it drops; SHAPE hugs the model's main stone (4-12
 * across, 0-7 high) so collision/selection match what is drawn.
 */
public class CraftingStoneBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<CraftingStoneBlock> CODEC = simpleCodec(CraftingStoneBlock::new);
    public static final VoxelShape SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 7.0, 12.0);
    public static final EnumProperty<Material> MATERIAL = EnumProperty.create("material", Material.class);

    public enum Material implements StringRepresentable {
        STONE("stone"),
        SANDSTONE("sandstone"),
        RED_SANDSTONE("red_sandstone");

        private final String name;

        Material(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public CraftingStoneBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(MATERIAL, Material.STONE));
    }

    @Override
    protected MapCodec<CraftingStoneBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MATERIAL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    // Non-full collision box reads as walkable to vanilla nav and citizens snag; must stay un-pathfindable.
    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CraftingStoneBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.CRAFTING_STONE_BE.get()) return null;
        return (lvl, pos, st, be) -> CraftingStoneBlockEntity.tick(lvl, pos, st, (CraftingStoneBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CraftingStoneBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player.isSecondaryUseActive() && tryCraft(level, pos, be)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide
                && !com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())
                && be.insertOne(stack, player.getDirection().getOpposite())
                && !player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CraftingStoneBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide
                && com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component
                    .translatable("bannerbound.workshop.station_busy")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }
        if (player.isSecondaryUseActive() && tryCraft(level, pos, be)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            ItemStack out = be.removeOne();
            if (!out.isEmpty()) giveOrDrop(player, out);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean tryCraft(Level level, BlockPos pos, CraftingStoneBlockEntity be) {
        if (be.getResult().isEmpty()) return false;
        if (!level.isClientSide) {
            ItemStack out = be.craft();
            Block.popResource(level, pos.above(), out);
            level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(),
                SoundSource.BLOCKS, 0.8F, 1.2F);
        }
        return true;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof CraftingStoneBlockEntity be) {
            for (ItemStack s : be.getContents()) {
                Block.popResource(level, pos, s);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
