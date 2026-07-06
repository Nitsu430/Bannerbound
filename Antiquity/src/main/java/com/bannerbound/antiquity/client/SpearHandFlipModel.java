package com.bannerbound.antiquity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * Wraps a spear item's baked model so that, ONLY while the entity holding it is mid-use with the
 * SPEAR wind-up (raise-over-shoulder throw), the held model is flipped 180 degrees at render time.
 * The authored model is oriented for the normal hold; the SPEAR raise would otherwise show it
 * backwards, and a static model can't tell the two apart (both use {@code thirdperson_righthand}).
 *
 * <p>{@code applyTransform} is the single choke point both the first- and third-person hand renders
 * pass through, and it is never hit for GUI / ground / fixed / NONE - so the inventory icon, dropped
 * item, item frame, the in-hand normal grip, and the flying/stuck projectile (rendered via NONE) are
 * all untouched. Only THIRD person gets the flip: first person already looked right (both share the
 * model's display transform; only the use-pose differs). {@code applyTransform} has no entity, so the
 * use-check keys on {@link HeldItemRenderContext} - the entity whose model is being drawn right now.
 * Keying on a global ({@code Minecraft#player}) was the "rotating one spear rotates all spears" bug:
 * it flipped EVERY on-screen spear whenever the local player raised one; per-entity context flips
 * each spear only while its own holder is winding up.</p>
 *
 * <p>The PIVOT_* constants are the model's geometric centre in the frame ItemRenderer renders in
 * (model bounds / 16, minus the renderer's internal 0.5 offset: the model spans ~(0..1.875, 0..1.75,
 * 0.5..0.56) blocks, so the centre sits at ~(0.52, 0.375, 0.03)). Pivoting there instead of
 * (0.5, 0.5, 0.5) keeps the flip seated in the fist rather than throwing the spear sideways or down.
 * The FLIP_*_DEG rotations apply X -> Y -> Z about that pivot; YP(180) alone left the spear seated
 * but blade-down, so the working flip is about Z. All six are pure visual tunables - nudge them (or
 * swap which axis is 180, or try 90) if the raised spear sits or points slightly off.</p>
 */
@OnlyIn(Dist.CLIENT)
public class SpearHandFlipModel extends BakedModelWrapper<BakedModel> {
    private static final float PIVOT_X = 0.52F;
    private static final float PIVOT_Y = 0.375F;
    private static final float PIVOT_Z = 0.03F;

    private static final float FLIP_X_DEG = 0.0F;
    private static final float FLIP_Y_DEG = 0.0F;
    private static final float FLIP_Z_DEG = 180.0F;

    public SpearHandFlipModel(BakedModel original) {
        super(original);
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext ctx, PoseStack pose, boolean applyLeftHandTransform) {
        BakedModel result = super.applyTransform(ctx, pose, applyLeftHandTransform);
        if (isThirdPersonHand(ctx) && isHolderRaisingSpear()) {
            pose.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
            if (FLIP_X_DEG != 0.0F) pose.mulPose(Axis.XP.rotationDegrees(FLIP_X_DEG));
            if (FLIP_Y_DEG != 0.0F) pose.mulPose(Axis.YP.rotationDegrees(FLIP_Y_DEG));
            if (FLIP_Z_DEG != 0.0F) pose.mulPose(Axis.ZP.rotationDegrees(FLIP_Z_DEG));
            pose.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
        }
        return result;
    }

    private static boolean isThirdPersonHand(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }

    private static boolean isHolderRaisingSpear() {
        LivingEntity holder = HeldItemRenderContext.current();
        if (holder == null || !holder.isUsingItem()) {
            return false;
        }
        ItemStack use = holder.getUseItem();
        return !use.isEmpty() && use.getUseAnimation() == UseAnim.SPEAR;
    }
}
