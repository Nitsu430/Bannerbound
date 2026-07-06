package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Strips drops a civ doesn't recognize yet - kill a sheep before you know wool and no wool drops;
 * break grass before you know seeds and no seeds drop. The decision is delegated to
 * {@link SettlementDropFilter}: automatic known-set gating plus the drop_overrides exceptions.
 * Both handlers also feed {@code InsightManager.recordEvent} (mine_block / kill_entity) as a side
 * effect of the same drop pass.
 *
 * Both drop handlers run at {@link EventPriority#LOW} so they filter the FINAL drop set - after
 * OreBreakHandler has applied any ore-disguise swap and after HuntingEvents has added its bone
 * bootstrap (bones survive via an always_drop override). Worker citizens that harvest with
 * Block.getDrops bypass these events entirely and apply the same filter at their own collection
 * sites.
 *
 * Multi-block plant gotcha (bamboo, scaffolding, cactus, sugar cane, kelp): the upper blocks
 * self-break via neighbor updates the SAME tick the player breaks the support, and those cascade
 * breaks carry no breaker, so settlementOf(null) is null and every non-starting drop would be
 * stripped (only the player-broken block survives). We remember the breaking player's settlement
 * and let breaker-less breaks in the same game tick inherit it. Order-critical: the cascade drop
 * events fire DURING removeBlock, BEFORE the player's own BlockDropsEvent, so the player must be
 * recorded at BlockEvent.BreakEvent (start of break, before removal) - hence HIGHEST priority on
 * onBlockBreakStart - not at BlockDropsEvent, or the cascade would run before anything is recorded.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class DropGatingEvents {
    private DropGatingEvents() {
    }

    private static Settlement lastBreakSettlement;
    private static long lastBreakTick = Long.MIN_VALUE;

    // HIGHEST + recorded before block removal so same-tick cascade self-breaks can inherit it.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreakStart(BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        Level level = event.getPlayer().level();
        if (level.isClientSide()) {
            return;
        }
        lastBreakSettlement = SettlementDropFilter.settlementOf(event.getPlayer());
        lastBreakTick = level.getGameTime();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBlockDrops(BlockDropsEvent event) {
        Level level = event.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        Entity breaker = event.getBreaker();
        Settlement settlement = SettlementDropFilter.settlementOf(breaker);
        long tick = level.getGameTime();
        if (breaker != null) {
            lastBreakSettlement = settlement;
            lastBreakTick = tick;
        } else if (settlement == null && tick == lastBreakTick) {
            // Breaker-less cascade this tick: inherit the recorded player's settlement.
            settlement = lastBreakSettlement;
        }
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            level.getServer(), settlement, "mine_block",
            com.bannerbound.core.api.research.InsightManager.matcherFor(event.getState().getBlock()), 1);
        ResourceLocation sourceId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock());
        SettlementDropFilter.filterEntities(settlement, sourceId, event.getDrops());
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        Entity killer = event.getSource() == null ? null : event.getSource().getEntity();
        Settlement settlement = SettlementDropFilter.settlementOf(killer);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            event.getEntity().getServer(), settlement, "kill_entity",
            com.bannerbound.core.api.research.InsightManager.matcherFor(event.getEntity().getType()), 1);
        ResourceLocation sourceId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
        SettlementDropFilter.filterEntities(settlement, sourceId, event.getDrops());
    }
}
