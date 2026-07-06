package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side global "muffle" amount for world sound. SoundEngineMixin multiplies every
 * non-Bannerbound sound's volume by factor() each tick, so a factor below 1.0 makes the world
 * recede (the muffled-hearing effect); set() clamps to [0,1] (1.0 = normal / no muffle, 0.0 =
 * silent). Core owns this because mixins live in Core; an expansion (Antiquity's poison) drives it
 * via set() from its client tick, so Core stays ignorant of why the world is muffled. This is the
 * volume-duck tier of muffle (reliable everywhere); a true spectral low-pass would attach an OpenAL
 * EFX filter at the same hook, a future upgrade once it can be audio-tested.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SoundMuffle {
    private static volatile float factor = 1.0F;

    private SoundMuffle() {}

    public static void set(float f) {
        factor = Math.max(0.0F, Math.min(1.0F, f));
    }

    public static float factor() {
        return factor;
    }
}
