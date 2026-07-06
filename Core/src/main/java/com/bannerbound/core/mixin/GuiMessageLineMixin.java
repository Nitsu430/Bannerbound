package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.bannerbound.core.client.ChatLineAlpha;

import net.minecraft.client.GuiMessage;

/**
 * Carries a proximity-chat text alpha on each displayed GuiMessage.Line. Copied from the owning
 * GuiMessage when the line is split out (see ChatComponentMixin), and read by the render mixin to
 * fade distant chatter. Default 0 means "unset -> opaque".
 */
@Mixin(GuiMessage.Line.class)
@ApiStatus.Internal
public class GuiMessageLineMixin implements ChatLineAlpha {
    @Unique
    private float bannerbound$chatAlpha;

    @Override
    public float bannerbound$getChatAlpha() {
        return this.bannerbound$chatAlpha;
    }

    @Override
    public void bannerbound$setChatAlpha(float alpha) {
        this.bannerbound$chatAlpha = alpha;
    }
}
