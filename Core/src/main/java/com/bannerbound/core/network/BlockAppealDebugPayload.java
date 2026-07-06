package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S->C: the diminishing-returns state of the block at {@code pos}, answering a
 * {@link RequestBlockAppealPayload}. {@code queuePosition} is this block's 1-based slot in its type's
 * diminishing-returns queue (0 = not counted: underground, chunk untracked, or pos not in a home's
 * union when {@code inHouse}); when {@code inHouse} the slot is the home's per-type position, else
 * the chunk's. {@code tracked} says whether the chunk has a scanned beauty record at all (irrelevant
 * when {@code inHouse}, since a home's score does not depend on the chunk scan). {@code inHouse} is
 * true iff {@code pos} fell inside a home selection, so the UI shows the home's appeal not the
 * chunk's. {@code appeal} is the fully-resolved value: base appeal under the OWNING settlement's
 * culture styles x diminishing returns for the queue slot, computed server-side so it is
 * viewer-independent - every client sees the same value for the same block, regardless of its own
 * settlement's styles.
 */
@ApiStatus.Internal
public record BlockAppealDebugPayload(BlockPos pos, int queuePosition, boolean tracked,
                                      boolean inHouse, float appeal)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlockAppealDebugPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "block_appeal_debug"));

    public static final StreamCodec<ByteBuf, BlockAppealDebugPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, BlockAppealDebugPayload::pos,
            ByteBufCodecs.VAR_INT, BlockAppealDebugPayload::queuePosition,
            ByteBufCodecs.BOOL, BlockAppealDebugPayload::tracked,
            ByteBufCodecs.BOOL, BlockAppealDebugPayload::inHouse,
            ByteBufCodecs.FLOAT, BlockAppealDebugPayload::appeal,
            BlockAppealDebugPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
