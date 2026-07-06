package com.bannerbound.core.network;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C->S: move a citizen into (assign=true) or out of (assign=false) home homeId. On assign the
 * server drops any prior residency first, since a citizen lives in exactly one home at a time;
 * unassign is a no-op if they were not a resident here. fromHousePanel picks which screen the
 * server refreshes in place afterwards: false re-sends HomeCitizenListPayload (the resident
 * picker), true re-sends OpenHouseStatusPayload (the House status panel's inline unassign).
 */
@ApiStatus.Internal
public record AssignCitizenToHomePayload(UUID homeId, UUID citizenId, boolean assign, boolean fromHousePanel)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AssignCitizenToHomePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "assign_citizen_to_home"));

    public static final StreamCodec<ByteBuf, AssignCitizenToHomePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.homeId().getMostSignificantBits());
            buf.writeLong(p.homeId().getLeastSignificantBits());
            buf.writeLong(p.citizenId().getMostSignificantBits());
            buf.writeLong(p.citizenId().getLeastSignificantBits());
            buf.writeBoolean(p.assign());
            buf.writeBoolean(p.fromHousePanel());
        },
        buf -> new AssignCitizenToHomePayload(
            new UUID(buf.readLong(), buf.readLong()),
            new UUID(buf.readLong(), buf.readLong()),
            buf.readBoolean(),
            buf.readBoolean()
        )
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
