package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C->S: assign a citizen to a workstation. Both pickers -- the worker-picker (from the
 * workstation GUI) and the workstation-picker (from the citizen GUI) -- resolve to the same
 * server op: link citizenId to workstationPos. A zero-bits UUID means "unassign whoever is
 * currently working this station".
 */
@ApiStatus.Internal
public record AssignCitizenToWorkstationPayload(BlockPos workstationPos, UUID citizenId)
    implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AssignCitizenToWorkstationPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "assign_workstation"));

    public static final StreamCodec<ByteBuf, AssignCitizenToWorkstationPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeInt(p.workstationPos().getX());
            buf.writeInt(p.workstationPos().getY());
            buf.writeInt(p.workstationPos().getZ());
            buf.writeLong(p.citizenId().getMostSignificantBits());
            buf.writeLong(p.citizenId().getLeastSignificantBits());
        },
        buf -> new AssignCitizenToWorkstationPayload(
            new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()),
            new UUID(buf.readLong(), buf.readLong())
        )
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
