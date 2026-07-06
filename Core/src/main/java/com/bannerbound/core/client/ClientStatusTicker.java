package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * CLIENT-dist EventBusSubscriber that drives the per-tick remaining-time decrement on
 * ClientStatusState. Tied to the post-tick client event so the Town Hall Statuses progress bars
 * animate in lockstep with the server-side counter (both decrement once per game tick). When a
 * single-player game is paused no client tick fires, matching the server's pause semantics, so the
 * two counters never drift across pause/resume.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class ClientStatusTicker {
    private ClientStatusTicker() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level == null) return;
        ClientStatusState.tickClient();
    }
}
