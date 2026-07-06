package com.bannerbound.core.codex;

/**
 * Immutable snapshot of a single gameplay event, matched against Chronicle unlock conditions.
 * The typed factories (research/item/block/era/flag/advancement/custom) each fill only the
 * relevant field and blank the rest; the canonical constructor trims every field to "" so
 * condition comparisons never see null or stray whitespace.
 */
public record CodexTriggerContext(
    String type,
    String id,
    String item,
    String block,
    String era,
    String flag,
    String advancement,
    String job
) {
    public CodexTriggerContext {
        type = norm(type);
        id = norm(id);
        item = norm(item);
        block = norm(block);
        era = norm(era);
        flag = norm(flag);
        advancement = norm(advancement);
        job = norm(job);
    }

    public static CodexTriggerContext research(String id, boolean culture) {
        return new CodexTriggerContext(culture ? "culture_completed" : "research_completed",
            id, "", "", "", "", "", "");
    }

    public static CodexTriggerContext item(String item) {
        return new CodexTriggerContext("item_obtained", "", item, "", "", "", "", "");
    }

    public static CodexTriggerContext block(String type, String block) {
        return new CodexTriggerContext(type, "", "", block, "", "", "", "");
    }

    public static CodexTriggerContext era(String era) {
        return new CodexTriggerContext("era_reached", "", "", "", era, "", "", "");
    }

    public static CodexTriggerContext flag(String flag) {
        return new CodexTriggerContext("flag", "", "", "", "", flag, "", "");
    }

    public static CodexTriggerContext advancement(String advancement) {
        return new CodexTriggerContext("advancement", "", "", "", "", "", advancement, "");
    }

    public static CodexTriggerContext custom(String type, String id) {
        return new CodexTriggerContext(type, id, "", "", "", "", "", "");
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim();
    }
}
