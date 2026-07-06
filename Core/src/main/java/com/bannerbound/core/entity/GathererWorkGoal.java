package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

/**
 * Category base for gatherers: citizens whose loop is SCAN for a resource -> walk -> harvest ->
 * claim yield into the workstation BE -> spend stamina. Gatherers are self-directed - they scan
 * the world around themselves for targets instead of consuming player-placed orders from
 * BlockSelectionRegistry. Current members: ForesterWorkGoal (logs -> fells trees) and
 * FisherWorkGoal (water bodies -> cast/wait/retract); the planned Forager extends this class,
 * returns its workstation TYPE_ID from workstationTypeId(), and implements the canUse/tick state
 * machine (shared findAssignment/citizen/speedModifier plumbing comes from WorkGoal).
 *
 * Because gatherers are the self-directed roles citizens auto-employ into under anarchy,
 * isAnarchyAutoEligible() returns true so they run willingly there, bypassing the social-window
 * pause and compliance refusal/strike thoughts.
 *
 * This abstract is intentionally thin today; once the Forager lands, common patterns (rescan
 * cooldowns, target-age timeouts, the SCAN/WALK/WORK phase skeleton) can be lifted here.
 */
@ApiStatus.Internal
public abstract class GathererWorkGoal extends WorkGoal {
    protected GathererWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected boolean isAnarchyAutoEligible() {
        return true;
    }
}
