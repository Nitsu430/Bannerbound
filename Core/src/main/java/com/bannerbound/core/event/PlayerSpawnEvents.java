package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Settlement-aware respawn fallback: if a player respawns with no bed/anchor set AND belongs to a
 * settlement with a valid town hall (the lit campfire), warp them to that campfire instead of world
 * spawn. Priority chain: vanilla world spawn (default) < this town-hall campfire < bed/anchor
 * (vanilla, which already overrides everything above). Vanilla's bed/anchor logic runs first; this
 * only fires when getRespawnPosition() is null. End-return respawns are left alone -- the player
 * was sent to world spawn intentionally by the End portal flow, not by a death.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class PlayerSpawnEvents {
    private PlayerSpawnEvents() {
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getRespawnPosition() != null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerLevel overworld = server.overworld();
        SettlementData data = SettlementData.get(overworld);
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return;
        BlockPos thp = s.townHallPos();
        if (thp == null) return;

        // +1 Y above the campfire so the player doesn't suffocate inside the block.
        player.teleportTo(overworld, thp.getX() + 0.5, thp.getY() + 1.0, thp.getZ() + 0.5,
            java.util.Set.of(), player.getYRot(), player.getXRot());
    }
}
