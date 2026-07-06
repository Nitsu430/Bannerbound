package com.bannerbound.core.api.research;

import java.util.function.Consumer;
import java.util.function.IntPredicate;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side bridge for opening a Create-style Ponder scene from the research tree. Core
 * never depends on Create: a Create-aware expansion registers an opener here at client setup,
 * and the research screen then shows a "Hold [W] to Ponder" line on guided nodes and forwards
 * a held trigger key to it. With no opener registered, {@link #isAvailable} is false and the
 * screen draws no ponder affordance at all, so players without Create see no trace of the
 * system. The trigger key defaults to W to line up with Create's own keybind; an expansion
 * may honour a player-rebound Create key via {@link #setKeyMatcher}.
 * <p>
 * Hold semantics mirror Create's inventory tooltip: tap-and-release does nothing -- the key
 * must be held over a guided node for {@link #HOLD_DURATION_MILLIS} ms, and only one node can
 * charge at a time. The screen drives the lifecycle from its keyPressed / keyReleased / tick
 * hooks via {@link #beginHold}, {@link #cancelHold} and {@link #tickHold} (which cancels when
 * the mouse drifts off the charging node and fires {@link #open} at full charge; {@link #open}
 * also works standalone to bypass the hold, e.g. for a debug command). Progress is measured
 * in wall-clock nanoseconds, so the render path should read {@link #holdProgress} every frame
 * for a bar that fills smoothly between 20 Hz game ticks; {@link #holdToPonderHint} renders
 * it as a row of '|' glyphs (GRAY filled / DARK_GRAY remaining) sized to match the hint text.
 */
@OnlyIn(Dist.CLIENT)
public final class ResearchPonderBridge {
    public static final long HOLD_DURATION_MILLIS = 750L;
    private static final long HOLD_DURATION_NANOS = HOLD_DURATION_MILLIS * 1_000_000L;

    private static Consumer<String> opener = null;
    private static IntPredicate keyMatcher = key -> key == GLFW.GLFW_KEY_W;

    @Nullable private static String holdingSceneId = null;
    private static long holdStartNanos = -1L;

    private ResearchPonderBridge() {}

    public static void setOpener(Consumer<String> opener) {
        ResearchPonderBridge.opener = opener;
    }

    public static void setKeyMatcher(IntPredicate matcher) {
        ResearchPonderBridge.keyMatcher = matcher != null ? matcher : (key -> key == GLFW.GLFW_KEY_W);
    }

    public static boolean isAvailable() {
        return opener != null;
    }

    public static boolean matchesKey(int keyCode) {
        return keyMatcher.test(keyCode);
    }

    public static void open(String sceneId) {
        Consumer<String> o = opener;
        if (o != null && sceneId != null && !sceneId.isEmpty()) {
            o.accept(sceneId);
        }
    }

    public static boolean beginHold(@Nullable String hoveredSceneId, int keyCode) {
        if (!isAvailable() || !matchesKey(keyCode)) return false;
        if (hoveredSceneId == null || hoveredSceneId.isEmpty()) return false;
        if (hoveredSceneId.equals(holdingSceneId)) {
            return true; // key auto-repeat re-enters here; must not restart the timer for this node
        }
        holdingSceneId = hoveredSceneId;
        holdStartNanos = System.nanoTime();
        return true;
    }

    public static boolean cancelHold(int keyCode) {
        if (!matchesKey(keyCode)) return false;
        boolean wasHolding = holdingSceneId != null;
        holdingSceneId = null;
        holdStartNanos = -1L;
        return wasHolding;
    }

    public static float tickHold(@Nullable String currentlyHoveredSceneId) {
        if (holdingSceneId == null) return 0f;
        if (currentlyHoveredSceneId == null || !holdingSceneId.equals(currentlyHoveredSceneId)) {
            holdingSceneId = null;
            holdStartNanos = -1L;
            return 0f;
        }
        float p = computeProgress();
        if (p >= 1f) {
            String toOpen = holdingSceneId;
            holdingSceneId = null;
            holdStartNanos = -1L;
            open(toOpen);
            return 1f;
        }
        return p;
    }

    public static boolean isHolding() {
        return holdingSceneId != null;
    }

    public static float holdProgress() {
        return computeProgress();
    }

    private static float computeProgress() {
        if (holdingSceneId == null || holdStartNanos < 0L) {
            return 0f;
        }
        long elapsed = System.nanoTime() - holdStartNanos;
        if (elapsed <= 0L) return 0f;
        if (elapsed >= HOLD_DURATION_NANOS) return 1f;
        return (float) ((double) elapsed / (double) HOLD_DURATION_NANOS);
    }

    public static Component holdToPonderHint(Font font) {
        final String baseText = "Hold [W] to Ponder";
        float progress = holdProgress();
        if (progress <= 0f) {
            return Component.literal("Hold ")
                .append(Component.literal("[W]").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" to Ponder"))
                .withStyle(ChatFormatting.DARK_GRAY);
        }
        float charWidth = font.width("|");
        int total = Math.max(1, (int) Math.floor(font.width(baseText) / charWidth));
        int current = Math.max(0, Math.min(total, (int) (progress * total)));
        MutableComponent bar = Component.literal("");
        if (current > 0) {
            bar.append(Component.literal("|".repeat(current)).withStyle(ChatFormatting.GRAY));
        }
        if (current < total) {
            bar.append(Component.literal("|".repeat(total - current)).withStyle(ChatFormatting.DARK_GRAY));
        }
        return bar;
    }
}
