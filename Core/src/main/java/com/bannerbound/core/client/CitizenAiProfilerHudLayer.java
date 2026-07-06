package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.sim.CitizenAiProfiler;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Debug overlay (toggle with {@code /bannerbound ai_profiler}) showing the server-side citizen tick
 * cost: total ms/tick across all loaded citizens, the count, and the average us per citizen -- so a
 * Tribe (work-scanning on) can be compared directly against a Village (cheap brain). Reads the
 * {@link CitizenAiProfiler} snapshot directly (same JVM in single-player). Projects the measured
 * per-citizen cost onto PROJECT_CITIZENS (400, a busy ~8-settlement server) and onto slower CPUs via
 * the MID/LOW factors -- crude heuristics, NOT benchmarks -- against the 50 ms / 20 TPS tick budget,
 * colouring each line green (< 50% of budget), yellow (< 100%), or red (over).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CitizenAiProfilerHudLayer implements LayeredDraw.Layer {
    public static final CitizenAiProfilerHudLayer INSTANCE = new CitizenAiProfilerHudLayer();

    private CitizenAiProfilerHudLayer() {
    }

    private static final int PROJECT_CITIZENS = 400;
    private static final double TICK_BUDGET_MS = 50.0;
    private static final double MID_FACTOR = 1.8;
    private static final double LOW_FACTOR = 3.5;

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (!CitizenAiProfiler.enabled()) return;

        Font font = mc.font;
        double usEach = CitizenAiProfiler.lastMicrosPerCitizen();
        double projThis = usEach * PROJECT_CITIZENS / 1000.0;
        double projMid = projThis * MID_FACTOR;
        double projLow = projThis * LOW_FACTOR;

        String[] lines = {
            String.format("§eCitizen AI (server)§r  %.2f ms/tick", CitizenAiProfiler.lastMsPerTick()),
            String.format("%d loaded  ·  %.1f µs / citizen", CitizenAiProfiler.lastCount(), usEach),
            String.format("§7proj. @%d citizens (of 50ms budget):§r", PROJECT_CITIZENS),
            String.format("  this CPU %s%.1f ms§r (%d%%)", col(projThis), projThis, pct(projThis)),
            String.format("  mid ×%.1f %s%.1f ms§r (%d%%)", MID_FACTOR, col(projMid), projMid, pct(projMid)),
            String.format("  low ×%.1f %s%.1f ms§r (%d%%)", LOW_FACTOR, col(projLow), projLow, pct(projLow)),
        };

        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 8;
        int lineH = font.lineHeight + 1;
        int x = 4, y = graphics.guiHeight() - 8 - lineH * lines.length;
        graphics.fill(x, y, x + w, y + 3 + lineH * lines.length, 0xC0000000);
        int yy = y + 3;
        for (String l : lines) {
            graphics.drawString(font, l, x + 4, yy, 0xFFFFFFFF, true);
            yy += lineH;
        }
    }

    private static int pct(double ms) {
        return (int) Math.round(ms / TICK_BUDGET_MS * 100.0);
    }

    private static String col(double ms) {
        double frac = ms / TICK_BUDGET_MS;
        return frac < 0.5 ? "§a" : frac < 1.0 ? "§e" : "§c";
    }
}
