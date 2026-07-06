package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Fire Sticks - a primitive reusable fire starter. Drawn like a bow for CHARGE_TICKS (2s);
 * releasing early cancels, so {@link #finishUsingItem} only fires on a full hold. On completion
 * it ignites whatever the player is aiming at, in priority order: a bloomery, but ONLY through an
 * open door (a closed bloomery can't be lit inside); a kiln, which has no door and lights
 * directly; an unlit campfire (e.g. one built from firewood as a cook-fire); otherwise it places
 * a fire block on the targeted face if one fits there. Each successful light wears the sticks
 * (friction fire) - they break after a handful of uses.
 */
public class FireSticksItem extends Item {
    private static final int CHARGE_TICKS = 40;

    public FireSticksItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return CHARGE_TICKS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof Player player) {
            HitResult hit = player.pick(5.0, 1.0F, false);
            if (hit instanceof BlockHitResult blockHit && tryIgnite(level, blockHit)) {
                level.playSound(null, player.blockPosition(), SoundEvents.FLINTANDSTEEL_USE,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
                EquipmentSlot slot = entity.getUsedItemHand() == InteractionHand.OFF_HAND
                    ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
                stack.hurtAndBreak(1, player, slot);
            }
        }
        return stack;
    }

    private static boolean tryIgnite(Level level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BloomeryBlockEntity bloomery = BloomeryBlock.getController(level, pos);
        if (bloomery != null) {
            if (bloomery.isOpen()) {
                bloomery.ignite();
                return true;
            }
            return false;
        }
        KilnBlockEntity kiln = KilnBlock.getController(level, pos);
        if (kiln != null) {
            kiln.ignite();
            return true;
        }
        BlockState targeted = level.getBlockState(pos);
        if (targeted.getBlock() instanceof CampfireBlock && !targeted.getValue(CampfireBlock.LIT)) {
            level.setBlock(pos, targeted.setValue(CampfireBlock.LIT, true), Block.UPDATE_ALL);
            return true;
        }
        BlockPos firePos = pos.relative(hit.getDirection());
        if (BaseFireBlock.canBePlacedAt(level, firePos, hit.getDirection())) {
            level.setBlockAndUpdate(firePos, BaseFireBlock.getState(level, firePos));
            return true;
        }
        return false;
    }
}
