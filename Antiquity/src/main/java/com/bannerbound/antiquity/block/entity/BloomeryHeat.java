package com.bannerbound.antiquity.block.entity;

import com.bannerbound.antiquity.metalworking.MetalworkingData;

/**
 * The bloomery's temperature model (METALWORKING_PLAN.md Part 1). Heat is fire-driven and
 * independent of the contents: while lit with the door shut the temperature climbs toward a
 * ceiling (base fire + a decaying bellows boost) and decays to 0 otherwise. Progress on whatever
 * sits inside only advances while the temperature is inside the active recipe's band; the Band
 * verdict mirrors the looked-at readout labels, and rate() maps it to a progress multiplier
 * (full in GOOD = the green zone hugging the band's low edge, slow in OKAY/yellow, stalled
 * otherwise). A metal's crucible melt band is [meltPoint, meltPoint + meltBandWidth], with
 * meltPoint (degrees C) the band's low edge. All tuning numbers (base ceiling, bellows, melt
 * points/bands, greenWidth) are data-driven via MetalworkingData; this class just reads them.
 * Only DEFAULT_BAND_LOW/DEFAULT_BAND_HIGH stay compile-time constants because they are the
 * codec fallback for an ore -> ingot recipe that doesn't specify a band.
 */
public final class BloomeryHeat {
    private BloomeryHeat() {}

    public static final int DEFAULT_BAND_LOW = 600;
    public static final int DEFAULT_BAND_HIGH = 1300;

    public static float baseCeiling() { return MetalworkingData.bloomery().baseCeiling(); }
    public static float bellowsPerPump() { return MetalworkingData.bloomery().bellowsPerPump(); }
    public static float bellowsMax() { return MetalworkingData.bloomery().bellowsMax(); }
    public static float bellowsDecay() { return MetalworkingData.bloomery().bellowsDecay(); }
    public static float climb() { return MetalworkingData.bloomery().climb(); }
    public static float fall() { return MetalworkingData.bloomery().fall(); }

    public static int meltPoint(String metal) {
        return MetalworkingData.meltPoint(metal);
    }

    public static int[] meltBand(String metal) {
        int m = meltPoint(metal);
        return new int[] { m, m + MetalworkingData.bloomery().meltBandWidth() };
    }

    public enum Band { NONE, NO_FIRE, TOO_LOW, OKAY, GOOD, TOO_HIGH }

    public static Band classify(boolean fireLit, boolean hasBand, float temp, int low, int high) {
        if (!hasBand) return Band.NONE;
        if (!fireLit && temp < 1f) return Band.NO_FIRE;
        if (temp < low) return Band.TOO_LOW;
        if (temp > high) return Band.TOO_HIGH;
        return temp <= low + MetalworkingData.bloomery().greenWidth() ? Band.GOOD : Band.OKAY;
    }

    public static float rate(Band band) {
        return switch (band) {
            case GOOD -> 1.0f;
            case OKAY -> 0.4f;
            default -> 0.0f;
        };
    }
}
