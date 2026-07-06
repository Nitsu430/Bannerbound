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
 * Client -> server: a carpenter's-table interaction at {@code pos}. {@link #COMPLETE} /
 * {@link #CANCEL} end the saw minigame (session-based; {@code index} unused, -1);
 * {@link #REMOVE_QUEUE} means the player right-clicked the in-world queue item at {@code index}
 * and the server removes that queued output. Add-to-queue and browse-cycle reuse the shared
 * ghost-preview path ({@code GhostActionPayload}), not this payload.
 */
@ApiStatus.Internal
public record CarpentryActionPayload(BlockPos pos, int action, int index) implements CustomPacketPayload {
    public static final int COMPLETE = 0;
    public static final int CANCEL = 1;
    public static final int REMOVE_QUEUE = 2;

    public CarpentryActionPayload(BlockPos pos, int action) {
        this(pos, action, -1);
    }

    public static final CustomPacketPayload.Type<CarpentryActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "carpentry_action"));

    public static final StreamCodec<ByteBuf, CarpentryActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, CarpentryActionPayload::pos,
            ByteBufCodecs.VAR_INT, CarpentryActionPayload::action,
            ByteBufCodecs.VAR_INT, CarpentryActionPayload::index,
            CarpentryActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
