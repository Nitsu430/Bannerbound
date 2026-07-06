package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.bannerbound.antiquity.recipe.MortarDyeing;
import com.bannerbound.antiquity.recipe.MortarRecipeManager;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Mortar and Pestle block. Body + dynamic liquid are drawn by {@code MortarAndPestleRenderer}
 * (a BER, kept so the pestle Mix animation stays possible); the JSON block model is intentionally
 * empty. All interactions are refused while a grind is mixing or another player/NPC holds the
 * {@code WorkBlockLocks} lock. Right-click with a water bottle fills the bowl; a recipe ingredient
 * loads the bowl (item-output recipes like bricks/poison pastes are batchable up to MAX_BATCH and
 * grind a whole stack at once, liquid-output recipes like ink/dyes take exactly one; loading a
 * different ingredient hands the previous one back); a dyeable item on a bowl of finished dye dyes
 * up to DYE_BATCH = 8 of it (one dip's worth) and empties the bowl. Empty-handed right-click starts
 * the press-and-grind minigame - research-gated by Herbalism via {@code MortarGrind.canGrindAt},
 * reporting the lock in red rather than failing silently. Shift + empty hand takes the loaded batch
 * out; shift + empty bottle scoops the water back out, but that lives in {@code AntiquityEvents}
 * because vanilla skips block use when sneaking while holding an item. Breaking the block drops the
 * loaded ingredient and aborts any live grind session.
 */
public class MortarAndPestleBlock extends Block implements EntityBlock {
    public static final MapCodec<MortarAndPestleBlock> CODEC = simpleCodec(MortarAndPestleBlock::new);
    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 12.0, 13.0);
    private static final int DYE_BATCH = 8;

    public MortarAndPestleBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<MortarAndPestleBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MortarAndPestleBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        return type == BannerboundAntiquity.MORTAR_AND_PESTLE_BE.get()
            ? (lvl, pos, st, be) -> MortarAndPestleBlockEntity.tick(lvl, pos, st, (MortarAndPestleBlockEntity) be)
            : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false; // non-full-cube shape: must stay un-pathfindable or NPCs path onto the bowl and snag
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof MortarAndPestleBlockEntity mortar)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (mortar.isMixing()
                || com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            return ItemInteractionResult.CONSUME;
        }

        if (!mortar.hasLiquid() && isWaterBottle(stack)) {
            if (!level.isClientSide) {
                mortar.setLiquid("water");
                giveBack(player, hand, stack, new ItemStack(Items.GLASS_BOTTLE));
                level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        DyeColor dye = MortarDyeing.dyeColorFor(mortar.getLiquidId());
        if (dye != null && !stack.isEmpty()) {
            int batch = Math.min(DYE_BATCH, stack.getCount());
            ItemStack dyed = MortarDyeing.recolor(stack.copyWithCount(batch), dye);
            if (!dyed.isEmpty()) {
                if (!level.isClientSide) {
                    mortar.setLiquid("");
                    if (!player.hasInfiniteMaterials()) {
                        stack.shrink(batch);
                    }
                    if (stack.isEmpty()) {
                        player.setItemInHand(hand, dyed);
                    } else {
                        giveOrDrop(player, dyed);
                    }
                    level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }

        if (!stack.isEmpty() && MortarRecipeManager.hasRecipeFor(stack)) {
            int cap = MortarRecipeManager.isBatchable(stack)
                ? MortarAndPestleBlockEntity.MAX_BATCH : 1;
            ItemStack existing = mortar.getIngredient();
            boolean canTopUp = !existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack);
            int alreadyIn = canTopUp ? existing.getCount() : 0;
            int add = Math.min(cap - alreadyIn, stack.getCount());
            if (add <= 0) {
                return ItemInteractionResult.CONSUME;
            }
            if (!level.isClientSide) {
                if (canTopUp) {
                    mortar.setIngredient(existing.copyWithCount(alreadyIn + add));
                } else {
                    ItemStack previous = existing;
                    mortar.setIngredient(stack.copyWithCount(add));
                    if (!previous.isEmpty()) {
                        giveOrDrop(player, previous);
                    }
                }
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(add);
                }
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7F, 1.0F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof MortarAndPestleBlockEntity mortar)) {
            return InteractionResult.PASS;
        }
        if (mortar.isMixing()
                || com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            return InteractionResult.CONSUME;
        }
        // A non-empty hand here means a no-recipe item fell through useItemOn -> don't grind/extract.
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }

        if (player.isSecondaryUseActive()) {
            ItemStack inside = mortar.getIngredient();
            if (!inside.isEmpty()) {
                mortar.setIngredient(ItemStack.EMPTY);
                giveOrDrop(player, inside);
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.7F, 1.0F);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        }

        if (player instanceof net.minecraft.server.level.ServerPlayer sp
                && !mortar.getIngredient().isEmpty()
                && MortarRecipeManager.find(mortar.getIngredient(), mortar.getLiquidId()) != null) {
            if (!com.bannerbound.antiquity.MortarGrind.canGrindAt(level, pos)) {
                sp.displayClientMessage(net.minecraft.network.chat.Component
                    .translatable("bannerboundantiquity.mortar.locked")
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            } else {
                com.bannerbound.antiquity.MortarGrind.startSession(sp, pos, mortar);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MortarAndPestleBlockEntity mortar) {
            ItemStack loaded = mortar.getIngredient();
            if (!loaded.isEmpty()) {
                Block.popResource(level, pos, loaded);
            }
            com.bannerbound.antiquity.MortarGrind.abortSessionAt(pos);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    private static boolean isWaterBottle(ItemStack stack) {
        if (!stack.is(Items.POTION)) {
            return false;
        }
        PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
    }

    private static void giveBack(Player player, InteractionHand hand, ItemStack used, ItemStack result) {
        if (player.hasInfiniteMaterials()) {
            return;
        }
        used.shrink(1);
        if (used.isEmpty()) {
            player.setItemInHand(hand, result);
        } else {
            giveOrDrop(player, result);
        }
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
