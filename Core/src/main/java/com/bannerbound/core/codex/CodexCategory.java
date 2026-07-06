package com.bannerbound.core.codex;

/**
 * A Chronicle sidebar category loaded from data/<namespace>/codex_categories; the compact
 * constructor null-defaults each field (blank title falls back to id).
 */
public record CodexCategory(String id, String title, String icon, int order) {
    public CodexCategory {
        id = id == null ? "" : id;
        title = title == null || title.isBlank() ? id : title;
        icon = icon == null ? "" : icon;
    }
}
