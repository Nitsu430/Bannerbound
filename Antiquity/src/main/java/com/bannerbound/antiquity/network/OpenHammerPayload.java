package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: open the cold-hammer minigame at the stone anvil. The server owns the session +
 * the quality roll; the client plays the gravity-drag and replies with {@link HammerActionPayload},
 * echoing the anvil {@code pos} back to match the session. {@code strikes} is the number of
 * drag-and-release strikes the piece needs (one per 50 mB of the casting). {@code canSuperior} says
 * whether the hammer is strong enough for the top tier - else the client's quality preview meter
 * caps at Standard, so the preview reflects the server's hammer-rank gate.
 */
@ApiStatus.Internal
public record OpenHammerPayload(BlockPos pos, int strikes, boolean canSuperior) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenHammerPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_hammer"));

    public static final StreamCodec<ByteBuf, OpenHammerPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenHammerPayload::pos,
            ByteBufCodecs.VAR_INT, OpenHammerPayload::strikes,
            ByteBufCodecs.BOOL, OpenHammerPayload::canSuperior,
            OpenHammerPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
