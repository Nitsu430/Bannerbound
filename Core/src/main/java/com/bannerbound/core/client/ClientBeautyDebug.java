package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client state for the beauty-debug overlay (toggled with the {@code B} key by default, rebindable
 * in Controls). While enabled, {@link BeautyDebugHudLayer} draws the looked-at block's name and
 * appeal score next to the crosshair. Holds the latest server reply for one block position (see
 * {@code RequestBlockAppealPayload}): the appeal is already culture-adjusted for the owning
 * settlement's styles and diminished for its queue slot, so the overlay shows it verbatim and
 * every client agrees on the value. A home-scope reply ({@link #resultInHouse()}) means the block
 * lies inside one of the requesting player's home selections; the overlay then labels the value
 * "Home appeal" and reads the per-home queue slot rather than the chunk's.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientBeautyDebug {
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
        "key.bannerbound.beauty_debug", GLFW.GLFW_KEY_B, "key.categories.bannerbound");

    private static boolean enabled = false;
    private static BlockPos resultPos;
    private static int resultQueuePos;
    private static boolean resultTracked;
    private static boolean resultInHouse;
    private static float resultAppeal;

    private ClientBeautyDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setResult(BlockPos pos, int queuePosition, boolean tracked, boolean inHouse,
                                 float appeal) {
        resultPos = pos;
        resultQueuePos = queuePosition;
        resultTracked = tracked;
        resultInHouse = inHouse;
        resultAppeal = appeal;
    }

    public static boolean hasResultFor(BlockPos pos) {
        return pos.equals(resultPos);
    }

    public static int resultQueuePos() {
        return resultQueuePos;
    }

    public static boolean resultTracked() {
        return resultTracked;
    }

    public static boolean resultInHouse() {
        return resultInHouse;
    }

    public static float resultAppeal() {
        return resultAppeal;
    }

    public static void toggle() {
        enabled = !enabled;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(
                enabled ? "bannerbound.beauty_debug.on" : "bannerbound.beauty_debug.off"), true);
        }
    }
}
