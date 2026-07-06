package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C->S request to cancel the settlement's pending policy change and its confirm votes, sent by the
 * original proposer (or, in a Chiefdom, the chief) clicking Cancel on the pending slot. Fieldless
 * (unit codec, shared INSTANCE); the server clears whatever policy change is currently pending.
 */
@ApiStatus.Internal
public record RetractPolicyChangePayload() implements CustomPacketPayload {
    public static final RetractPolicyChangePayload INSTANCE = new RetractPolicyChangePayload();

    public static final CustomPacketPayload.Type<RetractPolicyChangePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "retract_policy_change"));

    public static final StreamCodec<ByteBuf, RetractPolicyChangePayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
