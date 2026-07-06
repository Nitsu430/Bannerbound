package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * Duck-typing interface mixed onto {@code net.minecraft.client.GuiMessage} and {@code GuiMessage.Line}
 * to carry a per-message text alpha (audibility) for proximity chat. Convention: {@code 0} means
 * "unset -> render fully opaque" (the default for ordinary messages); a proximity message stamps a
 * value in {@code (0,1]} that the chat render mixin multiplies into the line's text alpha.
 */
@ApiStatus.Internal
public interface ChatLineAlpha {
    float bannerbound$getChatAlpha();

    void bannerbound$setChatAlpha(float alpha);
}
