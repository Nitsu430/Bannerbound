package com.bannerbound.core.api.settlement;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Claim-protection gate. isProtected blocks an actor from acting on a chunk claimed by a
 * settlement that isn't theirs (delegated to DiplomacyManager.canActInClaim). shouldBypass lets
 * op-level-2+ players ignore protection, but only when opsBypassClaimProtection is enabled in
 * config - it defaults OFF so protection applies to everyone, since a LAN world with cheats makes
 * every player an op and would otherwise disable all grief protection; admins can flip it on for
 * moderation.
 */
public final class ChunkProtection {
    private ChunkProtection() {
    }

    public static boolean isProtected(SettlementData data, ChunkPos chunk, UUID actorId) {
        return !DiplomacyManager.canActInClaim(data, chunk, actorId);
    }

    public static boolean shouldBypass(ServerPlayer player) {
        return com.bannerbound.core.Config.OPS_BYPASS_CLAIM_PROTECTION.get() && player.hasPermissions(2);
    }
}
