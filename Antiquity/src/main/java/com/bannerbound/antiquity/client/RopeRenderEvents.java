package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.rope.RopeAnchor;
import com.bannerbound.antiquity.rope.RopeTies;
import com.bannerbound.antiquity.rope.RopeTieState;
import com.bannerbound.antiquity.block.RopeFenceGateBlock;
import com.bannerbound.antiquity.block.RopeFencePostBlock;
import com.bannerbound.core.BannerboundCore;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.bannerbound.antiquity.event.HerdingEvents;

/**
 * Client-side rope render event hub: every world-space plant-fibre green rope ribbon that is not a
 * block model is drawn here at AFTER_ENTITIES via {@link RopeRenderer#drawRibbon} (a merge of three
 * former event classes). Three independent handlers:
 * <ul>
 *   <li><b>Vanilla leashes</b> ({@code onRenderLevel}): fiber-rope leashing is pure vanilla leashing
 *       (Mob.isLeashed/getLeashHolder, set by HerdingEvents); vanilla's brown ribbon is cancelled by
 *       Core's MobRendererMixin and this draws the green ribbon in its place. The holder end uses
 *       {@code getRopeHoldPosition} (hand / fence knot, matching where vanilla anchors a lead); the
 *       mob end ties at TIE_FRACTION (~60% of body height, chest-ish, so it reads as a held lead).</li>
 *   <li><b>Herder ropes</b> ({@code herderOnRenderLevel}): cosmetic rope from a herding citizen to each
 *       animal it herds. The real follow is a leash-FREE server-side pull; this just reads Core's synced
 *       HERDED_BY claim (the herder's entity id) off every rendered animal each frame.</li>
 *   <li><b>Tie preview</b> ({@code tiePreviewOnRenderLevel} + {@code onClientTick}): while the local
 *       player is mid-tie ({@link RopeTieState}) a live slack rope pays out from the selected tie point
 *       (post centre or gate upright). The loose end snaps to an aimed post/gate when it is a valid,
 *       in-reach second anchor (previewing the finished rope before committing); otherwise it trails
 *       LEAD_DISTANCE blocks ahead of the aim, clamped to RopeTies.MAX_ROPE_HORIZONTAL/VERTICAL so it
 *       visibly stops paying out at the limit (an out-of-reach aimed post also falls back to the
 *       trailing end so you SEE it will not reach). The aimed post's roped-coil model is a client-only
 *       blockstate flip (tracked in previewRopedTarget, applied via RopeTies.setRopedModel, reverted to
 *       the server-authoritative state via RopeTies.refreshRoped); the committed rope's "zip taut"
 *       settle lives in {@link RopeRenderer}. The mid-tie selection is only dropped when genuinely
 *       stale (tie host gone / wrong dimension) - merely not holding the rope hides the preview.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class RopeRenderEvents {

    private static final double TIE_FRACTION = 0.6;

    private RopeRenderEvents() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean drewAny = false;

        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Mob mob) || !mob.isLeashed()) {
                continue;
            }
            Entity holder = mob.getLeashHolder();
            if (holder == null || !holder.isAlive()) {
                continue;
            }

            Vec3 a = lerpPos(mob, partial);
            double ay = a.y + mob.getBbHeight() * TIE_FRACTION;
            Vec3 h = holder.getRopeHoldPosition(partial);
            float dx = (float) (h.x - a.x);
            float dy = (float) (h.y - ay);
            float dz = (float) (h.z - a.z);
            double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
            float sag = (float) Mth.clamp(0.12 * horiz, 0.08, 0.5);

            pose.pushPose();
            pose.translate(a.x - cam.x, ay - cam.y, a.z - cam.z);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(a.x, ay, a.z));
            RopeRenderer.drawRibbon(pose, buffers, light, dx, dy, dz, sag);
            pose.popPose();
            drewAny = true;
        }
        if (drewAny) {
            buffers.endBatch(RenderType.leash());
        }
    }

    private static Vec3 lerpPos(Entity e, float partial) {
        return new Vec3(Mth.lerp(partial, e.xOld, e.getX()),
            Mth.lerp(partial, e.yOld, e.getY()),
            Mth.lerp(partial, e.zOld, e.getZ()));
    }

    @SubscribeEvent
    public static void herderOnRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean drewAny = false;

        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Animal animal)) {
                continue;
            }
            Integer id = animal.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
            if (id == null || id == 0) {
                continue;
            }
            Entity herder = level.getEntity(id);
            if (herder == null || !herder.isAlive()) {
                continue;
            }

            Vec3 a = lerpPos(animal, partial);
            Vec3 h = lerpPos(herder, partial);
            double ay = a.y + animal.getBbHeight() * TIE_FRACTION;
            double hy = h.y + herder.getBbHeight() * TIE_FRACTION;
            float dx = (float) (h.x - a.x);
            float dy = (float) (hy - ay);
            float dz = (float) (h.z - a.z);
            double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
            float sag = (float) Mth.clamp(0.12 * horiz, 0.08, 0.5);

            pose.pushPose();
            pose.translate(a.x - cam.x, ay - cam.y, a.z - cam.z);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(a.x, ay, a.z));
            RopeRenderer.drawRibbon(pose, buffers, light, dx, dy, dz, sag);
            pose.popPose();
            drewAny = true;
        }
        if (drewAny) {
            buffers.endBatch(RenderType.leash());
        }
    }

    private static final double LEAD_DISTANCE = 1.5;

    @SubscribeEvent
    public static void tiePreviewOnRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        RopeAnchor anchor = RopeTieState.get();
        if (anchor == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        Level level = player.level();
        Vec3 tie = RopeAnchor.worldTie(level, anchor);
        if (tie == null || !RopeTieState.isAt(anchor, level.dimension())) {
            RopeTieState.clear();
            return;
        }
        if (!holdingRope(player)) {
            return; // hide the preview but deliberately KEEP the selection
        }

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        RopeAnchor target = aimedTargetAnchor(mc, level, anchor);
        Vec3 snap = target != null ? RopeAnchor.worldTie(level, target) : null;
        Vec3 end = snap != null ? snap
            : clampToReach(tie, player.getEyePosition(partial).add(player.getViewVector(partial).scale(LEAD_DISTANCE)));

        float dx = (float) (end.x - tie.x), dy = (float) (end.y - tie.y), dz = (float) (end.z - tie.z);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(tie.x - cam.x, tie.y - cam.y, tie.z - cam.z);
        int packedLight = LevelRenderer.getLightColor(level, BlockPos.containing(tie.x, tie.y, tie.z));
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RopeRenderer.drawRibbon(pose, buffers, packedLight, dx, dy, dz, RopeRenderer.slackSag(dx, dy, dz));
        buffers.endBatch(RenderType.leash());
        pose.popPose();
    }

    private static RopeAnchor aimedTargetAnchor(Minecraft mc, Level level, RopeAnchor from) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (pos.equals(from.pos())) {
            return null;
        }
        if (!(level.getBlockState(pos).getBlock() instanceof RopeFencePostBlock
                || level.getBlockState(pos).getBlock() instanceof RopeFenceGateBlock)) {
            return null;
        }
        RopeAnchor target = new RopeAnchor(pos, RopeTies.slotForHit(level, pos, hit.getLocation()));
        Vec3 to = RopeAnchor.worldTie(level, target);
        Vec3 fromTie = RopeAnchor.worldTie(level, from);
        if (to == null || fromTie == null) {
            return null;
        }
        double horiz = Math.sqrt((to.x - fromTie.x) * (to.x - fromTie.x) + (to.z - fromTie.z) * (to.z - fromTie.z));
        if (horiz > RopeTies.MAX_ROPE_HORIZONTAL || Math.abs(to.y - fromTie.y) > RopeTies.MAX_ROPE_VERTICAL) {
            return null;
        }
        return target;
    }

    private static RopeAnchor previewRopedTarget;

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        RopeAnchor desired = null;
        if (player != null && level != null) {
            RopeAnchor from = RopeTieState.get();
            if (from != null && holdingRope(player) && RopeTieState.isAt(from, level.dimension())) {
                desired = aimedTargetAnchor(mc, level, from);
            }
        }
        if (java.util.Objects.equals(desired, previewRopedTarget)) {
            return;
        }
        if (previewRopedTarget != null && level != null) {
            RopeTies.refreshRoped(level, previewRopedTarget);
        }
        previewRopedTarget = desired;
        if (desired != null && level != null) {
            RopeTies.setRopedModel(level, desired, true);
        }
    }

    private static Vec3 clampToReach(Vec3 tie, Vec3 target) {
        double dx = target.x - tie.x, dy = target.y - tie.y, dz = target.z - tie.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz > RopeTies.MAX_ROPE_HORIZONTAL && horiz > 1.0e-6) {
            double s = RopeTies.MAX_ROPE_HORIZONTAL / horiz;
            dx *= s;
            dz *= s;
        }
        dy = Mth.clamp(dy, -RopeTies.MAX_ROPE_VERTICAL, RopeTies.MAX_ROPE_VERTICAL);
        return new Vec3(tie.x + dx, tie.y + dy, tie.z + dz);
    }

    private static boolean holdingRope(LocalPlayer player) {
        return player.getMainHandItem().is(BannerboundAntiquity.FIBER_ROPE.get())
            || player.getOffhandItem().is(BannerboundAntiquity.FIBER_ROPE.get());
    }
}
