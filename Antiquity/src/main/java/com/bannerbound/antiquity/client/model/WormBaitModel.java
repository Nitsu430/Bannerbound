package com.bannerbound.antiquity.client.model;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.WormBaitEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

public class WormBaitModel<T extends Entity> extends EntityModel<T> {
    // This layer location should be baked with EntityRendererProvider.Context in the entity renderer and passed into this model's constructor
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "wormbait"), "main");
    private final ModelPart back;
    private final ModelPart front;

    public WormBaitModel(ModelPart root) {
        this.back = root.getChild("back");
        this.front = root.getChild("front");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition back = partdefinition.addOrReplaceChild("back", CubeListBuilder.create().texOffs(0, 0).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 23.5F, 0.0F));

        PartDefinition front = partdefinition.addOrReplaceChild("front", CubeListBuilder.create().texOffs(0, 4).addBox(-0.5F, -0.5F, -3.0F, 1.0F, 1.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 23.5F, 0.0F));

        return LayerDefinition.create(meshdefinition, 8, 8);
    }

    @Override
    public void setupAnim(@NotNull Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.front.yRot = 0.0F;
        this.back.yRot = 0.0F;

        if (entity instanceof WormBaitEntity bait) {
            WormBaitEntity.Phase currentPhase = bait.getBaitPhase();

            if (currentPhase == WormBaitEntity.Phase.FLEE || currentPhase == WormBaitEntity.Phase.FALLING) {
                float wiggleSpeed = 0.6F;
                float wiggleAmount = 0.4F;

                float wiggleAngle = (float) Math.sin(ageInTicks * wiggleSpeed) * wiggleAmount;

                this.front.yRot = wiggleAngle;
                this.back.yRot = -wiggleAngle;
            }
        }

    }
    @Override
    public void renderToBuffer(@NotNull PoseStack poseStack, @NotNull VertexConsumer vertexConsumer, int packedLight, int packedOverlay, int color) {
        back.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
        front.render(poseStack, vertexConsumer, packedLight, packedOverlay, color);
    }
}