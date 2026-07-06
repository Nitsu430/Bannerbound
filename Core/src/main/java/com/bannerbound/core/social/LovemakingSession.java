package com.bannerbound.core.social;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Transient (server-only, never persisted) record describing one in-progress procreation exchange
 * between a male and a female citizen sharing a home at night. Lives on {@link BabyMakingManager}'s
 * session list until either the 5th heart-particle burst completes (woman flagged pregnant, session
 * dropped) or a participant stops sleeping / dies / leaves the home (cancelled silently). Kept as a
 * record rather than entity fields so the protagonists' real state is untouched mid-sequence -
 * saving the world mid-session just loses the session, which is the correct failure mode.
 */
public record LovemakingSession(UUID motherId, UUID fatherId, BlockPos homePos, long startTick) {
}
