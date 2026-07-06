package com.bannerbound.core.entity;

import net.minecraft.server.level.ServerLevel;

/**
 * Step 14 - citizen-on-citizen brawl escalation. Not a vanilla {@code Goal}; a static helper called
 * from {@link ConversationGoal#onResolvingEntry()} when a 0-match argument turns physical (anarchy
 * or low compliance, 25% chance). {@link #escalate} just lights the first match: it schedules each
 * side's first swing, delayed 5 ticks (the same value the brawl retaliation uses, so an escalated
 * argument reads as a natural continuation of the conversation rather than a scripted cut). From
 * there the existing brawl mechanic drives the fight on its own -
 * {@link CitizenEntity#schedulePendingRetaliation} -> {@code BrawlRetaliationGoal} fires the swing
 * -> {@code CitizenBrawlEvents} catches the LivingIncomingDamageEvent and schedules the OTHER
 * side's retaliation -> until one runs, dies, or the brawl window expires. Death of either citizen
 * flows through {@code CitizenLifecycleEvents.onCitizenDeath} (broadcast + roster prune) and
 * {@code CitizenHarmResentmentEvents} (witnesses resent the killer) for free.
 */
public final class ConflictGoal {
    private static final int FIRST_SWING_DELAY_TICKS = 5;

    private ConflictGoal() {
    }

    public static void escalate(CitizenEntity a, CitizenEntity b, ServerLevel sl) {
        if (a == null || b == null || sl == null) return;
        if (!a.isAlive() || !b.isAlive()) return;
        long fireAt = sl.getGameTime() + FIRST_SWING_DELAY_TICKS;
        a.schedulePendingRetaliation(b.getUUID(), fireAt);
        b.schedulePendingRetaliation(a.getUUID(), fireAt);
    }
}
