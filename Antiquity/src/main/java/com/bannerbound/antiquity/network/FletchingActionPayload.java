package com.bannerbound.antiquity.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: a step in the active fletching minigame. {@code pos} is the station block,
 * echoed back so the server can match its authoritative session. COMMIT fires on the FIRST stretch
 * release and makes the server consume the station's pile - the commitment point; cancelling after
 * this forfeits the inputs. COMPLETE fires after the last stretch with the per-stretch 0-100 scores
 * in {@code scores} (empty for other actions); the server aggregates them, rolls the quality tier,
 * and pops the finished item. CANCEL means the player aborted (Escape): a committed session's
 * inputs are already gone, otherwise the untouched pile stays on the station.
 */
@ApiStatus.Internal
public record FletchingActionPayload(BlockPos pos, int action, List<Integer> scores)
        implements CustomPacketPayload {
    public static final int COMMIT = 0;
    public static final int COMPLETE = 1;
    public static final int CANCEL = 2;

    public static final CustomPacketPayload.Type<FletchingActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "fletching_action"));

    public static final StreamCodec<ByteBuf, FletchingActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, FletchingActionPayload::pos,
            ByteBufCodecs.VAR_INT, FletchingActionPayload::action,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), FletchingActionPayload::scores,
            FletchingActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
