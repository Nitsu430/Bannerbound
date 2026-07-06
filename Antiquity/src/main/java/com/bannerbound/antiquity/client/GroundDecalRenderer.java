package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;
import com.bannerbound.antiquity.entity.GroundDecalEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
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
 * Draws a {@link GroundDecalEntity} flat on the ground: a blood splat (random blood_splat_N
 * texture, rotated by variant, full opacity) or a footprint track (<species>_footprint.png,
 * rotated to point along the animal's travel heading, ~50% opacity). Both fade out over their
 * config lifetime with a FADE_TICKS tail. Examined tracks (hunting_instincts research) lerp
 * white->cyan by {@link FootprintHighlight#strength} so a whole trail reads as one animal. An
 * examined decal also gets a translucent white "search cone": a ~150 degree wedge fanning toward
 * where the animal went, armed by the right-click and fading client-side over CONE_FADE_TICKS
 * (~3s; re-click re-arms). The cone is built pointing model-forward (-Z) and rotated by
 * 180 - yaw, the vanilla entity-facing convention; its opacity fades hub->rim. Decal quads are
 * emitted with both windings so they show from above and below. Blood textures are discovered
 * from the resource pack once (every blood_splat_N.png) and sorted so a variant index maps to a
 * stable texture.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class GroundDecalRenderer extends EntityRenderer<GroundDecalEntity> {
    private static final ResourceLocation FALLBACK =
        ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    private static final int FADE_TICKS = 40;
    private static final float CONE_FADE_TICKS = 60.0F;
    private static final float TRACK_OPACITY = 0.5F;
    private static List<ResourceLocation> bloodCache;

    public GroundDecalRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(GroundDecalEntity decal) {
        return decal.getKind() == GroundDecalEntity.KIND_TRACK ? footprintTexture(decal.getSpecies())
            : bloodTexture(decal.getVariant());
    }

    @Override
    public void render(GroundDecalEntity decal, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        boolean track = decal.getKind() == GroundDecalEntity.KIND_TRACK;
        int lifetime = track ? Config.FOOTPRINT_LIFETIME_TICKS.get() : Config.BLOOD_SPLAT_LIFETIME_TICKS.get();
        float remaining = lifetime - (decal.tickCount + partialTick);
        if (remaining > 0.0F) {
            float lifeFade = remaining < FADE_TICKS ? remaining / FADE_TICKS : 1.0F;
            if (track) {
                String species = decal.getSpecies();
                if (!species.isEmpty()) {
                    float tint = FootprintHighlight.strength(
                        decal.getGroupId(), decal.level().getGameTime(), partialTick);
                    int red = Mth.clamp((int) ((1.0F - tint) * 255.0F), 0, 255);
                    drawQuad(pose, buffer, packedLight, footprintTexture(species), 0.4F,
                        180.0F - decal.getHeading(), red, 255, 255, TRACK_OPACITY * lifeFade);
                }
            } else {
                List<ResourceLocation> textures = bloodTextures();
                if (!textures.isEmpty()) {
                    drawQuad(pose, buffer, packedLight, textures.get(Math.floorMod(decal.getVariant(), textures.size())),
                        0.5F, (decal.getVariant() * 47) % 360, 255, 255, 255, lifeFade);
                }
            }
            drawDirectionCone(pose, buffer, decal.getHeading(), decal.getRevealTick(), decal.tickCount, partialTick);
        }
        super.render(decal, entityYaw, partialTick, pose, buffer, packedLight);
    }

    private static void drawQuad(PoseStack pose, MultiBufferSource buffer, int light,
                                 ResourceLocation texture, float half, float rotationDeg,
                                 int red, int green, int blue, float opacity) {
        int a = Mth.clamp((int) (opacity * 255.0F), 0, 255);
        if (a <= 4) {
            return;
        }
        pose.pushPose();
        pose.translate(0.0, 0.02, 0.0); // lift just above the surface or the quad z-fights the ground
        pose.mulPose(Axis.YP.rotationDegrees(rotationDeg));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentCull(texture));
        emitDoubleSidedQuad(vc, pose.last(), light, red, green, blue, a, half);
        pose.popPose();
    }

    private static void emitDoubleSidedQuad(VertexConsumer vc, PoseStack.Pose pose, int light,
                                            int red, int green, int blue, int alpha, float h) {
        quadVertex(vc, pose, -h, -h, 0.0F, 0.0F, light, red, green, blue, alpha);
        quadVertex(vc, pose, -h, h, 0.0F, 1.0F, light, red, green, blue, alpha);
        quadVertex(vc, pose, h, h, 1.0F, 1.0F, light, red, green, blue, alpha);
        quadVertex(vc, pose, h, -h, 1.0F, 0.0F, light, red, green, blue, alpha);
        quadVertex(vc, pose, h, -h, 1.0F, 0.0F, light, red, green, blue, alpha);
        quadVertex(vc, pose, h, h, 1.0F, 1.0F, light, red, green, blue, alpha);
        quadVertex(vc, pose, -h, h, 0.0F, 1.0F, light, red, green, blue, alpha);
        quadVertex(vc, pose, -h, -h, 0.0F, 0.0F, light, red, green, blue, alpha);
    }

    private static void quadVertex(VertexConsumer vc, PoseStack.Pose pose, float x, float z,
                                   float u, float v, int light, int red, int green, int blue, int alpha) {
        vc.addVertex(pose.pose(), x, 0.0F, z)
            .setColor(red, green, blue, alpha)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static void drawDirectionCone(PoseStack pose, MultiBufferSource buffer, float headingYaw,
                                          int revealTick, int tickCount, float partialTick) {
        if (revealTick < 0) {
            return;
        }
        float age = (tickCount - revealTick) + partialTick;
        if (age < 0.0F || age >= CONE_FADE_TICKS) {
            return;
        }
        int hubAlpha = Mth.clamp((int) (0.6F * (1.0F - age / CONE_FADE_TICKS) * 255.0F), 0, 200);
        if (hubAlpha <= 4) {
            return;
        }
        pose.pushPose();
        pose.translate(0.0, 0.03, 0.0); // must sit above the 0.02 decal quad or they z-fight
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - headingYaw));
        int segments = 24;
        float radius = 2.4F;
        float halfArc = 75.0F;
        VertexConsumer vc = buffer.getBuffer(HuntingRenderTypes.BLOOD_CONE);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.toRadians(-halfArc + 2.0F * halfArc * i / segments);
            double a1 = Math.toRadians(-halfArc + 2.0F * halfArc * (i + 1) / segments);
            coneVertex(vc, pose.last(), 0.0F, 0.0F, hubAlpha);
            coneVertex(vc, pose.last(), (float) Math.sin(a0) * radius, -(float) Math.cos(a0) * radius, 0);
            coneVertex(vc, pose.last(), (float) Math.sin(a1) * radius, -(float) Math.cos(a1) * radius, 0);
        }
        pose.popPose();
    }

    private static void coneVertex(VertexConsumer vc, PoseStack.Pose pose, float x, float z, int alpha) {
        vc.addVertex(pose.pose(), x, 0.0F, z).setColor(255, 255, 255, alpha);
    }

    private static ResourceLocation footprintTexture(String species) {
        return ResourceLocation.fromNamespaceAndPath(
            BannerboundAntiquity.MODID, "textures/item/" + species + "_footprint.png");
    }

    private static ResourceLocation bloodTexture(int variant) {
        List<ResourceLocation> textures = bloodTextures();
        return textures.isEmpty() ? FALLBACK : textures.get(Math.floorMod(variant, textures.size()));
    }

    private static List<ResourceLocation> bloodTextures() {
        if (bloodCache == null) {
            List<ResourceLocation> found = new ArrayList<>(
                Minecraft.getInstance().getResourceManager().listResources("textures/item",
                    rl -> rl.getNamespace().equals(BannerboundAntiquity.MODID)
                        && rl.getPath().matches("textures/item/blood_splat_\\d+\\.png")).keySet());
            found.sort(Comparator.comparing(ResourceLocation::toString));
            bloodCache = found;
        }
        return bloodCache;
    }
}
