package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

/**
 * The glanceable "what is this worker doing / why isn't it" verdict shown as the headline on the
 * Job tab (CitizenScreen A1). One enum serves every job. Generic activity/block states (WORKING,
 * IDLE, HAULING, NO_TOOL, NO_DROPOFF, NO_STAMINA, BLOCKED, STORAGE_FULL) are derived server-side in
 * {@code ServerPayloadHandler.sendCitizenJobState} from already-available facts, so no per-goal
 * instrumentation is needed. The forester live sub-states (PLANTING, WAITING, HARVESTING,
 * NEEDS_SAPLINGS, AREA_FULL) and the crafter states are published by their goals via
 * {@link CitizenEntity#getCurrentWorkStatus()} (a transient field kept fresh and cleared to IDLE on
 * stop). NEED_MATERIALS is kept distinct from NO_ORDERS so the tab reads "waiting on materials"
 * rather than the misleading "nothing to craft"; NEEDS_SEEDS is the farmer analog of NEEDS_SAPLINGS.
 *
 * <p>{@link #derive} is the single source of truth for the verdict, shared by the Job-tab payload
 * and the overhead "!" poll (which only needs {@link #category()}). Its precedence: hard blockers
 * (idle/sleep/banner/pregnant/dusk-social/stamina/strike) win over a stale published value, then a
 * goal's live sub-state is honoured, then workshop/tool checks, else WORKING.
 *
 * <p>Sent to the client as the ordinal in {@code CitizenJobStatePayload}; the client maps it to a
 * colour + the {@code bannerbound.citizen.job.status.<name>} lang key. Ordinal order is the wire
 * contract - append new states at the end, never reorder.
 */
@ApiStatus.Internal
public enum CitizenWorkStatus {
    IDLE,
    WORKING,
    HAULING,
    WAITING,
    NO_TOOL,
    NO_DROPOFF,
    NO_STAMINA,
    BLOCKED,
    STORAGE_FULL,
    PLANTING,
    HARVESTING,
    NEEDS_SAPLINGS,
    AREA_FULL,
    SLEEPING,
    SOCIALIZING,
    BANNER_DOWN,
    ON_STRIKE,
    EXPECTING,
    NO_WORKSHOP,
    NO_ORDERS,
    NEED_MATERIALS,
    NEEDS_SEEDS;

    public static CitizenWorkStatus derive(CitizenEntity citizen, Settlement settlement, boolean anarchy) {
        String jobType = citizen.getJobType() == null ? "" : citizen.getJobType();
        CitizenWorkStatus published = citizen.getCurrentWorkStatus();
        if (jobType.isEmpty()) {
            return IDLE;
        } else if (citizen.isSleeping()) {
            return SLEEPING;
        } else if (!settlement.hasFactionBanner()) {
            return BANNER_DOWN;
        } else if (citizen.isPregnant()) {
            return EXPECTING;
        } else if (!anarchy && WorkGoal.isAfternoonGathering(citizen)) {
            return SOCIALIZING;
        } else if (citizen.isStaminaExhausted()) {
            return NO_STAMINA;
        } else if (!anarchy && WorkGoal.hasRefusalThought(citizen)) {
            return ON_STRIKE;
        } else if (published != IDLE) {
            return published;
        } else if (CrafterWorkGoal.isWorkshopJob(jobType) && citizen.getAssignedWorkshopId() == null) {
            return NO_WORKSHOP;
        } else if (!anarchy && com.bannerbound.core.social.JobIcons.requiresTool(jobType)
                && !citizen.hasJobTool()) {
            // keep the !anarchy guard: anarchy gatherers (and tool-free foragers) must not read NO_TOOL
            return NO_TOOL;
        } else {
            return WORKING;
        }
    }

    public Category category() {
        return switch (this) {
            case WORKING, HAULING, PLANTING, HARVESTING -> Category.GOOD;
            case IDLE, WAITING, AREA_FULL, NO_ORDERS, NEED_MATERIALS,
                 SLEEPING, SOCIALIZING, EXPECTING -> Category.NEUTRAL;
            case NO_TOOL, NO_DROPOFF, NO_STAMINA, BLOCKED, STORAGE_FULL, NEEDS_SAPLINGS, NEEDS_SEEDS,
                 BANNER_DOWN, ON_STRIKE, NO_WORKSHOP -> Category.BLOCKED;
        };
    }

    public enum Category { GOOD, NEUTRAL, BLOCKED }
}
