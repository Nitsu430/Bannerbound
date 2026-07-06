package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Per-screen transient "+" pop near the cursor, spawned when a vote / suggestion click is sent.
 * Replaces the old vote-tally chat broadcasts: a green plus floats up over LIFETIME_MS (600ms,
 * short so multiple clicks don't pile up) then fades. One instance lives on each voting screen;
 * the screen calls spawn() (or spawnAtCursor()) on click and render() from its own render pass ->
 * call render AFTER the rest of the UI so the "+" sits on top.
 *
 * <p>Lightweight by design: no entity, no scheduler, just a list of pops aged by wall-clock delta
 * inside render(). Each pop holds full opacity for its first half (impact) then fades out; a brief
 * scale overshoot near t=0 gives the "pop". FLOAT_PX is the upward travel over one full lifetime.
 */
public final class TransientClickFeedback {
    private static final long LIFETIME_MS = 600L;
    private static final int FLOAT_PX = 18;

    private final List<Pop> pops = new ArrayList<>();
    private long lastRenderMs = -1L;

    public TransientClickFeedback() {}

    public void spawn(int x, int y) {
        pops.add(new Pop(x, y, 0L));
    }

    public void spawnAtCursor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.mouseHandler == null || mc.getWindow() == null) return;
        double mx = mc.mouseHandler.xpos()
            * (double) mc.getWindow().getGuiScaledWidth()
            / (double) Math.max(1, mc.getWindow().getScreenWidth());
        double my = mc.mouseHandler.ypos()
            * (double) mc.getWindow().getGuiScaledHeight()
            / (double) Math.max(1, mc.getWindow().getScreenHeight());
        spawn((int) mx, (int) my);
    }

    public void render(GuiGraphics graphics) {
        if (pops.isEmpty()) {
            lastRenderMs = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        long dt = lastRenderMs < 0 ? 0L : (now - lastRenderMs);
        lastRenderMs = now;
        Font font = Minecraft.getInstance().font;
        Iterator<Pop> it = pops.iterator();
        while (it.hasNext()) {
            Pop p = it.next();
            p.ageMs += dt;
            if (p.ageMs >= LIFETIME_MS) {
                it.remove();
                continue;
            }
            float t = (float) p.ageMs / (float) LIFETIME_MS;
            int alpha = t < 0.5f ? 255 : (int) (255f * (1f - (t - 0.5f) * 2f));
            alpha = Math.max(0, Math.min(255, alpha));
            int color = (alpha << 24) | 0x55E055;
            int drawY = p.y - (int) (FLOAT_PX * t);
            PoseStack pose = graphics.pose();
            pose.pushPose();
            float scale = 1.0f + 0.6f * Math.max(0f, 1f - t * 5f);
            pose.translate(p.x, drawY, 0);
            pose.scale(scale, scale, 1f);
            Component plus = Component.literal("+");
            int w = font.width(plus);
            graphics.drawString(font, plus, -w / 2, -font.lineHeight / 2, color, true);
            pose.popPose();
        }
        // Reset flat-item lighting in case a transform leaked; later UI draws depend on it.
        Lighting.setupForFlatItems();
    }

    private static final class Pop {
        final int x;
        final int y;
        long ageMs;

        Pop(int x, int y, long ageMs) {
            this.x = x;
            this.y = y;
            this.ageMs = ageMs;
        }
    }
}
