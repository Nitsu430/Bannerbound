package com.bannerbound.core.client.ui;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders text with a 1-pixel outline (4 cardinal offsets in the outline colour, then the fill pass
 * on top). Cheaper-to-read alternative to vanilla drop shadow for icons inline with text -- the
 * outline reads cleanly against the moss-flecked stone panel where a single bottom-right shadow gets
 * lost in the texture noise. Five draw calls per line: fine for a handful of stat lines, but use
 * vanilla shadow for long-form text or large lists.
 *
 * The outline pass recolours the whole component tree (recolor/overrideRecursive) to the outline
 * colour but keeps non-colour style (font, bold, italic) so glyphs from the bannerbound:icons font
 * still render through the right font, just in the outline colour.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class OutlinedText {
    private OutlinedText() {
    }

    public static final int DEFAULT_OUTLINE = 0xFF000000;

    public static void draw(GuiGraphics g, Font font, Component text, int x, int y, int fillColor) {
        draw(g, font, text, x, y, fillColor, DEFAULT_OUTLINE);
    }

    public static void draw(GuiGraphics g, Font font, Component text, int x, int y,
                            int fillColor, int outlineColor) {
        // drawString colour is only a fallback when the Component has no Style colour; our lines DO have styles, so recolour the whole tree first or the outline inherits the fill and vanishes.
        Component outlinePass = recolor(text, outlineColor);
        g.drawString(font, outlinePass, x - 1, y, outlineColor, false);
        g.drawString(font, outlinePass, x + 1, y, outlineColor, false);
        g.drawString(font, outlinePass, x, y - 1, outlineColor, false);
        g.drawString(font, outlinePass, x, y + 1, outlineColor, false);
        g.drawString(font, text, x, y, fillColor, false);
    }

    private static Component recolor(Component src, int color) {
        MutableComponent copy = src.copy();
        overrideRecursive(copy, color);
        return copy;
    }

    private static void overrideRecursive(MutableComponent c, int color) {
        c.setStyle(c.getStyle().withColor(TextColor.fromRgb(color)));
        List<Component> siblings = c.getSiblings();
        for (int i = 0; i < siblings.size(); i++) {
            MutableComponent reSibling = siblings.get(i).copy();
            overrideRecursive(reSibling, color);
            siblings.set(i, reSibling);
        }
    }
}
