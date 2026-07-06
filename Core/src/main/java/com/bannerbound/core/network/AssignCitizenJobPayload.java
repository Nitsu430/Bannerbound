package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C->S: assign (or clear, when typeId is empty) a citizen's job from the Job tab. Server
 *  re-checks management permission and that the type is research-unlocked. */
@ApiStatus.Internal
public record AssignCitizenJobPayload(int entityId, String typeId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AssignCitizenJobPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "assign_citizen_job"));

    public static final StreamCodec<ByteBuf, AssignCitizenJobPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.typeId());
        },
        buf -> new AssignCitizenJobPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
