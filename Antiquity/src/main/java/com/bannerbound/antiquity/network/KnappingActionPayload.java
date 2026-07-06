package com.bannerbound.antiquity.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: a step in the active knapping session. COMMIT fires on the FIRST chip and makes
 * the server consume one rock - the commitment point; abandoning after this forfeits the rock.
 * COMPLETE fires after the timing minigame with the shaped head's id in {@code head} and the
 * per-rep 0-100 scores in {@code scores}; the server rolls the quality tier and gives the head.
 * BROKE means the player chipped every cell away and wasted the (already consumed) rock; the server
 * just ends the session. CANCEL aborts (Escape) before completing - if COMMIT had fired the rock is
 * forfeit, otherwise nothing was consumed. {@code head} is meaningful only for COMPLETE; other
 * actions send the {@link #NONE} placeholder sentinel.
 */
@ApiStatus.Internal
public record KnappingActionPayload(int action, ResourceLocation head, List<Integer> scores)
        implements CustomPacketPayload {
    public static final int COMMIT = 0;
    public static final int COMPLETE = 1;
    public static final int BROKE = 2;
    public static final int CANCEL = 3;

    public static final ResourceLocation NONE =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "none");

    public static final CustomPacketPayload.Type<KnappingActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "knapping_action"));

    public static final StreamCodec<ByteBuf, KnappingActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KnappingActionPayload::action,
            ResourceLocation.STREAM_CODEC, KnappingActionPayload::head,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), KnappingActionPayload::scores,
            KnappingActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
