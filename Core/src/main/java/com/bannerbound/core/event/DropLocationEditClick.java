package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.client.DropLocationEditState;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side capture of the world clicks that drive drop-location edit mode (see
 * {@link DropLocationEditState}). Right-click on a block marks it as the drop-off; left-click, or
 * opening any screen (Escape), cancels the mode. Leaving edit mode clears the client state AND sends
 * CancelDropLocationEditPayload so the server drops its guard flag and the next right-click opens
 * containers normally again.
 *
 * Design note: the right-click handler suppresses ONLY the held-item use, deliberately NOT the block
 * use. Letting the block-use packet reach the server is what lets DropLocationServerGuard mark the
 * block (and cancel the container-open) authoritatively with no client/server race; the server
 * replies with EndDropLocationEditPayload on success.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class DropLocationEditClick {
    private DropLocationEditClick() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!DropLocationEditState.isActive()) return;
        if (!(event.getEntity() instanceof LocalPlayer)) return;
        // Suppress only held-item use; must NOT cancel block use or the server never gets to mark.
        event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!DropLocationEditState.isActive()) return;
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        event.setCanceled(true);
        cancel(player);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (!DropLocationEditState.isActive()) return;
        if (!(event.getEntity() instanceof LocalPlayer player)) return;
        cancel(player);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!DropLocationEditState.isActive()) return;
        if (Minecraft.getInstance().screen != null) {
            exitEditMode();
        }
    }

    private static void cancel(LocalPlayer player) {
        exitEditMode();
        player.displayClientMessage(Component.translatable("bannerbound.job.drop_edit_cancelled")
            .withStyle(ChatFormatting.GRAY), true);
    }

    private static void exitEditMode() {
        DropLocationEditState.clear();
        PacketDistributor.sendToServer(new com.bannerbound.core.network.CancelDropLocationEditPayload());
    }
}
