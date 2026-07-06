package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Era;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Inline icon glyphs for the {@code bannerbound:icons} and {@code bannerbound:resource_icons}
 * bitmap fonts, letting any {@link Component} embed a sprite between text runs -- the clean
 * MC-native way to mix an image with text (vs overlay-blitting a rendered tooltip). Server-side
 * code that cannot reach this client-only class gets the same glyphs from
 * {@link com.bannerbound.core.api.Glyphs} (see {@link #crown}).
 *
 * <p>Every glyph string is a Private Use Area codepoint that must line up with a provider in
 * {@code assets/bannerbound/font/icons.json} (food U+E010.., culture U+E020.., science U+E030..,
 * speech U+E040, faith U+E050.., pregnancy U+E102, crown U+E103). The era-keyed arrays are
 * indexed by {@link Era#ordinal()}; {@link #glyphFor} clamps out-of-range values and, for an era
 * whose art has not shipped, falls back to the nearest earlier era that has a glyph. Reskinning
 * an icon is just swapping a provider's PNG path in icons.json -- no Java change.
 *
 * <p>ICONS_STYLE / RESOURCE_ICONS_STYLE pin the glyph to white deliberately: a child Style's
 * color overrides its parent's, so an icon nested under a withStyle(GREEN) component would be
 * dyed unless it forces its own color.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class Icons {
    public static final ResourceLocation ICONS_FONT = ResourceLocation.fromNamespaceAndPath("bannerbound", "icons");
    public static final ResourceLocation RESOURCE_ICONS_FONT =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "resource_icons");
    public static final Style ICONS_STYLE = Style.EMPTY
        .withFont(ICONS_FONT)
        .withColor(TextColor.fromRgb(0xFFFFFF));
    public static final Style RESOURCE_ICONS_STYLE = Style.EMPTY
        .withFont(RESOURCE_ICONS_FONT)
        .withColor(TextColor.fromRgb(0xFFFFFF));

    private static final String SCIENCE_GLYPH = "";
    private static final String FOOD_GLYPH = "";
    private static final String CULTURE_GLYPH = "";
    private static final String HAPPINESS_HIGH_GLYPH = "";
    private static final String HAPPINESS_MID_GLYPH = "";
    private static final String HAPPINESS_LOW_GLYPH = "";

    private static final String[] FOOD_GLYPHS = {
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    };
    private static final String[] CULTURE_GLYPHS = {
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    };

    private static final String[] FAITH_GLYPHS = {
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    };

    private static final String[] SCIENCE_GLYPHS = {
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
    };

    private Icons() {
    }

    public static MutableComponent science() {
        return Component.literal(SCIENCE_GLYPH).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent food() {
        return Component.literal(FOOD_GLYPH).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent culture() {
        return Component.literal(CULTURE_GLYPH).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent food(Era era) {
        return Component.literal(glyphFor(FOOD_GLYPHS, era)).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent culture(Era era) {
        return Component.literal(glyphFor(CULTURE_GLYPHS, era)).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent science(Era era) {
        return Component.literal(glyphFor(SCIENCE_GLYPHS, era)).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent faith() {
        return Component.literal(FAITH_GLYPHS[0]).withStyle(ICONS_STYLE);
    }

    public static MutableComponent faith(Era era) {
        return Component.literal(glyphFor(FAITH_GLYPHS, era)).withStyle(ICONS_STYLE);
    }

    public static MutableComponent bubble() {
        return Component.literal(String.valueOf((char) 0xE040)).withStyle(ICONS_STYLE);
    }

    public static MutableComponent pregnant() {
        return Component.literal(String.valueOf((char) 0xE102)).withStyle(ICONS_STYLE);
    }

    public static MutableComponent crown() {
        return com.bannerbound.core.api.Glyphs.crown();
    }

    private static String glyphFor(String[] table, Era era) {
        int ord = era == null ? 0 : era.ordinal();
        if (ord < 0 || ord >= table.length) ord = 0;
        while (ord > 0 && table[ord].isEmpty()) ord--;
        return table[ord];
    }

    public static MutableComponent happinessForBucket(int bucket) {
        String glyph = switch (bucket) {
            case 2 -> HAPPINESS_HIGH_GLYPH;
            case 1 -> HAPPINESS_MID_GLYPH;
            default -> HAPPINESS_LOW_GLYPH;
        };
        return Component.literal(glyph).withStyle(ICONS_STYLE);
    }

    public static MutableComponent happiness(int value, int max) {
        if (max <= 0) {
            return Component.literal(HAPPINESS_MID_GLYPH).withStyle(ICONS_STYLE);
        }
        double ratio = (double) value / max;
        String glyph;
        if (ratio >= 0.7) {
            glyph = HAPPINESS_HIGH_GLYPH;
        } else if (ratio >= 0.4) {
            glyph = HAPPINESS_MID_GLYPH;
        } else {
            glyph = HAPPINESS_LOW_GLYPH;
        }
        return Component.literal(glyph).withStyle(ICONS_STYLE);
    }
}
