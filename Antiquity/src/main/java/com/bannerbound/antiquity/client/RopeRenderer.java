package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.rope.RopeAnchor;
import com.bannerbound.antiquity.rope.RopeTieHost;
import com.bannerbound.antiquity.rope.RopeTieState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * Shared drawing code for every plant-fibre green rope ribbon (spear tethers, speared-fish lines,
 * rope fences, leashes, herding, tie previews). Geometry mirrors vanilla's braided leash ribbon
 * ({@code MobRenderer.renderLeash}/{@code addVertexPair}: two offset triangle strips with alternating
 * 0.7/1.0 shading for the twist, straight-line interpolation pulled down by a parabolic mid-span sag)
 * on {@link RenderType#leash()} (POSITION_COLOR_LIGHTMAP) via the current addVertex/setColor/setLight
 * API - but plant-green and THICKNESS 0.05, chunkier than vanilla's 0.025 lead, for a "ropier" read.
 *
 * <p>Sag models a REAL fixed-length rope rather than a line that just scales: while LOOSE, ROPE_LENGTH
 * (4.6, a bit past the max horizontal tie so a long loose rope still has slack) is paid out, so
 * {@link #slackSag} droops in a deep U when the ends are close and pulls near-taut at full span.
 * SAG_FACTOR is 0.5 because a rope folded in half hangs ~half its slack deep; MAX_SLACK_SAG caps the
 * droop so the preview never dips through the ground (the tie sits ~0.8 up). A committed/TIED rope
 * holds taut with only a faint span-scaled droop ({@link #tiedSag}: TIED_SAG_PER_BLOCK x span, capped),
 * and for ZIP_TICKS after tying it "zips" from the loose droop up to taut with an ease-in driven by
 * {@link RopeTieState#zipProgress}. {@code ropeHoldPosition} uses the exact vanilla hand point for
 * players and an estimated main-arm hand (slightly below the eyes, forward along the look, offset to
 * the main-arm side) for citizens or any other humanoid. The spear-tether {@link #render} uses a mild
 * distance-scaled sag instead - a held line, not a slack fence rope. {@link #renderHostRopes} draws
 * every rope a tie host (post or gate) owns from its slots to each connected anchor.
 * {@link #drawRibbon} draws from the current pose origin out to offset (dx,dy,dz) in pose-space
 * blocks; the caller owns the pose push/translate; the long overload takes an explicit 0-255 base
 * colour (plant-green fiber rope vs leather-brown lead).</p>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class RopeRenderer {
    private static final int R = 96, G = 150, B = 58;
    private static final int SEGMENTS = 24;
    private static final float THICKNESS = 0.05F;

    public static final float ROPE_LENGTH = 4.6F;
    private static final float SAG_FACTOR = 0.5F;
    private static final float MAX_SLACK_SAG = 0.65F;
    private static final float TIED_SAG_PER_BLOCK = 0.05F;
    private static final float MAX_TIED_SAG = 0.25F;
    private static final float ZIP_TICKS = 6.0F;

    private RopeRenderer() {}

    private static Vec3 ropeHoldPosition(LivingEntity owner, float partialTick) {
        if (owner instanceof Player player) {
            return player.getRopeHoldPosition(partialTick);
        }
        Vec3 eye = owner.getEyePosition(partialTick);
        Vec3 look = owner.getViewVector(partialTick);
        Vec3 side = new Vec3(-look.z, 0.0, look.x).normalize()
            .scale(owner.getMainArm() == HumanoidArm.RIGHT ? 0.35 : -0.35);
        return eye.add(look.scale(0.4)).add(side).add(0.0, -0.4, 0.0);
    }

    public static float slackSag(double dx, double dy, double dz) {
        double span = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) Math.min(MAX_SLACK_SAG, Math.max(0.0, SAG_FACTOR * (ROPE_LENGTH - span)));
    }

    public static float tiedSag(double dx, double dy, double dz) {
        double span = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) Math.min(MAX_TIED_SAG, TIED_SAG_PER_BLOCK * span);
    }

    public static void render(PoseStack pose, MultiBufferSource buffer, int packedLight,
                              float partialTick, Entity tethered, LivingEntity owner, float attachY) {
        pose.pushPose();
        Vec3 hand = ropeHoldPosition(owner, partialTick);
        double ex = Mth.lerp(partialTick, tethered.xo, tethered.getX());
        double ey = Mth.lerp(partialTick, tethered.yo, tethered.getY());
        double ez = Mth.lerp(partialTick, tethered.zo, tethered.getZ());
        pose.translate(0.0, attachY, 0.0);
        float dx = (float) (hand.x - ex);
        float dy = (float) (hand.y - (ey + attachY));
        float dz = (float) (hand.z - ez);
        float sag = (float) Math.min(0.4, Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.1);
        drawRibbon(pose, buffer, packedLight, dx, dy, dz, sag);
        pose.popPose();
    }

    public static void drawRibbon(PoseStack pose, MultiBufferSource buffer, int packedLight,
                                  float dx, float dy, float dz, float sag) {
        drawRibbon(pose, buffer, packedLight, dx, dy, dz, sag, R, G, B);
    }

    public static void drawRibbon(PoseStack pose, MultiBufferSource buffer, int packedLight,
                                  float dx, float dy, float dz, float sag, int cr, int cg, int cb) {
        VertexConsumer line = buffer.getBuffer(RenderType.leash());
        Matrix4f matrix = pose.last().pose();
        float perp = Mth.invSqrt(dx * dx + dz * dz) * THICKNESS / 2.0F;
        float offZ = dz * perp;
        float offX = dx * perp;
        for (int i = 0; i <= SEGMENTS; i++) {
            addPair(line, matrix, dx, dy, dz, sag, packedLight, THICKNESS, offZ, offX, i, false, cr, cg, cb);
        }
        for (int i = SEGMENTS; i >= 0; i--) {
            addPair(line, matrix, dx, dy, dz, sag, packedLight, 0.0F, offZ, offX, i, true, cr, cg, cb);
        }
    }

    public static void renderHostRopes(BlockEntity be, RopeTieHost host, PoseStack pose,
                                       MultiBufferSource buffer, int packedLight, float partialTick) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        for (int slot = 0; slot < host.slotCount(); slot++) {
            RopeAnchor local = new RopeAnchor(pos, slot);
            Vec3 lt = RopeAnchor.worldTie(level, local);
            if (lt == null) {
                continue;
            }
            for (RopeAnchor other : host.connections(slot)) {
                if (local.compareTo(other) >= 0) {
                    continue; // only the lower-ordered end draws each rope, or it renders twice
                }
                Vec3 ot = RopeAnchor.worldTie(level, other);
                if (ot == null) {
                    continue;
                }
                float dx = (float) (ot.x - lt.x), dy = (float) (ot.y - lt.y), dz = (float) (ot.z - lt.z);
                float sag = zipSag(dx, dy, dz, local, other, level.getGameTime(), partialTick);
                pose.pushPose();
                pose.translate(lt.x - pos.getX(), lt.y - pos.getY(), lt.z - pos.getZ());
                drawRibbon(pose, buffer, packedLight, dx, dy, dz, sag);
                pose.popPose();
            }
        }
    }

    private static float zipSag(float dx, float dy, float dz, RopeAnchor a, RopeAnchor b,
                               long gameTime, float partialTick) {
        float tight = tiedSag(dx, dy, dz);
        float p = RopeTieState.zipProgress(a, b, gameTime, partialTick, ZIP_TICKS);
        if (p < 0.0F) {
            return tight;
        }
        float loose = slackSag(dx, dy, dz);
        float ease = p * p;
        return Mth.lerp(ease, loose, tight);
    }

    private static void addPair(VertexConsumer line, Matrix4f matrix, float dx, float dy, float dz,
                                float sag, int light, float w2, float offZ, float offX, int i, boolean flip,
                                int cr, int cg, int cb) {
        float t = (float) i / SEGMENTS;
        float shade = (i % 2 == (flip ? 1 : 0)) ? 0.7F : 1.0F;
        int r = (int) (cr * shade);
        int g = (int) (cg * shade);
        int b = (int) (cb * shade);
        float x = dx * t;
        float y = dy * t - sag * 4.0F * t * (1.0F - t);
        float z = dz * t;
        line.addVertex(matrix, x - offZ, y + w2, z + offX).setColor(r, g, b, 255).setLight(light);
        line.addVertex(matrix, x + offZ, y + THICKNESS - w2, z - offX).setColor(r, g, b, 255).setLight(light);
    }
}
