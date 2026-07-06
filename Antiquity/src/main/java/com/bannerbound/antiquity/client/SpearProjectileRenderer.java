package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.SpearProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders the flying {@link SpearProjectile} as its 3D spear model, oriented along flight, plus the
 * green rope back to the thrower's hand when the throw is rope-tethered (the owner check is
 * LivingEntity so it covers a player OR a spear-fisher citizen). The model is pulled from the carried
 * spear stack and drawn in {@link ItemDisplayContext#NONE} - the spear item models route NONE to the
 * 3D geometry (see models/item/*_spear.json), so this gets the raw model with no in-hand transform
 * baked in.
 *
 * <p>The authored spear model lies along the diagonal of its local XY plane (butt at the bottom, tip
 * up-and-to-the-right, ~45 degrees up). After the arrow-style yaw/pitch orientation,
 * MODEL_PITCH_CORRECTION (-45) rotates that diagonal down onto the flight axis, and the anchor
 * translate moves the off-origin model onto the entity. The spear's TIP (at ~(30, 28, 8.5) model
 * units; units / 16 = blocks) is anchored at the entity origin - the hit point - so the shaft extends
 * back OUT of the ground / mob and only the head is buried, instead of the model floating beside a
 * mob or sinking half-under the surface. TIP_BURY backs the tip out along the shaft so the head still
 * reads as embedded but not fully swallowed (0 = tip exactly at the hit point; ~0.10-0.18 looks
 * "stuck"). The ANCHOR_* offsets are (0.5 - tip), NOT (-tip), because ItemRenderer.renderStatic
 * applies an internal translate(-0.5,-0.5,-0.5) before drawing; using (-tip) pushes the whole
 * off-origin model ~0.5 per axis away and the rotation swings it far off the hitbox (the "spear
 * floating beside its box" bug). MODEL_PITCH_CORRECTION, MODEL_SCALE, TIP_BURY and the tip point are
 * visual tunables - tweak in-game until the spear points the way it flies and sits centered. TEXTURE
 * is unused for item-model rendering (the model carries its own texture) but the base class requires
 * one, so it points at a spear model texture.</p>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SpearProjectileRenderer extends EntityRenderer<SpearProjectile> {
    private static final float MODEL_PITCH_CORRECTION = -45.0F;
    private static final float MODEL_SCALE = 1.0F;

    private static final float TIP_X = 30.0F / 16.0F;
    private static final float TIP_Y = 28.0F / 16.0F;
    private static final float TIP_Z = 8.5F / 16.0F;
    private static final float TIP_BURY = 0.15F;
    private static final float ANCHOR_X = 0.5F - TIP_X + TIP_BURY;
    private static final float ANCHOR_Y = 0.5F - TIP_Y + TIP_BURY;
    private static final float ANCHOR_Z = 0.5F - TIP_Z;

    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "textures/item/wooden_spear_model.png");

    private final ItemRenderer itemRenderer;

    public SpearProjectileRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
    }

    @Override
    public ResourceLocation getTextureLocation(SpearProjectile entity) {
        return TEXTURE;
    }

    @Override
    public void render(SpearProjectile spear, float entityYaw, float partialTicks, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        ItemStack stack = spear.getSpearItem();
        if (stack.isEmpty()) {
            super.render(spear, entityYaw, partialTicks, pose, buffer, packedLight);
            return;
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, spear.yRotO, spear.getYRot()) - 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(
            Mth.lerp(partialTicks, spear.xRotO, spear.getXRot()) + MODEL_PITCH_CORRECTION));
        pose.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        pose.translate(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
        this.itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, packedLight,
            OverlayTexture.NO_OVERLAY, pose, buffer, spear.level(), spear.getId());
        pose.popPose();
        if (spear.isRopeTethered() && spear.getOwner() instanceof net.minecraft.world.entity.LivingEntity owner) {
            RopeRenderer.render(pose, buffer, packedLight, partialTicks, spear, owner, 0.1F);
        }
        super.render(spear, entityYaw, partialTicks, pose, buffer, packedLight);
    }
}
