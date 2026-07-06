package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C->S: an individual member is leaving their settlement (the Town Hall "Leave Settlement" button).
 * Server runs SettlementManager.tryLeave, which removes the player, drops their research access, and
 * collapses the settlement if they were the last member. No fields -- the actor is the
 * IPayloadContext player. The server refuses a seated Chief (CHIEFDOM): a chief must Step Down first
 * and serve the minimum term, mirroring the UI where the chief sees Step Down in this slot instead.
 */
@ApiStatus.Internal
public record LeaveSettlementPayload() implements CustomPacketPayload {
    public static final LeaveSettlementPayload INSTANCE = new LeaveSettlementPayload();

    public static final CustomPacketPayload.Type<LeaveSettlementPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "leave_settlement"));

    public static final StreamCodec<ByteBuf, LeaveSettlementPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
