package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client-side handlers for the birdseye chunk-claim view (merged subscriber class). Two subscribers:
 *
 * onClientTickPost is a belt-and-suspenders camera guard. While {@link ClientBirdseyeState} is active
 * it slams the camera entity back onto our ghost if anything steals it; once the state goes inactive it
 * restores the camera to the local player if it is still bound to the stale ghost -- this is what saves
 * "ESC closed the GUI but I'm still in birdseye" when the screen's lifecycle hooks get bypassed. The
 * last ghost is tracked in this class, NOT in ClientBirdseyeState, because state.exit() wipes its
 * reference before we get the chance to detect the stale binding.
 *
 * onRenderLevelStage draws the in-world overlay (AFTER_TRANSLUCENT_BLOCKS): one translucent slab plus a
 * top-face outline per visible chunk so chunks are pickable straight off the terrain. Own chunks fill
 * with the faction PRIMARY and outline with the SECONDARY accent (two-tone identity, driven by the live
 * banner dye via ClientIdentityState, not the founding color slot); foreign chunks are grey; purchasable
 * chunks are white-blue, brightening on hover and fading when unaffordable. The slab Y comes from
 * ClientBirdseyeState (player-relative); tall mountains can occlude it, which is acceptable.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class BirdseyeClientEvents {

    private BirdseyeClientEvents() {}

    // Tracked here, not in ClientBirdseyeState, so it survives state.exit() (which wipes the ref).
    private static Entity lastKnownGhost;

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (ClientBirdseyeState.isActive()) {
            Entity ghost = ClientBirdseyeState.ghostCamera();
            if (ghost == null) return;
            lastKnownGhost = ghost;
            if (mc.getCameraEntity() != ghost) {
                mc.setCameraEntity(ghost);
            }
        } else if (lastKnownGhost != null) {
            if (mc.getCameraEntity() == lastKnownGhost) {
                mc.setCameraEntity(mc.player);
            }
            lastKnownGhost = null;
        }
    }

    private static final float SLAB_HEIGHT = 0.5f;
    private static final float FILL_ALPHA_OWN = 0.28f;
    private static final float FILL_ALPHA_FOREIGN = 0.22f;
    private static final float FILL_ALPHA_PURCH = 0.32f;
    private static final float FILL_ALPHA_PURCH_HOVER = 0.55f;
    private static final float FILL_ALPHA_PURCH_DISABLED = 0.18f;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ClientBirdseyeState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        int ord = ClientBirdseyeState.colorOrdinal();
        int rgb = ClientIdentityState.primaryRgb(ord);
        float ownR = ((rgb >> 16) & 0xFF) / 255f;
        float ownG = ((rgb >> 8) & 0xFF) / 255f;
        float ownB = (rgb & 0xFF) / 255f;
        int accent = ClientIdentityState.secondaryRgb(ord);
        float accR = ((accent >> 16) & 0xFF) / 255f;
        float accG = ((accent >> 8) & 0xFF) / 255f;
        float accB = (accent & 0xFF) / 255f;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        Long hovered = ClientBirdseyeState.hoveredChunk();
        boolean affordable = ClientBirdseyeState.canAfford();

        for (long packed : ClientBirdseyeState.own()) {
            drawChunk(pose, buffer, packed, ownR, ownG, ownB, FILL_ALPHA_OWN,
                accR, accG, accB);
        }
        for (long packed : ClientBirdseyeState.foreign()) {
            drawChunk(pose, buffer, packed, 0.35f, 0.35f, 0.4f, FILL_ALPHA_FOREIGN);
        }
        for (long packed : ClientBirdseyeState.purchasable()) {
            float r, g, b, a;
            if (!affordable) {
                r = 0.35f; g = 0.4f; b = 0.5f;  a = FILL_ALPHA_PURCH_DISABLED;
            } else if (hovered != null && hovered == packed) {
                r = 0.85f; g = 0.95f; b = 1.0f; a = FILL_ALPHA_PURCH_HOVER;
            } else {
                r = 0.45f; g = 0.72f; b = 1.0f; a = FILL_ALPHA_PURCH;
            }
            drawChunk(pose, buffer, packed, r, g, b, a);
        }

        buffer.endBatch(RenderType.debugFilledBox());
        buffer.endBatch(RenderType.lines());
        pose.popPose();
    }

    private static void drawChunk(PoseStack pose, MultiBufferSource buffer, long packed,
                                   float r, float g, float b, float a) {
        drawChunk(pose, buffer, packed, r, g, b, a,
            Math.min(1.0f, r * 1.4f), Math.min(1.0f, g * 1.4f), Math.min(1.0f, b * 1.4f));
    }

    private static void drawChunk(PoseStack pose, MultiBufferSource buffer, long packed,
                                   float r, float g, float b, float a,
                                   float lineR, float lineG, float lineB) {
        ChunkPos cp = new ChunkPos(packed);
        double x0 = cp.getMinBlockX();
        double z0 = cp.getMinBlockZ();
        double x1 = x0 + 16;
        double z1 = z0 + 16;
        double slabY = ClientBirdseyeState.slabY();
        DebugRenderer.renderFilledBox(pose, buffer,
            x0, slabY, z0,
            x1, slabY + SLAB_HEIGHT, z1,
            r, g, b, a);
        LevelRenderer.renderLineBox(pose, buffer.getBuffer(RenderType.lines()),
            x0, slabY + SLAB_HEIGHT, z0,
            x1, slabY + SLAB_HEIGHT + 0.05, z1,
            lineR, lineG, lineB, 0.95f);
    }
}
