package com.bannerbound.core.creative;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable metadata for one labelled band ("section") inside a creative-mode tab, like the dividers
 * in Create Aeronautics. Pure data (no client-only types), safe on either physical side: the band is
 * a greyscale GUI sprite multiplied by bannerTint so one strip serves every section, with the title
 * on a labelBackground chip in textColor. Register sections via CreativeSections.forTab; animateOnHover
 * is reserved for future animated strips and is a no-op today.
 */
public record CreativeSection(
        String id,
        Component title,
        ResourceLocation sprite,
        int bannerTint,
        int labelBackground,
        int textColor,
        boolean animateOnHover) {

    public static CreativeSection of(String id, Component title, ResourceLocation sprite, int bannerTint) {
        return new CreativeSection(id, title, sprite, bannerTint, 0xBB000000, 0xFFFFFFFF, false);
    }
}
