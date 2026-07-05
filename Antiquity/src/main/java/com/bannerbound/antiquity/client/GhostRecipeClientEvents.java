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
 * Client-side event handlers for the workstation ghost-recipe previews (click routing + crosshair affordance).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class GhostRecipeClientEvents {

    /*
     * Client-side right-click routing for the ghost-preview targets. The browse arrows and the ghost
     * result are billboards in the air — no block, no entity — so vanilla's pick can't hit them; this
     * intercepts the use-key press, ray-tests the targets of nearby workstations, and forwards the hit
     * as a {@link GhostActionPayload} (cancelling the vanilla use so nothing behind reacts). Aiming at
     * the workstation block itself always keeps the normal insert/remove/craft interactions.
     */
    private GhostRecipeClientEvents() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        // Shared with the green-crosshair affordance so what you see is exactly what you can click.
        GhostClickTargets.Hover hover = GhostClickTargets.findHovered(Minecraft.getInstance());
        if (hover == null) return;
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(new GhostActionPayload(hover.pos(), hover.picked().target().action()));
    }

    /*
     * Draws a small green plus over the crosshair whenever it's aimed at a clickable floating ghost target
     * (a workstation's recipe preview / browse arrows) — the affordance that says "you can right-click
     * this". Reuses {@link GhostClickTargets#findHovered} so it lights up under exactly the same conditions
     * the click handler ({@code GhostRecipeClientEvents}) acts, for every ghost workstation (crafting stone,
     * fletching station, carpenter's table).
     */
    private static final int GREEN = 0xFF53E85A;

    @SubscribeEvent
    static void onCrosshair(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        // Lights up for the shared ghost picker/arrows AND the carpenter's-table queue chips.
        if (GhostClickTargets.findHovered(mc) == null
                && StationReadoutEvents.findHoveredQueue(mc) == null) return;

        GuiGraphics g = event.getGuiGraphics();
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        g.fill(cx - 6, cy - 1, cx + 6, cy + 1, GREEN);
        g.fill(cx - 1, cy - 6, cx + 1, cy + 6, GREEN);
    }
}
