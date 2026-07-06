package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

/**
 * Category base for ordered workers -- citizens whose targets are player-placed orders stored in
 * BlockSelectionRegistry (committed via the Foreman's Rod). They don't scan the world freely; they
 * iterate the registry's selections owned by their settlement and pick the closest valid block.
 * Current members: DiggerWorkGoal (mines blocks inside selections, gated by tier + Quarry flag) and
 * FarmerWorkGoal (tills/plants/harvests selections per their assigned seed).
 *
 * <p>Intentionally thin today -- shared mechanics like the tried-but-unreachable TTL, the per-block
 * claim registry handoff, and the no-approach progress watchdog are candidates to lift here once
 * both subclasses settle on identical implementations; kept in the leaves for now to preserve the
 * existing per-role tuning.
 */
@ApiStatus.Internal
public abstract class OrderedWorkGoal extends WorkGoal {
    protected OrderedWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }
}
