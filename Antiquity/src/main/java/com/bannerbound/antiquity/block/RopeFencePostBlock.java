package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.HerdingEvents;
import com.bannerbound.antiquity.RopeAnchor;
import com.bannerbound.antiquity.RopeTies;
import com.bannerbound.antiquity.block.entity.RopeFencePostBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Rope Fence Post - one rope tie point (slot 0, its centre). Right-click two posts/gate-uprights
 * in turn with a {@code fiber_rope} to tie a rope between them; the rope is drawn client-side as a
 * leash-style catenary and faked as a fence by {@link RopeTies}, and the block sits in
 * {@code minecraft:fences} so vanilla lead/livestock logic treats it as a fence. ROPED selects the
 * rope-wrap coil model once at least one connection exists (flipping it re-fires onRemove, hence
 * the same-block guard there). One block class serves every wood type (the wood is just the model);
 * all posts share one {@code ROPE_FENCE_POST_BE}. Outline SHAPE is the model's 4x16x4 column;
 * collision is 24px (1.5 blocks, vanilla fence height - jump-proof, natural standing on top), and
 * it is un-pathfindable like a vanilla fence post so mobs route around instead of being shoved by
 * the analytical rope collision. In useItemOn, shift or non-rope items fall through so fiber rope's
 * shift+right-click spear reel still works; if the player is leading animals on a rope, the click
 * ties THEM to this post (vanilla lead-to-fence, leash holder is synced so both sides agree)
 * instead of starting a post-to-post tie. Breaking the post breaks and drops all its ropes
 * server-side.
 */
public class RopeFencePostBlock extends Block implements EntityBlock {
    public static final MapCodec<RopeFencePostBlock> CODEC = simpleCodec(RopeFencePostBlock::new);
    public static final BooleanProperty ROPED = BooleanProperty.create("roped");
    public static final VoxelShape SHAPE = Block.box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);
    public static final VoxelShape COLLISION_SHAPE = Block.box(6.0, 0.0, 6.0, 10.0, 24.0, 10.0);

    public RopeFencePostBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ROPED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<RopeFencePostBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROPED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return COLLISION_SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RopeFencePostBlockEntity(pos, state);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(BannerboundAntiquity.FIBER_ROPE.get()) || player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (HerdingEvents.hasLedAnimalsNear(player, pos)) {
            if (!level.isClientSide) {
                HerdingEvents.tieLedAnimalsToFence(player, level, pos);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        RopeAnchor anchor = new RopeAnchor(pos, 0);
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
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        // Same-block guard: a ROPED property flip also fires onRemove and must NOT break the ropes.
        if (!oldState.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof RopeFencePostBlockEntity be) {
            RopeTies.breakAllAndDrop(level, pos, be);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
