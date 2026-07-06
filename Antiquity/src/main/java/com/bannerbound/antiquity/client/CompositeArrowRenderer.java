package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.CompositeArrowEntity;
import com.bannerbound.antiquity.item.ArrowParts;
import com.bannerbound.antiquity.recipe.ArrowPart;
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
 * Renders the modular arrow as a vanilla-arrow-shaped projectile in THREE layers -- back, then
 * shaft, then tip -- each from its own data-driven {@code textures/projectiles} PNG, picked from the
 * parts on the entity's pickup stack (a part with no projectile texture contributes no layer). The
 * three part textures are designed to be pixel-DISJOINT (verified: their union is the full arrow and
 * no two paint the same texel), so each layer just draws the complete vanilla arrow geometry (head
 * cross + four fins, mirroring vanilla ArrowRenderer) and the cutout shader discards the texels it
 * doesn't own -- no UV splitting and no z-fight offset needed (unlike {@link BlowdartRenderer},
 * whose poison layer repaints the shaft's texels and so had to be lifted), and layer order is purely
 * cosmetic. FALLBACK exists only to satisfy the abstract getTextureLocation; the real textures are
 * bound per layer in render().
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class CompositeArrowRenderer extends EntityRenderer<CompositeArrowEntity> {
    private static final ResourceLocation FALLBACK = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/projectiles/flint_arrow_tip.png");

    public CompositeArrowRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(CompositeArrowEntity entity) {
        return FALLBACK;
    }

    @Override
    public void render(CompositeArrowEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(
            Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTicks, entity.xRotO, entity.getXRot())));
        float shake = (float) entity.shakeTime - partialTicks;
        if (shake > 0.0F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(-Mth.sin(shake * 3.0F) * shake));
        }
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.scale(0.05625F, 0.05625F, 0.05625F);
        poseStack.translate(-4.0F, 0.0F, 0.0F);

        // The shared entity buffer builder must finish each cutout layer FULLY before the next
        // getBuffer call, or the builder ends mid-layer -> "Not building" crash.
        renderLayer(poseStack, buffer, packedLight,
            ArrowParts.projectileTexture(ArrowPart.SLOT_BACK, entity.back()));
        renderLayer(poseStack, buffer, packedLight,
            ArrowParts.projectileTexture(ArrowPart.SLOT_SHAFT, entity.shaft()));
        renderLayer(poseStack, buffer, packedLight,
            ArrowParts.projectileTexture(ArrowPart.SLOT_TIP, entity.tip()));

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private void renderLayer(PoseStack poseStack, MultiBufferSource buffer, int light,
                             ResourceLocation texture) {
        if (texture == null) return;
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(texture));
        PoseStack.Pose ph = poseStack.last();
        vertex(ph, vc, -7, -2, -2, 0.0F, 0.15625F, -1, 0, 0, light);
        vertex(ph, vc, -7, -2, 2, 0.15625F, 0.15625F, -1, 0, 0, light);
        vertex(ph, vc, -7, 2, 2, 0.15625F, 0.3125F, -1, 0, 0, light);
        vertex(ph, vc, -7, 2, -2, 0.0F, 0.3125F, -1, 0, 0, light);
        vertex(ph, vc, -7, 2, -2, 0.0F, 0.15625F, 1, 0, 0, light);
        vertex(ph, vc, -7, 2, 2, 0.15625F, 0.15625F, 1, 0, 0, light);
        vertex(ph, vc, -7, -2, 2, 0.15625F, 0.3125F, 1, 0, 0, light);
        vertex(ph, vc, -7, -2, -2, 0.0F, 0.3125F, 1, 0, 0, light);
        // Fin loop rotates 4 x 90deg = 360deg, leaving the pose exactly as it began (no push/pop).
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            PoseStack.Pose pf = poseStack.last();
            vertex(pf, vc, -8, -2, 0, 0.0F, 0.0F, 0, 1, 0, light);
            vertex(pf, vc, 8, -2, 0, 0.5F, 0.0F, 0, 1, 0, light);
            vertex(pf, vc, 8, 2, 0, 0.5F, 0.15625F, 0, 1, 0, light);
            vertex(pf, vc, -8, 2, 0, 0.0F, 0.15625F, 0, 1, 0, light);
        }
    }

    private void vertex(PoseStack.Pose pose, VertexConsumer vc, int x, int y, int z,
                        float u, float v, int nx, int ny, int nz, int light) {
        vc.addVertex(pose, (float) x, (float) y, (float) z)
            .setColor(-1)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, (float) nx, (float) nz, (float) ny);
    }
}
