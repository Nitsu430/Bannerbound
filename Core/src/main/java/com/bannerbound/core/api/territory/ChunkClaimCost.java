package com.bannerbound.core.api.territory;

import com.bannerbound.core.api.settlement.Settlement;

import java.util.List;

import net.minecraft.world.item.Item;

/**
 * One tier of the chunk-claim expansion cost ladder: a population requirement plus item stacks
 * that must all be present in the claiming player's inventory and are consumed atomically on claim.
 * Loaded from data/bannerbound/chunk_claim_costs/<era>.json, one tier per expansion the settlement
 * has made in that era. Nested ItemCost is one item x count entry.
 */
public record ChunkClaimCost(int populationRequired, List<ItemCost> items) {

    public record ItemCost(Item item, int count) {}
}
