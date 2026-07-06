package com.bannerbound.core.api.settlement;

/**
 * A randomly-rolled, permanent characteristic of a citizen. Each trait carries a roll chance
 * ([0,1], applied independently when the citizen immigrates) and a genetic flag. The genetic flag
 * is forward-looking: when babies + family trees land, genetic traits will be inheritable from
 * parents rather than rolled fresh, while non-genetic traits keep being rolled per-citizen
 * regardless of lineage. Nothing reads the flag yet - it's recorded now so the trait table
 * doesn't need a migration later.
 *
 * <p>Stored on the entity as a bitmask of ordinal() bits (NBT + synced data), so existing entries
 * must keep their position; only append new ones at the end.
 */
public enum CitizenTrait {
    LEFT_HANDED("left_handed", 0.10f, true);

    private final String id;
    private final float chance;
    private final boolean genetic;

    CitizenTrait(String id, float chance, boolean genetic) {
        this.id = id;
        this.chance = chance;
        this.genetic = genetic;
    }

    public String id() {
        return id;
    }

    public float chance() {
        return chance;
    }

    public boolean genetic() {
        return genetic;
    }

    public int bit() {
        return 1 << ordinal();
    }
}
