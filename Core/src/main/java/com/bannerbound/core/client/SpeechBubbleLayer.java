package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.social.ConversationTopic;
import com.bannerbound.core.social.WorkstationIcons;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the conversation speech bubble (and, via drawBlocked, a red "!") above a citizen's head.
 * Both are called from CitizenRenderer.render AFTER super.render returns, in the clean world-space
 * pose relative to the entity's render origin -- the same context vanilla name tags render in. This
 * is deliberately NOT a RenderLayer: layers run inside LivingEntityRenderer.render's push/pop block,
 * where the pose already has scale(-1,-1,1) + translate(0,-1.501,0) applied (upside-down, X-mirrored),
 * so drawing post-super sidesteps that.
 *
 * The bubble shows while DATA_BUBBLE (getBubbleTopic()) is non-zero. Wall-clock animation, one cycle
 * per BUBBLE phase: 0-300ms scale 0->1 ease-out (1-(1-t)^2); 300-3500ms full size/alpha; 3500-4000ms
 * alpha fade 1->0; >4000ms hidden (the server flips DATA_BUBBLE back to 0 at 4000ms). drawBlocked's
 * "!" pulses and sits higher so it clears the bubble when both show at once.
 *
 * JOB topics render the real workstation/tool ItemStack via ItemDisplayContext.GUI (the actual
 * hotbar icon; a barrier when unemployed or the tool age isn't researched yet); all other topics
 * render a font glyph. Every icon is billboard-locked via cameraOrientation, then nudged toward the
 * camera along billboard-local +Z (ICON_Z_OFFSET) so it depth-tests in front of the bubble glyph at
 * any view angle. SCALE is 1.5x the vanilla nametag's 0.025 so the bubble reads at a glance.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SpeechBubbleLayer {
    private static final float SCALE = 0.0375f;
    private static final float ICON_DIVISOR = 1.5f;
    private static final float ITEM_ICON_SCALE = 0.4f;
    // Depth nudge applied AFTER cameraOrientation (billboard-local, +Z = toward camera); a world-space or wrong-sign offset makes the icon depth-fail behind the bubble.
    private static final float ICON_Z_OFFSET = 0.005f;
    private static final long SCALE_IN_MS = 300L;
    private static final long FADE_OUT_MS = 500L;
    private static final long TOTAL_MS = 4_000L;

    private static final Map<UUID, AnimState> ANIM_STATES = new HashMap<>();

    private static final class AnimState {
        int lastBubble = 0;
        long startMs = 0L;
    }

    private SpeechBubbleLayer() {}

    public static void draw(CitizenEntity entity, PoseStack pose, MultiBufferSource buffers,
                            int packedLight) {
        int bubbleId = entity.getBubbleTopic();
        AnimState state = ANIM_STATES.computeIfAbsent(entity.getUUID(), id -> new AnimState());
        long now = System.currentTimeMillis();
        if (bubbleId != state.lastBubble) {
            state.lastBubble = bubbleId;
            state.startMs = now;
        }
        if (bubbleId == 0) return;

        long elapsed = now - state.startMs;
        if (elapsed >= TOTAL_MS) return;

        float scale = 1.0f;
        float alpha = 1.0f;
        if (elapsed < SCALE_IN_MS) {
            float t = elapsed / (float) SCALE_IN_MS;
            float oneMinus = 1.0f - t;
            scale = 1.0f - oneMinus * oneMinus;
        } else if (elapsed > TOTAL_MS - FADE_OUT_MS) {
            alpha = Math.max(0.0f, (TOTAL_MS - elapsed) / (float) FADE_OUT_MS);
        }
        if (scale <= 0.0f || alpha <= 0.0f) return;

        ConversationTopic topic = ConversationTopic.fromBubbleId(bubbleId);
        if (topic == null) return;
        int subType = ConversationTopic.subTypeFromPackedId(bubbleId);
        Component bubbleGlyph = Icons.bubble();
        Component topicGlyph = topicComponentFor(topic, subType, entity.getEra());

        int aByte = Math.min(255, Math.max(0, (int) (alpha * 255.0f)));
        int color = (aByte << 24) | 0x00FFFFFF;

        Font font = Minecraft.getInstance().font;

        pose.pushPose();
        pose.translate(0.0f, entity.getBbHeight() + 0.85f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.scale(SCALE * scale, -SCALE * scale, SCALE * scale);

        Matrix4f bubbleMatrix = pose.last().pose();
        float bubbleW = font.width(bubbleGlyph);
        float bubbleH = font.lineHeight;
        font.drawInBatch(bubbleGlyph, -bubbleW / 2f, -bubbleH / 2f, color, false,
            bubbleMatrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();

        if (topic == ConversationTopic.JOB) {
            ItemStack workstationStack = jobIconFor(subType, entity);
            if (!workstationStack.isEmpty()) {
                drawItemIcon(workstationStack, entity, pose, buffers, packedLight, scale);
            }
        } else if (topicGlyph != null) {
            drawGlyphIcon(topicGlyph, entity, pose, buffers, packedLight, color, scale, font);
        }
    }

    public static void drawBlocked(CitizenEntity entity, PoseStack pose, MultiBufferSource buffers,
                                   int packedLight) {
        if (!entity.isWorkBlocked()) return;
        Font font = Minecraft.getInstance().font;
        long now = System.currentTimeMillis();
        float pulse = 0.85f + 0.15f * (float) Math.sin(now / 250.0);
        int aByte = Math.min(255, Math.max(0, (int) (pulse * 255.0f)));
        int color = (aByte << 24) | 0x00FF3030;

        pose.pushPose();
        pose.translate(0.0f, entity.getBbHeight() + 1.55f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.translate(0.0f, 0.0f, ICON_Z_OFFSET);
        float s = SCALE * 1.2f;
        pose.scale(s, -s, s);
        Matrix4f matrix = pose.last().pose();
        Component glyph = Component.literal("!");
        float w = font.width(glyph);
        font.drawInBatch(glyph, -w / 2f, -font.lineHeight / 2f, color, false,
            matrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();
    }

    private static void drawGlyphIcon(Component glyph, CitizenEntity entity, PoseStack pose,
                                       MultiBufferSource buffers, int packedLight,
                                       int color, float scale, Font font) {
        pose.pushPose();
        pose.translate(0.0f, entity.getBbHeight() + 0.85f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.translate(0.0f, 0.0f, ICON_Z_OFFSET);
        float iconScale = (SCALE / ICON_DIVISOR) * scale;
        pose.scale(iconScale, -iconScale, iconScale);
        Matrix4f iconMatrix = pose.last().pose();
        float iconW = font.width(glyph);
        float iconH = font.lineHeight;
        font.drawInBatch(glyph, -iconW / 2f, -iconH / 2f, color, false,
            iconMatrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        pose.popPose();
    }

    private static void drawItemIcon(ItemStack stack, CitizenEntity entity, PoseStack pose,
                                      MultiBufferSource buffers, int packedLight, float scale) {
        pose.pushPose();
        pose.translate(0.0f, entity.getBbHeight() + 0.85f, 0.0f);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        pose.translate(0.0f, 0.0f, ICON_Z_OFFSET);
        // GUI items render in y-UP model space; do NOT flip Y here or the icon draws upside down.
        float iconScale = ITEM_ICON_SCALE * scale;
        pose.scale(iconScale, iconScale, iconScale);
        // Render thread is single-threaded, so this plain bypass flag is safe; reset in finally so nothing else inherits it.
        UnknownItemHelper.setBypassUnknownSwap(true);
        try {
            Minecraft.getInstance().getItemRenderer().renderStatic(
                stack, ItemDisplayContext.GUI,
                packedLight, OverlayTexture.NO_OVERLAY,
                pose, buffers, Minecraft.getInstance().level, 0);
        } finally {
            UnknownItemHelper.setBypassUnknownSwap(false);
        }
        pose.popPose();
    }

    private static ItemStack jobIconFor(int subType, CitizenEntity entity) {
        String typeId = WorkstationIcons.typeIdOf(subType);
        if (typeId == null) {
            return new ItemStack(net.minecraft.world.item.Items.BARRIER);
        }
        return switch (typeId) {
            case "diggers_slab" -> nonEmptyOrBarrier(entity.getToolShovelItem());
            case "farmers_granary" -> nonEmptyOrBarrier(entity.getToolHoeItem());
            case "foragers_basket" -> new ItemStack(net.minecraft.world.item.Items.POPPY);
            case "fishers_creel" -> new ItemStack(net.minecraft.world.item.Items.FISHING_ROD);
            case "stockpile_rack" -> new ItemStack(net.minecraft.world.item.Items.BUNDLE);
            default -> {
                ItemStack ws = WorkstationIcons.itemOrdinal(subType);
                yield ws.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.BARRIER) : ws;
            }
        };
    }

    private static ItemStack nonEmptyOrBarrier(net.minecraft.world.item.Item item) {
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return new ItemStack(net.minecraft.world.item.Items.BARRIER);
        }
        return new ItemStack(item);
    }

    private static Component topicComponentFor(ConversationTopic topic, int subType, Era era) {
        return switch (topic) {
            case CULTURE   -> Icons.culture(era);
            case FOOD      -> Icons.food(era);
            case SCIENCE   -> Icons.science(era);
            case HAPPINESS -> Icons.happinessForBucket(subType);
            case JOB       -> null;
        };
    }
}
