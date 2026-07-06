package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.StuckSpear;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws every spear embedded in a mob, reading the mob's synced List of StuckSpear data
 * attachments. It runs inside the mob renderer's already-posed PoseStack (model space), so each
 * spear tracks the body for free -- the vanilla arrows-in-a-mob approach: no follow-entity, no
 * lag, no relog. Extends RenderLayer directly (not vanilla StuckInBodyLayer, which is bound to
 * PlayerModel) so it works for every living entity plus players. The orientation constants mirror
 * SpearProjectileRenderer so a stuck spear reads the same as it did in flight; they and the two
 * rotation corrections (YAW_CORRECTION matches the in-flight yaw-90, MODEL_PITCH_CORRECTION brings
 * the model's authored 45-degrees-up diagonal onto the stored pitch axis) are visual tunables --
 * the layer runs in model space (Y-down, X-flipped, origin lifted), so the in-flight values may
 * need a small in-game adjustment. TIP_X/Y/Z anchor the spear's TIP exactly at the captured hit
 * point; the head burying happens at CAPTURE time (SpearProjectile pushes the point inward along
 * the flight direction), so no fudge is applied here.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class StuckSpearLayer<T extends LivingEntity, M extends EntityModel<T>>
        extends RenderLayer<T, M> {
    private static final float MODEL_PITCH_CORRECTION = -45.0F;
    private static final float YAW_CORRECTION = -90.0F;
    // MUST stay 1.0: the ANCHOR translate runs after this scale; <1 floats the spear off its hit point.
    private static final float MODEL_SCALE = 1.0F;
    private static final float TIP_X = 30.0F / 16.0F;
    private static final float TIP_Y = 28.0F / 16.0F;
    private static final float TIP_Z = 8.5F / 16.0F;
    // +0.5 cancels ItemRenderer.render's internal translate(-0.5) so the TIP lands on the anchor.
    private static final float ANCHOR_X = 0.5F - TIP_X;
    private static final float ANCHOR_Y = 0.5F - TIP_Y;
    private static final float ANCHOR_Z = 0.5F - TIP_Z;

    private final ItemRenderer itemRenderer;

    public StuckSpearLayer(RenderLayerParent<T, M> parent, ItemRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        List<StuckSpear> stuck = entity.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        for (StuckSpear spear : stuck) {
            ItemStack stack = spear.stack();
            if (stack.isEmpty()) {
                continue;
            }
            pose.pushPose();
            pose.translate(spear.localX(), spear.localY(), spear.localZ());

            // Undo ONLY the renderer's X/Y mirror; body yaw is already in the pose -- do NOT re-add it.
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.mulPose(Axis.YP.rotationDegrees(spear.yaw() + YAW_CORRECTION));
            pose.mulPose(Axis.ZP.rotationDegrees(spear.pitch() + MODEL_PITCH_CORRECTION));

            pose.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
            pose.translate(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
            this.itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, packedLight,
                OverlayTexture.NO_OVERLAY, pose, buffer, entity.level(), entity.getId());
            pose.popPose();
        }
    }
}
