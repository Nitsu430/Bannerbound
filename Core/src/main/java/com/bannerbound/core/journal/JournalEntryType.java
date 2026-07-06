package com.bannerbound.core.journal;

/** Broad journal buckets; declaration order IS the HUD sort order (JournalManager sorts by ordinal:
 *  crises first, then quests, then tutorials). byName falls back to QUEST for unknown/legacy tags. */
public enum JournalEntryType {
    CRISIS,
    QUEST,
    TUTORIAL;

    public static JournalEntryType byName(String name) {
        if (name != null) {
            for (JournalEntryType type : values()) {
                if (type.name().equalsIgnoreCase(name)) return type;
            }
        }
        return QUEST;
    }
}
