package com.bannerbound.core.codex;

import java.util.List;

/**
 * One authorable block in a Chronicle article page; type selects which fields matter (text,
 * image, recipe, embedded entry link, ponder clip, item list). The canonical constructor
 * null-normalizes every field so a partial JSON block renders safely.
 */
public record CodexPageElement(
    String type,
    String text,
    String caption,
    String entry,
    String clip,
    String image,
    String recipe,
    List<String> items
) {
    public CodexPageElement {
        type = type == null || type.isBlank() ? "text" : type;
        text = text == null ? "" : text;
        caption = caption == null ? "" : caption;
        entry = entry == null ? "" : entry;
        clip = clip == null ? "" : clip;
        image = image == null ? "" : image;
        recipe = recipe == null ? "" : recipe;
        items = items == null ? List.of() : List.copyOf(items);
    }
}
