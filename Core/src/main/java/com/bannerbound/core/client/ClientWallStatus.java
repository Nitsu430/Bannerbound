package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The wall screens' transient status banner: server feedback (gate errors, save confirmations,
 * construct results) rendered INSIDE the open screen instead of spamming chat (playtest 2026-06-12).
 * One global slot -- a new message replaces the old -- except that an error holds the slot for 2.5s
 * so a success arriving right after cannot bury the failure (save-all sends three payloads and one
 * kind may fail). When no wall screen (WallPreview/Refine/Designer or TownHall) is open to render
 * the banner, set() falls back to the vanilla action bar so post-close errors still surface. render()
 * eases the banner in, holds, then fades out over a fixed LIFE_MS lifetime.
 */
@ApiStatus.Internal
public final class ClientWallStatus {

    private static final long LIFE_MS = 3500;
    private static final long FADE_IN_MS = 120;
    private static final long FADE_OUT_MS = 600;

    private static String message = "";
    private static boolean error;
    private static long shownAtMs;

    private ClientWallStatus() {
    }

    public static void set(String text, boolean isError) {
        if (!isError && error && shownAtMs != 0 && Util.getMillis() - shownAtMs < 2500) {
            return;
        }
        message = text;
        error = isError;
        shownAtMs = Util.getMillis();
        Minecraft mc = Minecraft.getInstance();
        boolean wallScreenOpen = mc.screen instanceof WallPreviewScreen
            || mc.screen instanceof WallRefineScreen
            || mc.screen instanceof WallDesignerScreen
            || mc.screen instanceof TownHallScreen;
        if (!wallScreenOpen && mc.gui != null) {
            mc.gui.setOverlayMessage(Component.literal(text)
                .withStyle(isError ? ChatFormatting.RED : ChatFormatting.GOLD), false);
        }
    }

    public static void clear() {
        shownAtMs = 0;
    }

    public static void render(GuiGraphics g, Font font, int cx, int y) {
        if (shownAtMs == 0 || message.isEmpty()) return;
        long age = Util.getMillis() - shownAtMs;
        if (age >= LIFE_MS) {
            shownAtMs = 0;
            return;
        }
        float alpha = 1f;
        float rise = 0f;
        if (age < FADE_IN_MS) {
            float t = age / (float) FADE_IN_MS;
            alpha = t;
            rise = (1f - t * (2f - t)) * -5f;
        } else if (age > LIFE_MS - FADE_OUT_MS) {
            alpha = (LIFE_MS - age) / (float) FADE_OUT_MS;
        }
        int a = Math.max(8, (int) (alpha * 255f));
        int textColor = (a << 24) | (error ? 0xFF6868 : 0xFFD978);
        int w = font.width(message);
        int x0 = cx - w / 2 - 6;
        int x1 = cx + w / 2 + 6;
        int yy = y + (int) rise;
        g.fill(x0, yy - 4, x1, yy + 12, ((int) (alpha * 0xC8) << 24));
        g.fill(x0, yy - 4, x1, yy - 3, ((int) (alpha * 0x60) << 24) | 0xFFFFFF);
        g.drawCenteredString(font, message, cx, yy, textColor);
    }
}
