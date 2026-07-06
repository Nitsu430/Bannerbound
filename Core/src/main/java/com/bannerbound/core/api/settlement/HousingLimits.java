package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;

/**
 * Per-settlement cap on home size: a home's marked region may span at most 2*maxRadius+1 blocks
 * per axis, enforced by Homes.validate as a union-span check (homes have no anchor block to
 * measure a radius from). The limit starts at {@link #BASE_RADIUS} (kept small so Antiquity-era
 * homes are cosy) and grows via research: authoring a "bannerbound.home_radius:N" entry in a
 * research node's unlocks.flags allows half-span N once that node completes; the settlement uses
 * the largest unlocked N, hard-capped at {@link #MAX_RADIUS}. Malformed flag numbers are
 * silently skipped.
 */
public final class HousingLimits {
    public static final int BASE_RADIUS = 5;
    public static final int MAX_RADIUS = 32;

    private static final String RADIUS_FLAG_PREFIX = "bannerbound.home_radius:";

    private HousingLimits() {
    }

    public static int maxRadius(@Nullable Settlement settlement) {
        int max = BASE_RADIUS;
        if (settlement != null) {
            for (String id : settlement.completedResearches()) {
                ResearchDefinition def = ResearchTreeLoader.get(id);
                if (def == null) continue;
                for (String flag : def.unlocksFlags()) {
                    if (flag.startsWith(RADIUS_FLAG_PREFIX)) {
                        try {
                            max = Math.max(max, Integer.parseInt(
                                flag.substring(RADIUS_FLAG_PREFIX.length()).trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        return Math.min(max, MAX_RADIUS);
    }
}
