package com.bannerbound.antiquity.client.model;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.WormBaitEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

@OnlyIn(Dist.CLIENT)
public class WormBaitRenderer extends EntityRenderer<WormBaitEntity> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "textures/entity/worm_bait.png");

    private final WormBaitModel<WormBaitEntity> model;

    public WormBaitRenderer(EntityRendererProvider.Context context) {
        super(context);

        this.model = new WormBaitModel<>(context.bakeLayer(WormBaitModel.LAYER_LOCATION));
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull WormBaitEntity wormBaitEntity) {
        return TEXTURE;
    }

    @Override
    public void render(@NotNull WormBaitEntity entity, float entityYaw, float partialTicks, @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        float yaw = entityYaw;
        float basePitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());

        float pitch = basePitch;
        float rotProgress = 0.0F;
        float sinkProgress = 0.0F;
        float ageInTicks = entity.tickCount + partialTicks;

        if (entity.getBaitPhase() == WormBaitEntity.Phase.FLEE) {
            float fleeTicks = entity.getFleeTicks() + partialTicks;
            rotProgress = Math.min(1.0F, fleeTicks / 60.0F);
            sinkProgress = Math.min(1.0F, fleeTicks / 120.0F);

            pitch = Mth.lerp(rotProgress, basePitch, 90.0F);

            float maxDepth = 0.8F;
            poseStack.translate(0.0F, -sinkProgress * maxDepth, 0.0F);
        }

        if (entity.getBaitPhase() == WormBaitEntity.Phase.WATER_CHAOS) {
            float fleeTicks = entity.getFleeTicks() + partialTicks;
            yaw = yaw + fleeTicks * 100.0F * (fleeTicks / 240.0F);
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));

        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);

        this.model.setupAnim(entity, 0.0F, 0.0F, ageInTicks, yaw, pitch);

        VertexConsumer vertexConsumer = buffer.getBuffer(this.model.renderType(this.getTextureLocation(entity)));
        this.model.renderToBuffer(poseStack, vertexConsumer, packedLight, OverlayTexture.NO_OVERLAY);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }
}
