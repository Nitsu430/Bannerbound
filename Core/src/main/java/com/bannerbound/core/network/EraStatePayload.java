package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S->C: the viewer's era standing - their own {@code playerEra}, the shared {@code worldEra}, and
 * the current {@code worldYear} (BC values are negative, e.g. Antiquity near -100000). worldYear
 * uses plain (non-zig-zag) VAR_INT, so negative years always cost 5 bytes - correct, just not
 * compact.
 */
@ApiStatus.Internal
public record EraStatePayload(int playerEra, int worldEra, int worldYear) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EraStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "era_state"));

    public static final StreamCodec<ByteBuf, EraStatePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EraStatePayload::playerEra,
            ByteBufCodecs.VAR_INT, EraStatePayload::worldEra,
            ByteBufCodecs.VAR_INT, EraStatePayload::worldYear,
            EraStatePayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
