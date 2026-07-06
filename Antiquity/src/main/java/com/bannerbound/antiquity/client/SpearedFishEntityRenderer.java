package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.SpearedFishEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.model.CodModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PufferfishBigModel;
import net.minecraft.client.model.SalmonModel;
import net.minecraft.client.model.TropicalFishModelA;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders a {@link SpearedFishEntity}: the spear's 3D model with the speared fish's real vanilla
 * model impaled near the tip, the WHOLE catch angled along the direction the spear was travelling
 * when it struck (pierce yaw/pitch, mirroring {@link SpearProjectileRenderer}'s in-flight
 * orientation) so spear + fish read as one rigid catch tilted the way it pierced, not a fixed
 * planted pose. When the catch is rope-tethered, the green rope is drawn from the base pose (no
 * per-catch spin) back to the thrower's hand; the owner is resolved via the synced entity id so it
 * works for a player OR a spear-fisher citizen (the client can't find a citizen by UUID alone).
 *
 * <p>The spear half reuses SpearProjectileRenderer's tip-anchor approach: SPEAR_MODEL_PITCH (-45)
 * brings the model's authored 45-degrees-up diagonal onto the flight axis, and ANCHOR_* = (0.5 - tip)
 * cancels ItemRenderer.renderStatic's internal translate(-0.5) so the TIP lands on the entity origin
 * with the shaft trailing back. The fish half bakes the vanilla cod/salmon/pufferfish/tropical models
 * (no custom layer definitions - vanilla already registers these; cod doubles as the fallback for
 * unexpected fish types). FISH_FLOP rolls the fish onto its side for a limp "caught" read; entity
 * models are authored Y-down, so the fish is mirrored with scale(-1,-1,1) and then its off-pivot body
 * translated onto the local origin so the flop rotates it about its own centre instead of throwing it
 * sideways. FISH_CENTER_Y is -22/16: vanilla fish models place the body ~22px (1.375 blocks) above
 * the root pivot - NOT the 1.501 lift mob renderers use (that's for ~2-block humanoids); salmon's
 * body is at 20px but the 0.12-block difference is negligible. FISH_CENTER_Z (~-3/16) pierces
 * mid-body. The tropical fish's base texture is an untinted silhouette, so it gets a solid
 * TROPICAL_TINT. Every numeric constant here is a visual tunable - the poses are eyeballed and meant
 * to be nudged in-game until the shaft reads as passing through the fish at the waterline (the same
 * convention the project's other renderers follow). getTextureLocation is unused (each part picks its
 * own render type) but the base class requires one.</p>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SpearedFishEntityRenderer extends EntityRenderer<SpearedFishEntity> {
    private static final float SPEAR_MODEL_PITCH = -45.0F;
    private static final float SPEAR_SCALE = 1.0F;
    private static final float TIP_X = 30.0F / 16.0F;
    private static final float TIP_Y = 28.0F / 16.0F;
    private static final float TIP_Z = 8.5F / 16.0F;
    private static final float ANCHOR_X = 0.5F - TIP_X;
    private static final float ANCHOR_Y = 0.5F - TIP_Y;
    private static final float ANCHOR_Z = 0.5F - TIP_Z;

    private static final float FISH_SCALE = 0.9F;
    private static final float FISH_X = 0.0F;
    private static final float FISH_Y = 0.0F;
    private static final float FISH_Z = 0.0F;
    private static final float FISH_PITCH = 0.0F;
    private static final float FISH_FLOP = 90.0F;
    private static final float FISH_CENTER_Y = -1.375F;
    private static final float FISH_CENTER_Z = -0.19F;
    private static final int TROPICAL_TINT = 0xFFF08000;
    private static final int NO_TINT = 0xFFFFFFFF;

    private static final ResourceLocation COD_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/cod.png");
    private static final ResourceLocation SALMON_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/salmon.png");
    private static final ResourceLocation PUFFERFISH_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/pufferfish.png");
    private static final ResourceLocation TROPICAL_TEX =
        ResourceLocation.withDefaultNamespace("textures/entity/fish/tropical_a.png");

    private final ItemRenderer itemRenderer;
    private final EntityModel<Entity> codModel;
    private final EntityModel<Entity> salmonModel;
    private final EntityModel<Entity> pufferfishModel;
    private final EntityModel<Entity> tropicalModel;

    public SpearedFishEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.itemRenderer = ctx.getItemRenderer();
        this.codModel = new CodModel<>(ctx.bakeLayer(ModelLayers.COD));
        this.salmonModel = new SalmonModel<>(ctx.bakeLayer(ModelLayers.SALMON));
        this.pufferfishModel = new PufferfishBigModel<>(ctx.bakeLayer(ModelLayers.PUFFERFISH_BIG));
        this.tropicalModel = new TropicalFishModelA<>(ctx.bakeLayer(ModelLayers.TROPICAL_FISH_SMALL));
    }

    @Override
    public ResourceLocation getTextureLocation(SpearedFishEntity entity) {
        return COD_TEX;
    }

    @Override
    public void render(SpearedFishEntity entity, float entityYaw, float partialTicks, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(entity.getPierceYaw() - 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(entity.getPiercePitch() + SPEAR_MODEL_PITCH));

        ItemStack spear = entity.getSpearItem();
        if (!spear.isEmpty()) {
            pose.pushPose();
            pose.scale(SPEAR_SCALE, SPEAR_SCALE, SPEAR_SCALE);
            pose.translate(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
            this.itemRenderer.renderStatic(spear, ItemDisplayContext.NONE, packedLight,
                OverlayTexture.NO_OVERLAY, pose, buffer, entity.level(), entity.getId());
            pose.popPose();
        }

        EntityModel<Entity> model = modelFor(entity.getFishType());
        ResourceLocation texture = textureFor(entity.getFishType());
        int tint = "minecraft:tropical_fish".equals(entity.getFishType()) ? TROPICAL_TINT : NO_TINT;
        pose.pushPose();
        pose.translate(FISH_X, FISH_Y, FISH_Z);
        pose.mulPose(Axis.ZP.rotationDegrees(FISH_FLOP));
        pose.mulPose(Axis.XP.rotationDegrees(FISH_PITCH));
        // Order matters: mirror the Y-down model FIRST, then re-center its off-pivot body so the flop
        // above rotated it about its own centre.
        pose.scale(-FISH_SCALE, -FISH_SCALE, FISH_SCALE);
        pose.translate(0.0F, FISH_CENTER_Y, FISH_CENTER_Z);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        model.renderToBuffer(pose, vc, packedLight, OverlayTexture.NO_OVERLAY, tint);
        pose.popPose();

        pose.popPose();

        if (entity.isTethered()
                && entity.getTetherOwner() instanceof net.minecraft.world.entity.LivingEntity owner) {
            RopeRenderer.render(pose, buffer, packedLight, partialTicks, entity, owner, 0.15F);
        }
        super.render(entity, entityYaw, partialTicks, pose, buffer, packedLight);
    }

    private EntityModel<Entity> modelFor(String fishType) {
        return switch (fishType) {
            case "minecraft:salmon" -> this.salmonModel;
            case "minecraft:pufferfish" -> this.pufferfishModel;
            case "minecraft:tropical_fish" -> this.tropicalModel;
            default -> this.codModel;
        };
    }

    private ResourceLocation textureFor(String fishType) {
        return switch (fishType) {
            case "minecraft:salmon" -> SALMON_TEX;
            case "minecraft:pufferfish" -> PUFFERFISH_TEX;
            case "minecraft:tropical_fish" -> TROPICAL_TEX;
            default -> COD_TEX;
        };
    }
}
