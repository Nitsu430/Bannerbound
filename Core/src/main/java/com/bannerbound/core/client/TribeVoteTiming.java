package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * Single source of truth for the tribe-vote reveal animation timing, shared so client and server
 * never drift. TribeVoteScreen paces the on-screen row reveals from these constants; the server
 * calls revealDurationMs() and passes it to schedulePendingChief / schedulePendingGovernment so
 * the chief/government enactment lands exactly when the last vote finishes revealing.
 *
 * <p>Reveal sequence: a per-vote delay starting at FIRST_DELAY_MS (the "Waiting..." opening pause)
 * and multiplied by DECAY_FACTOR (< 1, so reveals accelerate) each step, floored at MIN_DELAY_MS
 * (keeps the last few from firing on one frame). revealDurationMs(n) sums those delays and adds
 * FINAL_HOLD_MS, a trailing linger that lets the player read the result before the enactment and
 * its chat broadcast land.
 *
 * <p>Lives in the client package because the screen reads the constants directly, but is plain
 * Java with no client-only dependencies so the server can import it for the duration calc.
 */
@ApiStatus.Internal
public final class TribeVoteTiming {
    public static final long FIRST_DELAY_MS = 1000L;
    public static final double DECAY_FACTOR = 0.65;
    public static final long MIN_DELAY_MS = 80L;
    public static final long FINAL_HOLD_MS = 2000L;

    private TribeVoteTiming() {
    }

    public static long revealDurationMs(int voteCount) {
        if (voteCount <= 0) return FINAL_HOLD_MS;
        long total = 0L;
        double delay = FIRST_DELAY_MS;
        for (int i = 0; i < voteCount; i++) {
            total += (long) delay;
            delay = Math.max(MIN_DELAY_MS, delay * DECAY_FACTOR);
        }
        return total + FINAL_HOLD_MS;
    }
}
