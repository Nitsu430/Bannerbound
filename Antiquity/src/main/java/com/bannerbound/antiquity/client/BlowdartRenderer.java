package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders the blowdart as a vanilla-arrow-shaped projectile (oriented along flight, sticks in blocks)
 * in TWO layers, like a tipped arrow: the base {@code dart.png}, then {@code dart_poison_layer.png}
 * tinted by the dart's {@link com.bannerbound.antiquity.poison.PoisonType#tintColor()} (so each poison's
 * coating reads as its own colour). Geometry mirrors vanilla {@code ArrowRenderer}, except each fin
 * quad is split at {@code X_SPLIT}/{@code U_SPLIT} into a shaft half (base texture) and a tip half
 * (tinted poison layer). The two halves are COPLANAR and ADJACENT - they meet edge-to-edge and never
 * overlap - so the tip just changes colour in place: no z-fighting and no raised shell. A scaled or
 * normal-lifted overlay was rejected because the poison pixels sit at the dart's centre, exactly
 * where a scale gives ~0 separation. {@code getTextureLocation} only satisfies the abstract method;
 * {@code render} binds each layer's texture itself.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class BlowdartRenderer extends EntityRenderer<BlowdartProjectile> {
    private static final ResourceLocation DART = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/projectiles/dart.png");
    private static final ResourceLocation POISON_LAYER = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/projectiles/dart_poison_layer.png");

    public BlowdartRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(BlowdartProjectile entity) {
        return DART;
    }

    @Override
    public void render(BlowdartProjectile entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        renderDart(entity, partialTicks, poseStack, buffer, packedLight, entity.getPoison().tintColor());
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    // Poison pixels start at texel column 7 of the 32px strip; fin u maps x[-8,8] -> u[0,0.5], so u=7/32 is x=-1.
    private static final float U_SPLIT = 7.0F / 32.0F;
    private static final int X_SPLIT = -1;

    private void renderDart(BlowdartProjectile entity, float partialTicks, PoseStack poseStack,
                            MultiBufferSource buffer, int light, int tipColor) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTicks, entity.xRotO, entity.getXRot())));
        float shake = (float) entity.shakeTime - partialTicks;
        if (shake > 0.0F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(-Mth.sin(shake * 3.0F) * shake));
        }
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.scale(0.05625F, 0.05625F, 0.05625F);
        poseStack.translate(-4.0F, 0.0F, 0.0F);
        VertexConsumer base = buffer.getBuffer(RenderType.entityCutout(DART));
        PoseStack.Pose ph = poseStack.last();
        vertex(ph, base, -7, -2, -2, 0.0F, 0.15625F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, -2, 2, 0.15625F, 0.15625F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, 2, 0.15625F, 0.3125F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, -2, 0.0F, 0.3125F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, -2, 0.0F, 0.15625F, 1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, 2, 0.15625F, 0.15625F, 1, 0, 0, light, -1);
        vertex(ph, base, -7, -2, 2, 0.15625F, 0.3125F, 1, 0, 0, light, -1);
        vertex(ph, base, -7, -2, -2, 0.0F, 0.3125F, 1, 0, 0, light, -1);
        // Rotates 4 x 90 = 360 degrees total, leaving the pose unchanged for the tip pass below.
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            PoseStack.Pose pf = poseStack.last();
            vertex(pf, base, -8, -2, 0, 0.0F, 0.0F, 0, 1, 0, light, -1);
            vertex(pf, base, X_SPLIT, -2, 0, U_SPLIT, 0.0F, 0, 1, 0, light, -1);
            vertex(pf, base, X_SPLIT, 2, 0, U_SPLIT, 0.15625F, 0, 1, 0, light, -1);
            vertex(pf, base, -8, 2, 0, 0.0F, 0.15625F, 0, 1, 0, light, -1);
        }
        // This getBuffer ends the shared DART builder: write ALL base vertices above or "Not building" crash.
        VertexConsumer tip = buffer.getBuffer(RenderType.entityCutout(POISON_LAYER));
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            PoseStack.Pose pf = poseStack.last();
            vertex(pf, tip, X_SPLIT, -2, 0, U_SPLIT, 0.0F, 0, 1, 0, light, tipColor);
            vertex(pf, tip, 8, -2, 0, 0.5F, 0.0F, 0, 1, 0, light, tipColor);
            vertex(pf, tip, 8, 2, 0, 0.5F, 0.15625F, 0, 1, 0, light, tipColor);
            vertex(pf, tip, X_SPLIT, 2, 0, U_SPLIT, 0.15625F, 0, 1, 0, light, tipColor);
        }
        poseStack.popPose();
    }

    private void vertex(PoseStack.Pose pose, VertexConsumer vc, int x, int y, int z,
                        float u, float v, int nx, int ny, int nz, int light, int color) {
        vc.addVertex(pose, (float) x, (float) y, (float) z)
            .setColor(color)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, (float) nx, (float) nz, (float) ny);
    }
}
