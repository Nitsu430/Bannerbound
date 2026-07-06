package com.bannerbound.core.api.settlement;

/**
 * Categorical icon used by a StatusEffect when rendered in the town hall Statuses tab. FOOD/CULTURE/
 * SCIENCE mirror the three top-level resource rates the icon font already has glyphs for; ALERT is
 * the warning/event marker (outpost lost, future attack notices) that renders with no rate value.
 * Extensible: add a new entry, add a glyph in the font, and add a case in the client-side
 * icon-rendering switch. Persisted by ordinal() (NBT + network), so existing entries must keep their
 * position and new ones only ever append at the end.
 */
public enum StatusEffectIcon {
    FOOD,
    CULTURE,
    SCIENCE,
    ALERT;

    public static StatusEffectIcon fromOrdinalOrFood(int ord) {
        StatusEffectIcon[] vals = values();
        if (ord < 0 || ord >= vals.length) return FOOD;
        return vals[ord];
    }
}
