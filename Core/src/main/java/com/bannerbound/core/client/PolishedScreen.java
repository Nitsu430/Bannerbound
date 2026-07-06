package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.Config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Base class for the mod's panel screens: owns the open-settle polish animation (the panel zooms
 * 0.96->1 with a 10px upward drift over ~160ms ease-out) with the dim/blur background rendered
 * OUTSIDE the pose so it never zooms along. New menus get the polish for free by extending this
 * instead of {@link Screen}; {@link Config#UI_ANIMATIONS} off reverts every screen to instant
 * static rendering at once.
 *
 * <p>Subclasses that need custom drawing override the two hooks instead of {@link #render}:
 * {@link #renderPolishedBackdrop} draws BEFORE the widgets (panel fills, chrome) and
 * {@link #renderPolishedExtras} AFTER them (overlays, drag ghosts) -- both ride the settle pose.
 * Vanilla widget tooltips are deferred by the engine until after {@code render()}, so they never
 * scale. Screens with bespoke camera/animation needs (TownHallScreen, ResearchScreen) keep their
 * own render overrides; world-anchored screens (ExpandTerritoryScreen) must NOT extend this -- the
 * settle pose would misalign their overlay from the world behind it. Override
 * {@link #drawsDimmedBackground} to false for transparent cinematic overlays that must show the
 * live world (the settle animation still applies; only the dim/blur pass is skipped).
 *
 * <p>Shared chrome: {@link #drawIdentityPanel} is THE settlement panel treatment (near-black fill
 * plus the banner identity worn as a border, or a neutral outline on unclaimed ground), and
 * {@link #drawIdentityDivider}/{@link #drawIdentityGradient}/{@link #drawIdentityBorder} render the
 * banner colors so a red nation's panels read red everywhere from one call. Identity accents are
 * the ARGB dye colors (most-present first) of the settlement the player stands in when the screen
 * opens, empty off-claim; a subclass whose payload carries a color ordinal may reassign via
 * {@link GuiPalette#identityAccents(int)}. House rule: every free-prose line in a panel MUST go
 * through {@link #drawWrapped} (never a raw drawString) -- long translatable lines have repeatedly
 * overflowed panels because drawString does not wrap; {@link #wrappedLineCount} sizes them first.
 *
 * <p>Opt-in auto-fit: a fixed-size panel returns its dimensions from {@link #fitPanelWidth}/{@link
 * #fitPanelHeight} and the render pass centre-scales the whole panel to the window (shrinking on
 * small windows / high GUI scales, growing on large ones). Subclasses that opt in MUST remap mouse
 * coords through {@link #virtualX}/{@link #virtualY} at the top of
 * mouseClicked/mouseReleased/mouseScrolled (before super) so widget hit-tests align, and pre-map
 * any {@code enableScissor} bounds through {@link #scissorX}/{@link #scissorY} because scissor is
 * raw screen-space and ignores the fit pose.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public abstract class PolishedScreen extends Screen {
    private final long openedAtMs = net.minecraft.Util.getMillis();

    protected java.util.List<Integer> identityAccents = GuiPalette.localIdentityAccents();

    protected PolishedScreen(Component title) {
        super(title);
    }

    protected int primaryAccent() {
        return GuiPalette.primary(identityAccents);
    }

    protected int secondaryAccent() {
        return identityAccents.size() > 1 ? identityAccents.get(1) : primaryAccent();
    }

    protected boolean drawsDimmedBackground() {
        return true;
    }

    public static int drawWrapped(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                  Component text, int x, int y, int maxWidth, int color) {
        for (net.minecraft.util.FormattedCharSequence line : font.split(text, maxWidth)) {
            graphics.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    public static int wrappedLineCount(net.minecraft.client.gui.Font font, Component text, int maxWidth) {
        return Math.max(1, font.split(text, maxWidth).size());
    }

    public static int blendArgb(int base, int accent, float t) {
        int a = (int) net.minecraft.util.Mth.lerp(t, (base >>> 24) & 0xFF, (accent >>> 24) & 0xFF);
        int r = (int) net.minecraft.util.Mth.lerp(t, (base >>> 16) & 0xFF, (accent >>> 16) & 0xFF);
        int g = (int) net.minecraft.util.Mth.lerp(t, (base >>> 8) & 0xFF, (accent >>> 8) & 0xFF);
        int b = (int) net.minecraft.util.Mth.lerp(t, base & 0xFF, accent & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static void drawIdentityGradient(GuiGraphics graphics, int x, int y, int width,
                                            int height, java.util.List<Integer> argbColors) {
        if (argbColors.isEmpty()) return;
        if (argbColors.size() == 1) {
            graphics.fill(x, y, x + width, y + height, argbColors.get(0));
            return;
        }
        int spans = argbColors.size() - 1;
        int drawn = 0;
        for (int i = 0; i < spans; i++) {
            int spanWidth = (width - drawn) / (spans - i);
            drawHorizontalGradient(graphics, x + drawn, y, spanWidth, height,
                argbColors.get(i), argbColors.get(i + 1));
            drawn += spanWidth;
        }
    }

    public static void drawIdentityBorder(GuiGraphics graphics, int x, int y, int width,
                                          int height, java.util.List<Integer> argbColors) {
        if (argbColors.isEmpty()) return;
        if (argbColors.size() == 1) {
            graphics.renderOutline(x, y, width, height, argbColors.get(0));
            return;
        }
        int spans = argbColors.size() - 1;
        int drawnHeight = 0;
        for (int i = 0; i < spans; i++) {
            int spanHeight = (height - drawnHeight) / (spans - i);
            int spanTop = y + drawnHeight;
            int upper = argbColors.get(i);
            int lower = argbColors.get(i + 1);
            // fillGradient paints colorFrom at the rect TOP, so pass the upper color first.
            graphics.fillGradient(x, spanTop, x + 1, spanTop + spanHeight, upper, lower);
            graphics.fillGradient(x + width - 1, spanTop, x + width, spanTop + spanHeight,
                upper, lower);
            drawnHeight += spanHeight;
        }
        graphics.fill(x, y, x + width, y + 1, argbColors.get(0));
        graphics.fill(x, y + height - 1, x + width, y + height,
            argbColors.get(argbColors.size() - 1));
    }

    public static void drawIdentityPanel(GuiGraphics graphics, int x, int y, int width,
                                         int height, java.util.List<Integer> accents) {
        graphics.fill(x, y, x + width, y + height, GuiPalette.PANEL_BG);
        if (accents.isEmpty()) {
            graphics.renderOutline(x, y, width, height, GuiPalette.PANEL_BORDER);
        } else {
            drawIdentityBorder(graphics, x, y, width, height, accents);
        }
    }

    public static void drawIdentityDivider(GuiGraphics graphics, int x, int y, int width,
                                           java.util.List<Integer> accents) {
        if (accents.isEmpty()) {
            graphics.fill(x, y, x + width, y + 1, GuiPalette.PANEL_BORDER);
        } else {
            drawIdentityGradient(graphics, x, y, width, 1, accents);
        }
    }

    public static void drawHorizontalGradient(GuiGraphics graphics, int x, int y, int width,
                                              int height, int from, int to) {
        final int segments = Math.min(16, Math.max(1, width));
        int drawn = 0;
        for (int i = 0; i < segments; i++) {
            int segWidth = (width - drawn) / (segments - i);
            int color = blendArgb(from, to, (i + 0.5f) / segments);
            graphics.fill(x + drawn, y, x + drawn + segWidth, y + height, color);
            drawn += segWidth;
        }
    }

    protected int fitPanelWidth() {
        return 0;
    }

    protected int fitPanelHeight() {
        return 0;
    }

    protected final float fitScale() {
        int pw = fitPanelWidth();
        int ph = fitPanelHeight();
        if (pw <= 0 || ph <= 0) return 1f;
        float byH = (this.height * 0.82f) / ph;
        float byW = (this.width * 0.90f) / pw;
        return Math.max(0.5f, Math.min(Math.min(byH, byW), 2.5f));
    }

    protected final double virtualX(double screenX) {
        return (screenX - this.width / 2.0) / fitScale() + this.width / 2.0;
    }

    protected final double virtualY(double screenY) {
        return (screenY - this.height / 2.0) / fitScale() + this.height / 2.0;
    }

    protected final int scissorX(double layoutX) {
        return (int) Math.round((layoutX - this.width / 2.0) * fitScale() + this.width / 2.0);
    }

    protected final int scissorY(double layoutY) {
        return (int) Math.round((layoutY - this.height / 2.0) * fitScale() + this.height / 2.0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (drawsDimmedBackground()) {
            this.renderBackground(graphics, mouseX, mouseY, partialTick);
        }
        float fit = fitScale();
        boolean fitted = Math.abs(fit - 1f) > 0.001f;
        int tmx = fitted ? (int) Math.round(virtualX(mouseX)) : mouseX;
        int tmy = fitted ? (int) Math.round(virtualY(mouseY)) : mouseY;
        if (fitted) {
            float cx = this.width / 2f;
            float cy = this.height / 2f;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0);
            graphics.pose().scale(fit, fit, 1f);
            graphics.pose().translate(-cx, -cy, 0);
        }
        boolean animate = Config.UI_ANIMATIONS.get();
        float open = animate
            ? easeOutCubic(Math.min(1f, (net.minecraft.Util.getMillis() - openedAtMs) / 160f)) : 1f;
        boolean posed = animate && open < 1f;
        if (posed) {
            float scale = 0.96f + 0.04f * open;
            float cx = this.width / 2f;
            float cy = this.height / 2f;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0);
            graphics.pose().scale(scale, scale, 1f);
            graphics.pose().translate(-cx, -cy + (1f - open) * 10f, 0);
        }
        renderPolishedBackdrop(graphics, tmx, tmy, partialTick);
        for (net.minecraft.client.gui.components.Renderable renderable : this.renderables) {
            renderable.render(graphics, tmx, tmy, partialTick);
        }
        renderPolishedExtras(graphics, tmx, tmy, partialTick);
        if (posed) {
            graphics.pose().popPose();
        }
        if (fitted) {
            graphics.pose().popPose();
        }
    }

    protected void renderPolishedBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    protected void renderPolishedExtras(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    protected static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }
}
