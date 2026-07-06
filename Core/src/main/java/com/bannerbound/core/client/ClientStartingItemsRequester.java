package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.network.RequestStartingItemsPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * On client login, re-requests the starting-items set from the server. The server's push
 * (OnDatapackSyncEvent) can miss during the join handshake, leaving ClientStartingItems empty --
 * which makes JEI fall back to showing every item as a "?" and breaks name search. Asking again
 * after login guarantees the set arrives. Registered as a CLIENT-dist EventBusSubscriber.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class ClientStartingItemsRequester {
    private ClientStartingItemsRequester() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        PacketDistributor.sendToServer(RequestStartingItemsPayload.INSTANCE);
    }
}
