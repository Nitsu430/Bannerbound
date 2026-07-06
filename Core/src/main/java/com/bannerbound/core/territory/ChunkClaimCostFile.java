package com.bannerbound.core.territory;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.territory.ChunkClaimCost;
import com.bannerbound.core.api.territory.data.ChunkClaimCostLoader;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/**
 * One parsed cost file (keyed in ChunkClaimCostLoader by era id: "antiquity", "medieval", ...):
 * the per-era expansion cap (maxExpansions) plus the default and per-biome cost-tier ladders. Each
 * ladder is indexed by the within-era expansion number (0 = this era's first expansion), and
 * TerritoryService resolves a settlement's global expansion count onto the right era's ladder.
 * tiersFor returns the biome override when present (biomeTiers takes priority), else defaultTiers --
 * never null, since the loader guarantees defaults exist.
 */
@ApiStatus.Internal
public record ChunkClaimCostFile(
        String era,
        int maxExpansions,
        List<ChunkClaimCost> defaultTiers,
        Map<ResourceLocation, List<ChunkClaimCost>> biomeTiers) {

    public List<ChunkClaimCost> tiersFor(ResourceLocation biome) {
        if (biome != null) {
            List<ChunkClaimCost> override = biomeTiers.get(biome);
            if (override != null) return override;
        }
        return defaultTiers;
    }
}
