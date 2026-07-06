package com.bannerbound.core.api.settlement;

import net.minecraft.network.chat.Component;

/**
 * Bannerbound tech-tier enum. Ordinals are persisted ({@code Age} in {@link Settlement#save()},
 * {@code WorldAge} in {@link SettlementData}), so existing entries must keep their position -
 * append new eras at the end. One historical exception: {@code CLASSICAL} was inserted between
 * {@code ANCIENT} and {@code MEDIEVAL} (keeping {@code min_age} ordering monotonic for the
 * Ancient->Classical->Medieval progression), shifting every later ordinal up by one; worlds
 * saved before that insert read one era too low - acceptable during development, re-create test
 * worlds. Lang keys follow {@code bannerbound.era.<lowercase_name>}.
 *
 * <p>{@link #immigrationFloor()} is a per-era population FLOOR, not a lifetime cap: immigration
 * fires whenever {@code population() < immigrationFloor()}, so a settlement whose citizens all
 * died drops back below the floor and resumes immigration automatically - no soft-lock where the
 * player can never rebuild. Ancient is locked at 7 (the original spec); every gate (immigration,
 * consumption, population-max floor) reads the per-era number through
 * {@link Settlement#immigrationFloor()}. The three slot counts - active policies, active
 * palettes, registration documents - all follow the same +1-per-era curve (Ancient=1,
 * Classical=2, ...) but stay separate methods so they can diverge later without entangling;
 * slots never shrink, so an era advance never evicts an active policy or claws back an issued
 * registration document. {@link #next()} returns {@code this} when already at the last tier.
 * Open: post-Ancient immigration floors are placeholder values to tune as each era ships.
 */
public enum Era {
    ANCIENT,
    CLASSICAL,
    MEDIEVAL,
    RENAISSANCE,
    INDUSTRIAL,
    DIESEL,
    ATOMIC,
    MODERN,
    FUTURE;

    public Component displayName() {
        return Component.translatable("bannerbound.era." + name().toLowerCase());
    }

    public String key() {
        return name().toLowerCase();
    }

    public int immigrationFloor() {
        return switch (this) {
            case ANCIENT     -> 7;
            case CLASSICAL   -> 10;
            case MEDIEVAL    -> 14;
            case RENAISSANCE -> 23;
            case INDUSTRIAL  -> 34;
            case DIESEL      -> 57;
            case ATOMIC      -> 80;
            case MODERN      -> 100;
            case FUTURE      -> 150;
        };
    }

    public int activePolicySlots() {
        return ordinal() + 1;
    }

    public int activePaletteSlots() {
        return ordinal() + 1;
    }

    public int registrationDocumentSlots() {
        return ordinal() + 1;
    }

    public Era next() {
        Era[] vals = values();
        int idx = ordinal();
        return idx + 1 < vals.length ? vals[idx + 1] : this;
    }

    public static Era fromOrdinalOrDefault(int ord) {
        Era[] vals = values();
        if (ord < 0 || ord >= vals.length) {
            return ANCIENT;
        }
        return vals[ord];
    }

    public static Era fromName(String name) {
        if (name == null) {
            return null;
        }
        for (Era e : values()) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }
}
