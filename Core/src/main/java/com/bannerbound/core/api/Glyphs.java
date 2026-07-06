package com.bannerbound.core.api;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

/**
 * Single source of truth for the mod's bitmap-font glyph codepoints and the {@link Component}
 * builders that work on EITHER side (server or client). The client-only {@code Icons} class covers
 * the same set with convenience methods, but server-side code cannot reach it due to its
 * {@code @OnlyIn(Dist.CLIENT)} guard - so shared pieces like the chief scoreboard-team prefix
 * (built on the server) live here instead. ICONS_FONT is the font all glyphs route through and
 * ICONS_STYLE is the white-on-anything style matching what Icons uses client-side, so both sides
 * build identical components; {@link #crown()} is the one crown-glyph definition used by both
 * {@code Icons.crown()} and the server-side chief prefix, keeping the glyph identical across chat,
 * nametag, TAB list, and scoreboard prefix.
 * <p>
 * Add a new glyph by registering the bitmap in {@code assets/bannerbound/font/icons.json} at a PUA
 * codepoint, then pinning that codepoint as a {@code public static final char} here so both sides
 * reference it without duplication. Each char constant MUST match its provider codepoint in
 * icons.json exactly (CROWN = U+E103).
 */
@ApiStatus.Internal
public final class Glyphs {
    public static final ResourceLocation ICONS_FONT =
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "icons");

    public static final Style ICONS_STYLE = Style.EMPTY
        .withFont(ICONS_FONT)
        .withColor(TextColor.fromRgb(0xFFFFFF));

    public static final char CROWN = (char) 0xE103;

    private Glyphs() {
    }

    public static MutableComponent crown() {
        return Component.literal(String.valueOf(CROWN)).withStyle(ICONS_STYLE);
    }
}
