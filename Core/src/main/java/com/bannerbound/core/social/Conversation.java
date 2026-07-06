package com.bannerbound.core.social;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * Transient server-side coordinator for one in-progress chat between two citizens, held on
 * {@code Settlement.activeConversations} so both {@code ConversationGoal}s find it via the partner's
 * UUID and share state. Never persisted: a save landing mid-conversation silently drops it on world
 * load - citizens fall back to patrol and a fresh conversation may spawn.
 * <p>
 * Initiator-owns-progress: both goals tick on the server main thread one at a time, so there is no
 * concurrency hazard, but to avoid double-counting the initiator (citizen {@link #a}) is the sole
 * writer of {@link #phase}, {@link #phaseStartGameTime}, {@link #currentBubble}, {@link #matches},
 * and {@link #outcomeApplied}. The joiner only mutates its own facing, navigation, DATA_BUBBLE slot,
 * and {@link #bArrived}. Phases store a shared start game-tick rather than an incrementing counter so
 * each participant reads "time in phase" as {@code now - phaseStartGameTime}, sidestepping tick-order
 * races between the two goal ticks within one game tick.
 * <p>
 * Outcome by match count (0..3 over the three bubbles): 0 -> fight (-10), 1 -> argument (-3),
 * 2 -> neutral (+1), 3 -> agreement (+5). {@link #begin} weights matching by happiness: it rolls a
 * per-bubble {@code commonProb = avg(happiness)/125} clamped to [0, 0.8]; on a hit both citizens are
 * forced onto the same random topic, else each rolls independently. This compensates for the 5-topic
 * enum diluting the natural match rate from 1/3 to 1/5 - without it the average conversation would
 * fall from neutral (+1) to argument (-3) purely from the topic-count change. Expected matches:
 * avg 0 -> ~0.6 (mostly fights), avg 50 -> ~1.56 (neutral-leaning), avg 100 -> ~2.52 (mostly
 * agreements), so friendships build faster when both parties are doing well.
 */
public final class Conversation {
    public static final double SOCIAL_RADIUS = 10.0;
    public static final double MEET_REACH_SQ = 1.5 * 1.5;
    public static final int WALK_TIMEOUT_TICKS = 200;
    public static final int FACE_OFF_TICKS = 20;
    public static final int BUBBLE_TICKS = 80; // 4s - must match the client bubble scale-in+hold+fade animation
    public static final int GAP_TICKS = 20;
    public static final int RESOLVING_TICKS = 30;

    public static final int FIGHT_DELTA     = -10;
    public static final int ARGUMENT_DELTA  = -3;
    public static final int NEUTRAL_DELTA   = +1;
    public static final int AGREEMENT_DELTA = +5;

    public enum Phase { WALK_TO_MEET, FACE_OFF, BUBBLE, GAP, RESOLVING, DONE }

    public final UUID a;
    public final UUID b;
    public final BlockPos meetPosA;
    public final BlockPos meetPosB;
    public final ConversationTopic[] topicsA;
    public final ConversationTopic[] topicsB;

    public Phase phase = Phase.WALK_TO_MEET;
    public long phaseStartGameTime = -1L;
    public int currentBubble = 0;
    public int matches = 0;
    public boolean outcomeApplied = false;

    public boolean aArrived = false;
    public boolean bArrived = false;

    private Conversation(UUID a, UUID b, BlockPos meetPosA, BlockPos meetPosB,
                          ConversationTopic[] topicsA, ConversationTopic[] topicsB) {
        this.a = a;
        this.b = b;
        this.meetPosA = meetPosA;
        this.meetPosB = meetPosB;
        this.topicsA = topicsA;
        this.topicsB = topicsB;
    }

    public static Conversation begin(UUID initiator, UUID partner,
                                      BlockPos initiatorStand, BlockPos partnerStand,
                                      int initiatorHappiness, int partnerHappiness,
                                      boolean allowWorkTopics,
                                      RandomSource rng) {
        double avg = (initiatorHappiness + partnerHappiness) * 0.5;
        double commonProb = Math.max(0.0, Math.min(0.8, avg / 125.0));

        ConversationTopic[] ta = new ConversationTopic[3];
        ConversationTopic[] tb = new ConversationTopic[3];
        for (int i = 0; i < 3; i++) {
            if (rng.nextDouble() < commonProb) {
                ConversationTopic shared = ConversationTopic.random(rng, allowWorkTopics);
                ta[i] = shared;
                tb[i] = shared;
            } else {
                ta[i] = ConversationTopic.random(rng, allowWorkTopics);
                tb[i] = ConversationTopic.random(rng, allowWorkTopics);
            }
        }
        return new Conversation(initiator, partner, initiatorStand, partnerStand, ta, tb);
    }

    public boolean isParticipant(UUID id) {
        return a.equals(id) || b.equals(id);
    }

    @Nullable
    public UUID otherSide(UUID self) {
        if (a.equals(self)) return b;
        if (b.equals(self)) return a;
        return null;
    }

    public BlockPos standFor(UUID self) {
        return a.equals(self) ? meetPosA : meetPosB;
    }

    public ConversationTopic topicFor(UUID self, int bubbleIndex) {
        return a.equals(self) ? topicsA[bubbleIndex] : topicsB[bubbleIndex];
    }

    public void markArrived(UUID self) {
        if (a.equals(self)) aArrived = true;
        else if (b.equals(self)) bArrived = true;
    }

    public boolean bothArrived() {
        return aArrived && bArrived;
    }

    public void transitionTo(Phase next, long now) {
        this.phase = next;
        this.phaseStartGameTime = now;
    }

    public int ticksInPhase(long now) {
        if (phaseStartGameTime < 0L) return 0;
        long elapsed = now - phaseStartGameTime;
        return elapsed < 0L ? 0 : (int) elapsed;
    }

    public static int outcomeDelta(int matches) {
        return switch (matches) {
            case 0 -> FIGHT_DELTA;
            case 1 -> ARGUMENT_DELTA;
            case 2 -> NEUTRAL_DELTA;
            case 3 -> AGREEMENT_DELTA;
            default -> 0;
        };
    }
}
