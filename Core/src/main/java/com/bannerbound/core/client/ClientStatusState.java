package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.StatusEffectIcon;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the local player's settlement status-effect list; source of truth for the Town
 * Hall Statuses tab. Authoritative replacements arrive wholesale via StatusEffectListPayload on
 * add/remove. Between syncs the client decrements remaining ticks locally (driven per client tick by
 * ClientResearchEvents.onClientTick) so progress bars animate smoothly without per-tick network
 * traffic; an effect whose remaining ticks hits zero is dropped immediately and the server's removal
 * payload confirms shortly after. State lives in an AtomicReference to an immutable list, so the
 * main-thread tick swap keeps concurrent renderer reads consistent.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientStatusState {
    public record Entry(
        UUID instanceId,
        String translationKey,
        List<String> args,
        StatusEffectIcon icon,
        double iconValue,
        int totalDurationTicks,
        int remainingTicks
    ) {}

    private static final AtomicReference<List<Entry>> EFFECTS =
        new AtomicReference<>(java.util.Collections.emptyList());

    private ClientStatusState() {
    }

    public static void setAll(List<Entry> entries) {
        EFFECTS.set(List.copyOf(entries));
    }

    public static List<Entry> getAll() {
        return EFFECTS.get();
    }

    public static void tickClient() {
        List<Entry> current = EFFECTS.get();
        if (current.isEmpty()) return;
        List<Entry> next = new ArrayList<>(current.size());
        for (Entry e : current) {
            int rem = e.remainingTicks() - 1;
            if (rem > 0) {
                next.add(new Entry(e.instanceId(), e.translationKey(), e.args(), e.icon(),
                    e.iconValue(), e.totalDurationTicks(), rem));
            }
        }
        EFFECTS.set(java.util.Collections.unmodifiableList(next));
    }
}
