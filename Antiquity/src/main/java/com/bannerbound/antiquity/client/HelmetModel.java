package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The designed helmet's 3D geometry - the first piece of the player-designed armor system (ARMOR_PLAN.md).
 * Translated by hand from {@code assets/.../models/armor/helmet.bbmodel} (Blockbench "Modded Entity",
 * box-UV, 64x64 texture, flip-Y): each cube reads {@code addBox(x0, -y1, z0, w, h, d)} with
 * {@code texOffs(uv_offset)} - the canonical flip-Y export transform (verified against the bbmodel's
 * {@code REF_head} -> the vanilla {@code (-4,-8,-4,8,8,8)} head). The cubes are split into the four
 * zone bones the bbmodel groups them into - {@link #DOME} (crown plate), {@link #FRONT} (brow band +
 * two front uprights), {@link #CHEEKS} (both cheek guards), {@link #NECK} (rear gorget); the constants
 * match the bbmodel group names and are public so the design screen can iterate them. Each zone renders
 * independently via {@link #renderZone} with its own material (the {@code zones:{zone->material}} schema
 * from the plan; the texture is bound by the VertexConsumer's RenderType). All bones share the head/neck
 * origin {@code (0,0,0)}, so the same model attaches to the head bone for worn rendering later.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class HelmetModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "helmet_armor"), "main");

    public static final String DOME = "dome";
    public static final String FRONT = "front";
    public static final String CHEEKS = "cheeks";
    public static final String NECK = "neck";

    private final ModelPart root;

    public HelmetModel(ModelPart root) {
        this.root = root;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartPose zero = PartPose.offset(0.0F, 0.0F, 0.0F);

        root.addOrReplaceChild(DOME, CubeListBuilder.create()
                .texOffs(0, 16).addBox(-4.0F, -9.0F, -4.0F, 8.0F, 1.0F, 8.0F, CubeDeformation.NONE),
            zero);

        root.addOrReplaceChild(FRONT, CubeListBuilder.create()
                .texOffs(22, 25).addBox(-4.0F, -9.0F, -5.0F, 8.0F, 3.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(10, 32).addBox(-5.0F, -9.0F, -5.0F, 1.0F, 4.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(14, 32).addBox(4.0F, -9.0F, -5.0F, 1.0F, 4.0F, 1.0F, CubeDeformation.NONE),
            zero);

        // .mirror() is load-bearing: box-UV swaps the large east/west faces; mirror puts outer texture back outside.
        root.addOrReplaceChild(CHEEKS, CubeListBuilder.create().mirror()
                .texOffs(22, 29).addBox(-5.0F, -9.0F, 0.0F, 1.0F, 5.0F, 4.0F, CubeDeformation.NONE)
                .texOffs(32, 0).addBox(-5.0F, -9.0F, -4.0F, 1.0F, 4.0F, 4.0F, CubeDeformation.NONE)
                .texOffs(32, 8).addBox(4.0F, -9.0F, -4.0F, 1.0F, 4.0F, 4.0F, CubeDeformation.NONE)
                .texOffs(0, 32).addBox(4.0F, -9.0F, 0.0F, 1.0F, 5.0F, 4.0F, CubeDeformation.NONE),
            zero);

        root.addOrReplaceChild(NECK, CubeListBuilder.create()
                .texOffs(0, 25).addBox(-5.0F, -9.0F, 4.0F, 10.0F, 6.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(32, 16).addBox(-3.0F, -3.0F, 4.0F, 6.0F, 1.0F, 1.0F, CubeDeformation.NONE),
            zero);

        return LayerDefinition.create(mesh, 64, 64);
    }

    public void renderZone(String zone, PoseStack pose, VertexConsumer vc, int light, int overlay, int color) {
        root.getChild(zone).render(pose, vc, light, overlay, color);
    }
}
