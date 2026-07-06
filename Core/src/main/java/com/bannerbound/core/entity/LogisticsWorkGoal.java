package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

/**
 * Category base for logistics workers -- citizens who shuttle items between workstations rather
 * than produce or gather (they don't scan resources or consume Foreman's Rod orders; their cycle
 * keys off other workstations' inventory state). Current member: StockerWorkGoal. Intentionally
 * thin until a second logistics role (hauler/courier) lets the shared round-trip skeleton lift up.
 */
@ApiStatus.Internal
public abstract class LogisticsWorkGoal extends WorkGoal {
    protected LogisticsWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }
}
