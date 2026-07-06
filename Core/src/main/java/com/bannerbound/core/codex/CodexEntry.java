package com.bannerbound.core.codex;

import java.util.List;

/**
 * A data-authored Chronicle article, one JSON file under data/<namespace>/codex_entries.
 * The canonical constructor null-normalizes every field so partial or malformed JSON never
 * yields nulls downstream (missing category -> getting_started, blank title -> id, absent
 * unlock -> unlockedByDefault, absent tutorial -> empty). Loaded by CodexEntryLoader;
 * searchableText() feeds the Chronicle search box a lowercased blob of title, subtitle, and
 * every page's text and caption.
 */
public record CodexEntry(
    String id,
    String category,
    String title,
    String subtitle,
    String icon,
    int order,
    boolean secret,
    CodexUnlockRule unlock,
    String ponder,
    CodexTutorial tutorial,
    List<CodexPageElement> pages
) {
    public CodexEntry {
        id = id == null ? "" : id;
        category = category == null || category.isBlank() ? "bannerbound:getting_started" : category;
        title = title == null || title.isBlank() ? id : title;
        subtitle = subtitle == null ? "" : subtitle;
        icon = icon == null ? "" : icon;
        unlock = unlock == null ? CodexUnlockRule.unlockedByDefault() : unlock;
        ponder = ponder == null ? "" : ponder;
        tutorial = tutorial == null ? new CodexTutorial("", "", 10, List.of()) : tutorial;
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public String searchableText() {
        StringBuilder builder = new StringBuilder(title).append(' ').append(subtitle);
        for (CodexPageElement page : pages) {
            builder.append(' ').append(page.text()).append(' ').append(page.caption());
        }
        return builder.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
