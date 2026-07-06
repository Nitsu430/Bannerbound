package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Recomputes Chiefdom regent state on player login/logout. The regency model: the regent is the
 * least-resented online member while the chief is offline. That flips on every login and logout, so
 * this drives SettlementManager.recomputeRegent from both events, for every Chiefdom in the world
 * (not just ones the logging player belongs to -- the regent depends on every online member's
 * citizen-resentment totals, and even a non-member login can shift those through prior
 * interactions). Cheap: a typical world has few settlements and the recompute is O(citizens +
 * members). ImmigrationManager.tickAll also calls recomputeRegent periodically as a fallback
 * heartbeat for the rare case these events miss (e.g. server-crash recovery).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class RegencyEvents {
    private RegencyEvents() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        recomputeForAllChiefdoms(event.getEntity() instanceof ServerPlayer sp ? sp : null);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        recomputeForAllChiefdoms(event.getEntity() instanceof ServerPlayer sp ? sp : null);
    }

    private static void recomputeForAllChiefdoms(ServerPlayer source) {
        if (source == null) return;
        MinecraftServer server = source.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        SettlementData data = SettlementData.get(overworld);
        for (Settlement s : data.all()) {
            if (s.governmentType() != Settlement.Government.CHIEFDOM) continue;
            SettlementManager.recomputeRegent(server, s);
        }
    }
}
