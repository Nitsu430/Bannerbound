package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.network.ClaimEntry;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of chunk claims (packed chunk pos -> {@link ClaimEntry}) for the local
 * player, replaced wholesale by the server sync. Read by the map/territory overlays to color
 * chunks by their owning settlement.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientClaimState {
    private static final Map<Long, ClaimEntry> CLAIMS = new HashMap<>();

    private ClientClaimState() {
    }

    public static void replaceAll(List<ClaimEntry> entries) {
        CLAIMS.clear();
        for (ClaimEntry e : entries) {
            CLAIMS.put(e.chunkPos(), e);
        }
    }

    public static void clear() {
        CLAIMS.clear();
    }

    public static ClaimEntry getEntry(long packedChunkPos) {
        return CLAIMS.get(packedChunkPos);
    }

    public static SettlementColor getColor(long packedChunkPos) {
        ClaimEntry e = CLAIMS.get(packedChunkPos);
        return e == null ? null : SettlementColor.byIndex(e.colorIndex());
    }

    public static Map<Long, ClaimEntry> all() {
        return CLAIMS;
    }
}
