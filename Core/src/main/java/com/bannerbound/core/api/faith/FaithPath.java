package com.bannerbound.core.api.faith;

/**
 * The mechanical ruleset a faith runs on (FAITH_PLAN.md): paths are templates we ship,
 * faiths are player-created instances. ASTROLOGY = passive domain boosts from player-drawn
 * constellations (gods live in the sky); TOTEMIC = active ritual boosts from the totem pole
 * (gods are HERE and make demands). Antiquity ships these two; later ages append - NEVER
 * reorder, ordinals are persisted and synced.
 */
public enum FaithPath {
    ASTROLOGY,
    TOTEMIC;

    public static FaithPath fromOrdinal(int ordinal) {
        FaithPath[] vals = values();
        return ordinal >= 0 && ordinal < vals.length ? vals[ordinal] : ASTROLOGY;
    }
}
