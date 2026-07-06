package com.bannerbound.core.api.faith;

import com.bannerbound.core.chat.BannerboundGameRules;
import com.bannerbound.core.network.SkySeedPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One place that assembles and sends the client sky state - sky seed plus the
 * celestialSpeed/meteorAmount gamerules and the calendar month length. Used on login, on
 * {@code /bannerbound sky reroll}, and from the gamerule change callbacks, so every client
 * renders the same authoritative sky.
 */
public final class SkyStateSync {
    private SkyStateSync() {
    }

    public static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, build(player.getServer()));
    }

    public static void broadcast(MinecraftServer server) {
        if (server == null) return;
        PacketDistributor.sendToAllPlayers(build(server));
    }

    private static SkySeedPayload build(MinecraftServer server) {
        return new SkySeedPayload(
            FaithData.get(server.overworld()).skySeed(),
            server.getGameRules().getInt(BannerboundGameRules.CELESTIAL_SPEED),
            server.getGameRules().getInt(BannerboundGameRules.METEOR_AMOUNT),
            com.bannerbound.core.Config.calendarMonthDays());
    }
}
