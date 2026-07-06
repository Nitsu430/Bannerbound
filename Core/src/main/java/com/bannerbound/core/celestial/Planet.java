package com.bannerbound.core.celestial;

/**
 * One wandering star of the world's procedural solar system (FAITH_PLAN.md Part 3). Pure orbital
 * elements + dormant physical data - ON RAILS, never N-body: positions are closed-form functions of
 * game time, derived in {@link SkyField}. Fields: {@code a} = orbital radius in observer-orbit units
 * (observer world = 1.0); {@code periodDays} = orbital period in Minecraft days (Kepler: T = a^1.5 x
 * observer year); {@code phaseDeg} = heliocentric longitude at day 0; {@code baseSize} = sprite
 * half-size on the r=100 celestial sphere at distance 1.0; {@code rgb} = tint; {@code inclinationDeg}
 * = orbital tilt vs the ecliptic, scattering planets AROUND the zodiac band instead of pinning them
 * to a line; {@code nodeDeg} = longitude of the ascending node (where the tilted orbit crosses the
 * ecliptic).
 *
 * <p>The dormant fields ({@code rings} / {@code moonCount} / {@code axialTiltDeg}) are rolled at
 * generation but unrendered: later ages REVEAL them (Renaissance telescopes), they never
 * re-generate. Planet identity is the list index under a fixed sky seed - stable only as long as the
 * generation code for earlier rolls doesn't change, which is why the generation order in
 * {@link SkyField} must never be reordered once worlds exist.
 */
public record Planet(
        double a,
        double periodDays,
        double phaseDeg,
        float baseSize,
        int rgb,
        boolean rings,
        int moonCount,
        double axialTiltDeg,
        double inclinationDeg,
        double nodeDeg) {

    public double helioLonDeg(double days) {
        return SkyField.wrapDeg(phaseDeg + 360.0 * days / periodDays);
    }
}
