package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Drunk text jumbling (GROG_PLAN.md Phase 3.5): when the local player is drunk enough, a fraction
 * of rendered glyphs flip to vanilla "obfuscated" (the cycling random-glyph effect), so the
 * in-world HUD and chat read as a jumbled, swimming mess - worse the drunker you are. jumble()
 * never fires while a screen (menu, inventory, pause) is open, so menus stay readable. The
 * per-glyph chance is set once per frame by {@link StatusClientEffects} via setChance;
 * {@code DrunkTextMixin} calls jumble() in the font's glyph sink. Cheap: a volatile read + a
 * null-check + one RNG roll per glyph.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class DrunkText {
    private static volatile float chance = 0.0F;
    private static final RandomSource RNG = RandomSource.create();

    private DrunkText() {}

    public static void setChance(float c) {
        chance = c;
    }

    public static boolean jumble() {
        float c = chance;
        if (c <= 0.0F || Minecraft.getInstance().screen != null) {
            return false;
        }
        return RNG.nextFloat() < c;
    }
}
