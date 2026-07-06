package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Opens the settlement-founding screen. siteWarningMask is a bitmask of SiteWarning values
 * (com.bannerbound.core.api.settlement.SiteWarning) assessed server-side at the founding spot (see
 * SettlementSiteAssessor), so the screen can flag a poor site before the player commits.
 */
@ApiStatus.Internal
public record OpenSettleScreenPayload(int siteWarningMask) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenSettleScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_settle_screen"));

    public static final StreamCodec<ByteBuf, OpenSettleScreenPayload> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(OpenSettleScreenPayload::new, OpenSettleScreenPayload::siteWarningMask);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
