package com.bannerbound.core.social;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.network.chat.Component;

/**
 * Resolves the display name of a Foreman's-Rod worker type, which can change with research: the
 * "digger" unit reads as Digger until the Quarry research (FLAG_QUARRY), then as Quarryworker. This is
 * the server-safe path (no client-only references); the client equivalent lives next to the rod UI and
 * reads ClientResearchState instead of a Settlement.
 */
@ApiStatus.Internal
public final class WorkstationNames {
    public static final String DIGGER = "digger";
    public static final String FLAG_QUARRY = "bannerbound.unlock_quarry";

    private WorkstationNames() {
    }

    public static Component dynamic(Settlement settlement, String wsType) {
        if (DIGGER.equals(wsType) && settlement != null
                && ResearchManager.hasFlag(settlement, FLAG_QUARRY)) {
            return Component.translatable("bannerbound.workstation_type.quarryworker");
        }
        return Component.translatable("bannerbound.workstation_type." + wsType);
    }
}
