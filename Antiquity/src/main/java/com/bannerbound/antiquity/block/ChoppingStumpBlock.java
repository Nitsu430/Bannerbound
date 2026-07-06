package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.ChoppingStumpBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import com.bannerbound.antiquity.event.AntiquityEvents;

/**
 * Chopping Stump -- a log butchering block made by right-clicking a lone log with an axe
 * (AntiquityEvents.onChopLoneLog). It is skinned with the textures of the log it was carved from:
 * ChoppingStumpRenderer (a BER) draws the whole body, so getRenderShape is INVISIBLE or the static
 * model would double up, and break particles are re-pointed at the source log's texture through a
 * client-only IClientBlockExtensions. Right-click with logs deposits them onto the stump (one log
 * type at a time, up to MAX_LOGS; the pile slides in from the player's side); right-click with an
 * axe chops ONE log per swing -- FIREWOOD_PER_LOG_CHANCE odds of popping a firewood, the chopped
 * log's chip particles, and 1 axe durability; right-click empty-handed takes the remaining logs
 * back, and breaking the stump pops them via onRemove. SHAPE matches the 6px-tall stump body the
 * renderer draws; because that is not a full cube, vanilla would classify the cell walkable and
 * NPCs would path onto it and snag, so isPathfindable returns false.
 */
public class ChoppingStumpBlock extends Block implements EntityBlock {
    public static final MapCodec<ChoppingStumpBlock> CODEC = simpleCodec(ChoppingStumpBlock::new);
    public static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 6.0, 16.0);
    public static final float FIREWOOD_PER_LOG_CHANCE = 0.85f;
    public static final int MAX_LOGS = 64;

    public ChoppingStumpBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<ChoppingStumpBlock> codec() {
        return CODEC;
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

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChoppingStumpBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.CHOPPING_STUMP_BE.get()) return null;
        return (lvl, pos, st, be) -> ChoppingStumpBlockEntity.tick(lvl, pos, st, (ChoppingStumpBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof AxeItem) {
            if (be.isEmpty()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                Block choppedLog = Block.byItem(be.getLogs().getItem());
                if (level.getRandom().nextFloat() < FIREWOOD_PER_LOG_CHANCE) {
                    Block.popResource(level, pos.above(),
                        new ItemStack(BannerboundAntiquity.FIREWOOD.get(), 1));
                }
                ItemStack remaining = be.getLogs().copy();
                remaining.shrink(1);
                be.setLogs(remaining);
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                level.playSound(null, pos, SoundType.WOOD.getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
                if (level instanceof ServerLevel sl && choppedLog != Blocks.AIR) {
                    sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, choppedLog.defaultBlockState()),
                        pos.getX() + 0.5, pos.getY() + 0.55, pos.getZ() + 0.5, 10, 0.25, 0.1, 0.25, 0.02);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(ItemTags.LOGS)) {
            int held = be.isEmpty() ? 0 : be.getLogs().getCount();
            boolean sameType = be.isEmpty() || ItemStack.isSameItemSameComponents(be.getLogs(), stack);
            if (!sameType || held >= MAX_LOGS) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                int move = Math.min(MAX_LOGS - held, stack.getCount());
                be.insert(stack.copyWithCount(held + move), player.getDirection().getOpposite());
                if (!player.hasInfiniteMaterials()) stack.shrink(move);
                level.playSound(null, pos, SoundType.WOOD.getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be) || be.isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            giveOrDrop(player, be.takeLogs());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be && !be.isEmpty()) {
            Block.popResource(level, pos, be.getLogs());
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    // initializeClient is deprecated-for-removal but IS the 1.21.1 hook for per-block client extensions.
    @Override
    @SuppressWarnings("removal")
    public void initializeClient(java.util.function.Consumer<IClientBlockExtensions> consumer) {
        consumer.accept(new IClientBlockExtensions() {
            @Override
            public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine engine) {
                if (level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be) {
                    engine.destroy(pos, be.getLogType().defaultBlockState());
                    return true;
                }
                return false;
            }
        });
    }
}
