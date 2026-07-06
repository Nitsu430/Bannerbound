package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Era;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Top-left HUD: a large era-name banner with a smaller world-calendar line ("Year N / Month M /
 * Day D") underneath. Era comes from {@link ClientEraState} (server pushes new values via
 * {@code EraStatePayload} when a research completes or a settlement era-advances, keeping the line
 * in sync with the leading civ); the date comes from the client sky calendar (config-driven month
 * lengths; a year is one observer orbit). The world tells its OWN history - "Year 3" can be
 * medieval, there is no BC/AD anchoring. Months stay "Month N" until month-naming ships (faiths
 * and cultures will name them, see FAITH_PLAN).
 * <p>
 * Era name is drawn at 1.4x font scale to read as a banner; the year line is regular size to keep
 * the cluster compact. Both sit a few pixels in from the screen edge to clear common resource-pack
 * overlays. Hidden when F1 (hideGui) is active.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class EraYearHudLayer implements LayeredDraw.Layer {
    public static final EraYearHudLayer INSTANCE = new EraYearHudLayer();

    private static final float ERA_SCALE = 1.4f;
    private static final int MARGIN_X = 8;
    private static final int MARGIN_Y = 6;
    private static final int LINE_GAP = 3;

    private EraYearHudLayer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) {
            return;
        }
        Font font = mc.font;
        Era era = ClientEraState.getWorldEra();
        Component eraName = era.displayName();
        com.bannerbound.core.celestial.WorldCalendar.CalendarDate date =
            com.bannerbound.core.client.sky.ClientSkyState.calendar().fromDayTime(
                mc.level != null ? mc.level.getDayTime() : 0L);
        Component yearLine = Component.literal(
                "Year " + (date.year() + 1) + " · Month " + date.month() + " · Day " + date.day())
            .withStyle(net.minecraft.ChatFormatting.GRAY);

        int eraWidth = (int) Math.ceil(font.width(eraName) * ERA_SCALE);
        int eraHeight = (int) Math.ceil(font.lineHeight * ERA_SCALE);
        int yearWidth = font.width(yearLine);

        // Keep the top-left cluster the same fraction of the screen at any GUI scale / resolution,
        // in lockstep with the "Currently in" line and the journal tracker below it (see HudScale).
        // Anchored near the top-left origin, so scaling about (0,0) just shrinks it in place.
        float uiScale = HudScale.factor(mc);
        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1.0f);

        int boxLeft = MARGIN_X - 2;
        int boxTop = MARGIN_Y - 2;
        int boxRight = MARGIN_X + Math.max(eraWidth, yearWidth) + 2;
        int boxBottom = MARGIN_Y + eraHeight + LINE_GAP + font.lineHeight + 1;
        graphics.fill(boxLeft, boxTop, boxRight, boxBottom, 0xA0000000);

        graphics.pose().pushPose();
        graphics.pose().scale(ERA_SCALE, ERA_SCALE, 1.0f);
        // Draw pos divided by ERA_SCALE so the scaled matrix lands it back at MARGIN_X/MARGIN_Y.
        graphics.drawString(font, eraName,
            Math.round(MARGIN_X / ERA_SCALE),
            Math.round(MARGIN_Y / ERA_SCALE),
            0xFFFFFFFF, true);
        graphics.pose().popPose();

        int yearY = MARGIN_Y + eraHeight + LINE_GAP;
        graphics.drawString(font, yearLine, MARGIN_X, yearY, 0xFFFFFFFF, false);

        graphics.pose().popPose();
    }
}
