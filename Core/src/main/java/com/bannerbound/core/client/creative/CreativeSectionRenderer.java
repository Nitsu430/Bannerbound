package com.bannerbound.core.client.creative;

import com.bannerbound.core.creative.CreativeSection;
import com.bannerbound.core.creative.CreativeSections;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Client-only (referenced solely from {@code CreativeModeInventoryScreenMixin}). Draws the labelled
 * section banners over the blank rows that {@link CreativeSections#layout} leaves in the creative
 * item grid. Called at the tail of the screen's render so banners sit on top of the (empty) grid
 * cells of their row.
 *
 * <p>Constant derivation: the slot grid starts at panel (+9, +18); GRID_X/GRID_Y (8, 17) put the
 * banner one pixel up-and-left of that so it frames the row cleanly (matches Create's section
 * placement). A grid row is 18px tall, so a full 9-slot row spans 9 * 18 = 162px. Each section is
 * skipped when it had no items this rebuild (bannerRow < 0) or scrolled out of view; otherwise a
 * single greyscale strip sprite is recoloured per section via the shader colour, then a label chip
 * and text are drawn.
 */
public final class CreativeSectionRenderer {

    private CreativeSectionRenderer() {}

    private static final int GRID_X = 8;
    private static final int GRID_Y = 17;
    private static final int ROW_HEIGHT = 18;
    private static final int BANNER_WIDTH = CreativeSections.COLUMNS * 18;

    public static void render(CreativeModeTab tab, GuiGraphics g, int leftPos, int topPos, int mouseX, int mouseY) {
        CreativeSections.TabSections ts = CreativeSections.forResolvedTab(tab);
        if (ts == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int originX = leftPos + GRID_X;
        int originY = topPos + GRID_Y;

        RenderSystem.enableDepthTest();
        for (CreativeSection s : ts.order()) {
            int bannerRow = ts.bannerRow(s);
            if (bannerRow < 0) {
                continue;
            }
            int visibleRow = bannerRow - CreativeSections.currentRow;
            if (visibleRow < 0 || visibleRow >= CreativeSections.VISIBLE_ROWS) {
                continue;
            }
            int x = originX;
            int y = originY + visibleRow * ROW_HEIGHT;

            int tint = s.bannerTint();
            RenderSystem.setShaderColor(
                    ((tint >> 16) & 0xFF) / 255f,
                    ((tint >> 8) & 0xFF) / 255f,
                    (tint & 0xFF) / 255f,
                    ((tint >>> 24) & 0xFF) / 255f);
            g.blitSprite(s.sprite(), x, y, BANNER_WIDTH, ROW_HEIGHT);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f); // reset so later draws this frame aren't tinted

            Component text = s.title();
            int textWidth = font.width(text);
            g.fill(x + 2, y + 2, x + textWidth + 8, y + ROW_HEIGHT - 2, s.labelBackground());
            g.drawString(font, text, x + 5, y + 5, s.textColor(), true);
        }
        RenderSystem.disableDepthTest();
    }
}
