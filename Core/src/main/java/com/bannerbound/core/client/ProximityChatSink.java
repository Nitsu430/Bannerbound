package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.chat.Component;

/**
 * Duck-typing interface mixed onto {@code net.minecraft.client.gui.components.ChatComponent} so the
 * proximity-chat payload handler can push a chat line at a given audibility alpha (0-1 text
 * transparency; values >= 1 render fully opaque) without disturbing vanilla's message pipeline.
 */
@ApiStatus.Internal
public interface ProximityChatSink {
    void bannerbound$addProximityMessage(Component message, float alpha);
}
