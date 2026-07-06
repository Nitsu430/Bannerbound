package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.PolicyRegistry;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Effect hook of the {@link PolicyRegistry#PROSPECTING_QUARRY Prospecting Quarry} policy:
 * quarryworkers mining NATURAL stone have a small chance (CHANCE = 0.02) to turn up common raw
 * ore. This is the scarcity floor for ore-poor starts (MINER_PLAN.md phase 2) -- it un-softlocks
 * a civ with no ore chunks, but is deliberately worse than trading or working a deposit, so trade
 * leverage survives. The tooltip says "small chance" and never the number.
 *
 * <p>The percentage is NOT the real defense against farming -- the per-settlement daily cap
 * (DAILY_CAP = 8 per Minecraft day) is: even a cobblestone-generator + repeat-dig-order farm can't
 * beat a cap. Only stone-tier (pickaxe) mining of {@code BASE_STONE_OVERWORLD}, which excludes
 * cobblestone and player-processed blocks, qualifies -- closing the cheap generator path on top.
 * Common ores only -- iron must NEVER leak through prospecting or it punches a hole in
 * perception-gating. Tin joins the pool only once the civ knows it (SettlementDropFilter gate);
 * before that copper carries the whole roll rather than half the hits fizzling.
 *
 * <p>today() keys the cap on getDayTime()/24000 (not gameTime) so sleeping through the night rolls
 * the counter over too. The daily counter is transient (resets on server restart -- worth at most
 * one extra cap of ore, not worth Settlement NBT wiring). FOUND_TODAY maps settlementId to
 * {dayStamp, found-today}.
 */
@ApiStatus.Internal
public final class ProspectingQuarry {
    private static final float CHANCE = 0.02f;
    private static final int DAILY_CAP = 8;
    private static final ResourceLocation RAW_TIN_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "raw_tin");

    private static final Map<UUID, long[]> FOUND_TODAY = new ConcurrentHashMap<>();

    private ProspectingQuarry() {
    }

    public static ItemStack tryBonus(ServerLevel sl, Settlement settlement, BlockState mined,
                                     String toolRole) {
        if (settlement == null || !settlement.hasPolicy(PolicyRegistry.PROSPECTING_QUARRY)) {
            return ItemStack.EMPTY;
        }
        if (!"pickaxe".equals(toolRole)) return ItemStack.EMPTY;
        if (!mined.is(BlockTags.BASE_STONE_OVERWORLD)) return ItemStack.EMPTY;
        if (!underDailyCap(sl, settlement)) return ItemStack.EMPTY;
        if (sl.random.nextFloat() >= CHANCE) return ItemStack.EMPTY;

        List<Item> pool = new ArrayList<>(2);
        addIfKnown(pool, settlement, Items.RAW_COPPER);
        addIfKnown(pool, settlement, BuiltInRegistries.ITEM.getOptional(RAW_TIN_ID).orElse(null));
        if (pool.isEmpty()) return ItemStack.EMPTY;

        spend(sl, settlement);
        return new ItemStack(pool.get(sl.random.nextInt(pool.size())));
    }

    private static void addIfKnown(List<Item> pool, Settlement settlement, Item item) {
        if (item == null) return;
        if (SettlementDropFilter.shouldDrop(settlement, null, new ItemStack(item))) pool.add(item);
    }

    private static long today(ServerLevel sl) {
        return sl.getDayTime() / 24_000L;
    }

    private static boolean underDailyCap(ServerLevel sl, Settlement settlement) {
        long[] e = FOUND_TODAY.get(settlement.id());
        return e == null || e[0] != today(sl) || e[1] < DAILY_CAP;
    }

    private static void spend(ServerLevel sl, Settlement settlement) {
        long day = today(sl);
        FOUND_TODAY.compute(settlement.id(), (id, e) ->
            (e == null || e[0] != day) ? new long[]{day, 1} : new long[]{day, e[1] + 1});
    }
}
