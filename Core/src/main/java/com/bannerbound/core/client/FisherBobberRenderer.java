package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.FisherBobber;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * Renders the {@link FisherBobber}: a camera-facing quad textured with vanilla's fishing_hook
 * sprite plus a fishing line back to the owning citizen's hand. The line math is a simplified
 * port of vanilla FishingHookRenderer -- one straight segment rather than vanilla's 16 sub-
 * segment curve.
 *
 * <p>Quad: billboard scale only, NO extra Y rotation; a 180-degree spin flips it backwards so
 * face-culling hides it entirely. Line: drawn with RenderType.lines() (GL_LINES) not lineStrip,
 * because every fisher shares one batched line buffer and a strip would connect one fisher's
 * line-end to the next's line-start (visible especially under Iris, which flushes differently).
 *
 * <p>Hand anchor (approximateHandPosition, ported from FishingHookRenderer.getPlayerHandPos):
 * forward offset 0.8 (0.2 put the anchor inside the torso, so the line emerged through the
 * chest), lateral 0.35 in the body's right direction (negated for left-handed), Y at chest
 * height (eye height - 0.6) where the rod is held out. Reads BODY yaw not head yaw so the tip
 * tracks the body; the fisher goal locks body yaw to the outward cardinal during the cast to
 * keep the anchor stable. A seated fisher (sailing on a vessel) sits lower with the rod over
 * the gunwale, so the anchor is pulled down and in.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class FisherBobberRenderer extends EntityRenderer<FisherBobber> {
    private static final ResourceLocation TEXTURE_LOCATION =
        ResourceLocation.withDefaultNamespace("textures/entity/fishing_hook.png");
    private static final RenderType BOBBER_TYPE = RenderType.entityCutout(TEXTURE_LOCATION);

    public FisherBobberRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(FisherBobber entity) {
        return TEXTURE_LOCATION;
    }

    @Override
    public void render(FisherBobber bobber, float entityYaw, float partialTicks, PoseStack pose,
                       MultiBufferSource buffer, int packedLight) {
        CitizenEntity owner = bobber.getOwnerCitizen(bobber.level());

        pose.pushPose();
        pose.scale(0.5f, 0.5f, 0.5f);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        PoseStack.Pose entry = pose.last();
        Matrix4f matrix = entry.pose();
        VertexConsumer consumer = buffer.getBuffer(BOBBER_TYPE);
        vertex(consumer, matrix, entry, packedLight, 0.0f, 0, 0, 1);
        vertex(consumer, matrix, entry, packedLight, 1.0f, 0, 1, 1);
        vertex(consumer, matrix, entry, packedLight, 1.0f, 1, 1, 0);
        vertex(consumer, matrix, entry, packedLight, 0.0f, 1, 0, 0);
        pose.popPose();

        if (owner != null) {
            Vec3 handWorld = approximateHandPosition(owner, partialTicks);
            Vec3 bobberWorld = new Vec3(
                Mth.lerp(partialTicks, bobber.xo, bobber.getX()),
                Mth.lerp(partialTicks, bobber.yo, bobber.getY()) + 0.25,
                Mth.lerp(partialTicks, bobber.zo, bobber.getZ()));
            float dx = (float) (handWorld.x - bobberWorld.x);
            float dy = (float) (handWorld.y - bobberWorld.y);
            float dz = (float) (handWorld.z - bobberWorld.z);
            // GL_LINES not lineStrip: fishers share one batched buffer; a strip would join separate lines.
            VertexConsumer line = buffer.getBuffer(RenderType.lines());
            Matrix4f m = pose.last().pose();
            line.addVertex(m, 0, 0.25f, 0).setColor(0, 0, 0, 255).setNormal(entry, 0f, 1f, 0f);
            line.addVertex(m, dx, dy, dz).setColor(0, 0, 0, 255).setNormal(entry, 0f, 1f, 0f);
        }

        super.render(bobber, entityYaw, partialTicks, pose, buffer, packedLight);
    }

    private static Vec3 approximateHandPosition(CitizenEntity citizen, float partialTicks) {
        double cx = Mth.lerp(partialTicks, citizen.xo, citizen.getX());
        double cy = Mth.lerp(partialTicks, citizen.yo, citizen.getY());
        double cz = Mth.lerp(partialTicks, citizen.zo, citizen.getZ());
        float bodyYaw = Mth.lerp(partialTicks, citizen.yBodyRotO, citizen.yBodyRot);
        double rad = Math.toRadians(bodyYaw);
        double sinY = Math.sin(rad);
        double cosY = Math.cos(rad);
        int armOffset = (citizen.getMainArm() == HumanoidArm.RIGHT) ? 1 : -1;
        boolean seated = citizen.isPassenger();
        double forward = seated ? 0.55 : 0.8;
        double handY = cy + citizen.getEyeHeight() - (seated ? 0.85 : 0.6);
        double handX = cx - cosY * armOffset * 0.35 - sinY * forward;
        double handZ = cz - sinY * armOffset * 0.35 + cosY * forward;
        return new Vec3(handX, handY, handZ);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, PoseStack.Pose entry,
                                int packedLight, float u, int v, int texU, int texV) {
        consumer.addVertex(matrix, u - 0.5f, (float) v - 0.5f, 0.0f)
            .setColor(0xFFFFFFFF)
            .setUv((float) texU, (float) texV)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(packedLight)
            .setNormal(entry, 0.0f, 1.0f, 0.0f);
    }
}
