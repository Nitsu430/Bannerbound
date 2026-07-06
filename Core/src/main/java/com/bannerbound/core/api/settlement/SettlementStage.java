package com.bannerbound.core.api.settlement;

/**
 * Growth stage of a settlement: a named milestone ladder layered on population + government. Each
 * step-up is a visible moment (chat + fireworks) and gates how much per-citizen detail the
 * settlement simulates. HEARTH is the pre-government 1:1 start; TRIBE is post-Code-of-Laws (a
 * government exists), still 1:1; VILLAGE (pop >= VILLAGE_THRESHOLD) is the cost transition to
 * cheap-brain citizens, aggregate mood, auto-professions and worker groups (Phase 1 only detects +
 * celebrates it, not yet wired); TOWN/CITY are reserved for the later decorative-mover crowd.
 * Order-dependence: a stage-up is detected when stage().ordinal() rises, so entries must stay in
 * ascending-milestone order. key() lowercases the name for translation keys (bannerbound.stage.<key>).
 */
public enum SettlementStage {
    HEARTH,
    TRIBE,
    VILLAGE,
    TOWN,
    CITY;

    public static final int VILLAGE_THRESHOLD = 25;

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
