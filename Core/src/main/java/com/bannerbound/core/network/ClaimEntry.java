package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** One claimed-chunk row in {@link ClaimSyncPayload}: packed chunk pos, settlement color index,
 *  and settlement name (codec caps the name at 64 chars). */
@ApiStatus.Internal
public record ClaimEntry(long chunkPos, int colorIndex, String settlementName) {
    public static final StreamCodec<ByteBuf, ClaimEntry> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_LONG, ClaimEntry::chunkPos,
        ByteBufCodecs.VAR_INT, ClaimEntry::colorIndex,
        ByteBufCodecs.stringUtf8(64), ClaimEntry::settlementName,
        ClaimEntry::new
    );
}
