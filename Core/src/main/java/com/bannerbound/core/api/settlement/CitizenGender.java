package com.bannerbound.core.api.settlement;

/**
 * A citizen's gender. Rolled 50/50 when a citizen immigrates and fixed for life. Drives three
 * cosmetic systems: the name pool it draws from, the gender icon shown before its name, and the
 * body model used to render it (male = wide/Steve, female = slim/Alex). texturePrefix returns
 * "man"/"woman" because the art files use those, not the enum's "male"/"female".
 *
 * <p>Persisted by ordinal() (NBT + synced entity data), so existing entries must keep their
 * position; only append new ones at the end.
 */
public enum CitizenGender {
    MALE,
    FEMALE;

    public static CitizenGender fromOrdinalOrMale(int ord) {
        CitizenGender[] vals = values();
        if (ord < 0 || ord >= vals.length) return MALE;
        return vals[ord];
    }

    public String key() {
        return name().toLowerCase();
    }

    public String texturePrefix() {
        return this == MALE ? "man" : "woman";
    }
}
