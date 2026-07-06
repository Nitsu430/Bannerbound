package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders the /bannerbound simulate decorative crowd (ClientSimulationState / ClientCrowd) as real
 * animated HumanoidModels wearing the actual citizen skins -- visually indistinguishable from real
 * citizens, just non-interactive and entirely client-simulated. Purely decorative; the server has no
 * idea these exist (the whole thesis of the stress test). CLIENT-dist EventBusSubscriber: ticks the
 * crowd each client tick, resets on logout, and draws during RenderLevelStage AFTER_TRANSLUCENT_BLOCKS.
 *
 * v2 replaced v1 flat billboards (black blobs up close from wrong skin UVs); movers now read as people
 * at every distance the ClientSimulationState.MAX_MOVERS cap reaches. A cheap far tier (LOD/billboard)
 * is a deferred optimization. Agents are position/facing/gait interpolated between ticks for smooth
 * 60fps motion, culled behind the camera and past CULL_DISTANCE, and capped at renderCap() per frame.
 *
 * Two rendering constraints: (1) with no backing entity we can't call setupAnim(null,...) (it NPEs),
 * so poseAgent writes the walk cycle onto the ModelParts directly with vanilla math -- one continuous
 * pose that blends a distance-driven walk with a standing idle gesture by amount (0 = standing,
 * 1 = walking) so start/stop eases with no pose pop. (2) STEP_PER_BLOCK gives one full leg cycle (two
 * steps) per ~1.6 blocks walked, distance-driven so feet always match ground speed.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class CrowdRenderer {
    private static final double STEP_PER_BLOCK = (Math.PI * 2.0) / 1.6;

    private static HumanoidModel<LivingEntity> wideModel;
    private static HumanoidModel<LivingEntity> slimModel;

    private CrowdRenderer() {
    }

    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSimulationState.reset();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientCrowd.tick();
    }

    private static void ensureModels() {
        if (wideModel != null) return;
        var set = Minecraft.getInstance().getEntityModels();
        wideModel = new HumanoidModel<>(set.bakeLayer(ModelLayers.PLAYER));
        slimModel = new HumanoidModel<>(set.bakeLayer(ModelLayers.PLAYER_SLIM));
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ClientSimulationState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ensureModels();

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double tSeconds = (mc.level.getGameTime() + partial) / 20.0;
        Era era = ClientSimulationState.era();

        double fy = Math.toRadians(yaw);
        double fp = Math.toRadians(pitch);
        double fx = -Math.sin(fy) * Math.cos(fp);
        double fz = Math.cos(fy) * Math.cos(fp);

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        int near = 0, mid = 0, far = 0, culled = 0, rendered = 0;
        for (ClientCrowd.Agent a : ClientCrowd.agents()) {
            double rx = a.prevX + (a.x - a.prevX) * partial;
            double ry = a.prevY + (a.y - a.prevY) * partial;
            double rz = a.prevZ + (a.z - a.prevZ) * partial;
            float facingR = a.prevFacing + ClientCrowd.wrapDeg(a.facing - a.prevFacing) * partial;
            double gaitR = a.prevGait + (a.gait - a.prevGait) * partial;
            float headYawR = a.prevHeadYaw + (a.headYaw - a.prevHeadYaw) * partial;
            float amountR = a.prevGaitAmount + (a.gaitAmount - a.prevGaitAmount) * partial;

            double dx = rx - cam.x, dz = rz - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > ClientSimulationState.CULL_DISTANCE) { culled++; continue; }
            if (dist > 0.01 && (dx * fx + dz * fz) / dist < -0.2) { culled++; continue; }
            if (rendered >= ClientSimulationState.renderCap()) { culled++; continue; }

            boolean male = (a.vseed & 1L) == 0L;
            CitizenGender gender = male ? CitizenGender.MALE : CitizenGender.FEMALE;
            HumanoidModel<LivingEntity> model = male ? wideModel : slimModel;
            int variant = (int) ((a.vseed >>> 1) & 0x7fffffff);
            ResourceLocation skin = CitizenSkins.texture(gender, era, variant);

            poseAgent(model, gaitR, amountR, headYawR, tSeconds, a.idlePhase);

            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(rx, ry + 1, rz));
            VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(skin));

            pose.pushPose();
            pose.translate(rx - cam.x, ry - cam.y, rz - cam.z);
            pose.mulPose(Axis.YP.rotationDegrees(180.0f - facingR));
            pose.scale(-1.0f, -1.0f, 1.0f);
            pose.translate(0.0f, -1.501f, 0.0f);
            model.renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY, -1);
            pose.popPose();

            if (dist < ClientSimulationState.NEAR_BAND) near++;
            else if (dist < ClientSimulationState.MID_BAND) mid++;
            else far++;
            rendered++;
        }
        buffer.endBatch();

        ClientSimulationState.lastNear = near;
        ClientSimulationState.lastMid = mid;
        ClientSimulationState.lastFar = far;
        ClientSimulationState.lastCulled = culled;
    }

    private static float wrap(double phase) {
        // Modulo in double before the float cast: casting a huge gameTime phase to float first freezes the gait.
        double w = phase % (Math.PI * 2.0);
        if (w < 0) w += Math.PI * 2.0;
        return (float) w;
    }

    private static void poseAgent(HumanoidModel<LivingEntity> model, double gaitBlocks, float amount,
                                  float headYawDeg, double tSeconds, double idlePhase) {
        model.young = false;
        model.crouching = false;
        model.riding = false;
        model.attackTime = 0f;

        float p = wrap(gaitBlocks * STEP_PER_BLOCK);
        model.rightLeg.xRot = Mth.cos(p) * 0.85f * amount;
        model.leftLeg.xRot = Mth.cos(p + Mth.PI) * 0.85f * amount;
        model.rightLeg.yRot = 0f; model.rightLeg.zRot = 0f;
        model.leftLeg.yRot = 0f; model.leftLeg.zRot = 0f;

        float gesture = Mth.cos(wrap(tSeconds * 2.0 + idlePhase)) * 0.12f;
        float idleR = -0.12f + gesture, idleL = -0.12f - gesture;
        float walkR = Mth.cos(p + Mth.PI) * 0.62f, walkL = Mth.cos(p) * 0.62f;
        model.rightArm.xRot = Mth.lerp(amount, idleR, walkR);
        model.leftArm.xRot = Mth.lerp(amount, idleL, walkL);
        model.rightArm.zRot = 0.06f * (1f - amount); model.leftArm.zRot = -0.06f * (1f - amount);
        model.rightArm.yRot = 0f; model.leftArm.yRot = 0f;

        float glance = Mth.cos(wrap(tSeconds * 0.7 + idlePhase)) * 0.30f * (1f - amount);
        model.head.xRot = 0f;
        model.head.yRot = (float) Math.toRadians(headYawDeg) + glance;
        model.head.zRot = 0f;
        model.hat.copyFrom(model.head);
        model.body.xRot = 0f; model.body.yRot = 0f; model.body.zRot = 0f;
    }
}
