package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

/**
 * Suppresses VANILLA's brown leash ribbon. Bannerbound draws EVERY leash itself as the plant-fibre
 * green rope used everywhere else (rope fences / spear-fishing / herding); see Antiquity's
 * RopeRenderEvents. Without this cancel, vanilla's own leash render would double-draw a thin brown
 * line over ours. Cancelling renderLeash at HEAD removes the vanilla draw for every leashed entity;
 * the green replacement is drawn in a RenderLevelStageEvent keyed off the entity's vanilla
 * isLeashed() state, so no custom data is needed - vanilla leashing drives the link. Note: in
 * NeoForge 1.21.1 leash rendering lives on the base EntityRenderer (the Leashable backport made any
 * entity renderable-with-a-leash), not on MobRenderer, so this must mix into EntityRenderer.
 */
@Mixin(EntityRenderer.class)
@ApiStatus.Internal
public class EntityRendererMixin {

    @Inject(method = "renderLeash", at = @At("HEAD"), cancellable = true)
    private void bannerbound$suppressVanillaLeash(Entity entity, float partialTicks, PoseStack poseStack,
                                                  MultiBufferSource buffer, Entity leashHolder, CallbackInfo ci) {
        ci.cancel();
    }
}
