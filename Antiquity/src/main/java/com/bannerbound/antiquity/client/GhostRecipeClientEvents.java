package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.GhostActionPayload;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side event handlers for the workstation ghost-recipe previews: right-click routing plus
 * the green-crosshair affordance. The browse arrows and ghost result are billboards in the air
 * (no block, no entity), so vanilla's pick can never hit them; {@link #onInteract} intercepts the
 * use-key press, ray-tests nearby workstations' targets via
 * {@link GhostClickTargets#findHovered}, and forwards the hit as a {@link GhostActionPayload},
 * cancelling the vanilla use so nothing behind reacts. Aiming at the workstation block itself
 * always keeps the normal insert/remove/craft interactions. {@link #onCrosshair} draws a small
 * green plus over the crosshair while a clickable floating target is hovered, reusing the same
 * findHovered (plus the carpenter's-table queue chips via StationReadoutEvents.findHoveredQueue)
 * so the affordance lights up under exactly the conditions a click would act, for every ghost
 * workstation (crafting stone, fletching station, carpenter's table).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class GhostRecipeClientEvents {

    private GhostRecipeClientEvents() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        GhostClickTargets.Hover hover = GhostClickTargets.findHovered(Minecraft.getInstance());
        if (hover == null) return;
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(new GhostActionPayload(hover.pos(), hover.picked().target().action()));
    }

    private static final int GREEN = 0xFF53E85A;

    @SubscribeEvent
    static void onCrosshair(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        if (GhostClickTargets.findHovered(mc) == null
                && StationReadoutEvents.findHoveredQueue(mc) == null) return;

        GuiGraphics g = event.getGuiGraphics();
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        g.fill(cx - 6, cy - 1, cx + 6, cy + 1, GREEN);
        g.fill(cx - 1, cy - 6, cx + 1, cy + 6, GREEN);
    }
}
