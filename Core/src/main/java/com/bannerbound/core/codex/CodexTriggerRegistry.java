package com.bannerbound.core.codex;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerPlayer;

/**
 * Extension point for mods that need Chronicle unlock conditions beyond Core's built-ins.
 * Register a ConditionHandler under a condition type string (thread-safe map); CodexCondition
 * dispatches to it. Handlers default to event-only matching (type equals the event's type) and
 * report themselves not state-based, so reconcile only re-checks them on a live event unless the
 * handler overrides isStateBased.
 */
public final class CodexTriggerRegistry {
    private static final Map<String, ConditionHandler> HANDLERS = new ConcurrentHashMap<>();

    private CodexTriggerRegistry() {
    }

    public static void register(String type, ConditionHandler handler) {
        if (type == null || type.isBlank() || handler == null) return;
        HANDLERS.put(type.trim(), handler);
    }

    static ConditionHandler get(String type) {
        return type == null ? null : HANDLERS.get(type.trim());
    }

    public interface ConditionHandler {
        default boolean isStateBased(CodexCondition condition) {
            return false;
        }

        default boolean matchesEvent(CodexCondition condition, CodexTriggerContext event) {
            return event != null && condition.type().equals(event.type());
        }

        boolean isSatisfied(CodexCondition condition, ServerPlayer player, Settlement settlement,
                            CodexTriggerContext event);
    }
}
