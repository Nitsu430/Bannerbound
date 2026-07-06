package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bannerbound.core.client.UnknownItemHelper;

import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Swaps the baked model for any item unknown to the local player's civ to
 * bannerbound:item/question_mark, so it renders as a question mark in inventory, hotbar, as a
 * dropped entity, and in third-person hands. Render code that explicitly wants the real model (e.g.
 * the research tooltip's "Unlocked Items:" grid, where question marks would defeat the point) sets
 * UnknownItemHelper's bypass flag, which short-circuits the swap.
 */
@Mixin(ItemRenderer.class)
@ApiStatus.Internal
public class ItemRendererMixin {
    @Inject(method = "getModel(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("RETURN"), cancellable = true)
    private void bannerbound$swapModelForUnknown(ItemStack stack, Level level, LivingEntity entity, int seed,
                                             CallbackInfoReturnable<BakedModel> cir) {
        if (UnknownItemHelper.isBypassActive()) {
            return;
        }
        if (!UnknownItemHelper.isUnknownForLocalPlayer(stack)) {
            return;
        }
        BakedModel qm = UnknownItemHelper.getQuestionMarkModel();
        if (qm != null) {
            cir.setReturnValue(qm);
        }
    }
}
