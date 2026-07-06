package com.bannerbound.core.citystate;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.FactionBanner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Capture condition for city-state war: breaking a city-state's standard during an active war with your
 * settlement captures it (CITY_STATES plan). Keys off CityStateData.bannerAt so a city-state banner is
 * never confused with a settlement or camp banner. When the manager accepts the break it cancels the
 * event and removes the block WITHOUT drops -- the carryable standard item it spawns replaces the
 * vanilla banner drop, so this prevents a duplicate banner.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CityStateWarEvents {
    private CityStateWarEvents() {
    }

    @SubscribeEvent
    public static void onBannerBroken(BlockEvent.BreakEvent event) {
        if (!CityStateManager.enabled()) return;
        if (!(event.getLevel() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return;
        if (!FactionBanner.isBanner(event.getState())) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (CityStateWarManager.onBannerBroken(sl, player, event.getPos())) {
            event.setCanceled(true);
            sl.removeBlock(event.getPos(), false);
        }
    }
}
