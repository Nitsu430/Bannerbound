package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: a Town Hall "Labor" tab edit - the full new gatherer-job priority order, which
 * jobs are disabled, and the auto-assign flag. The server validates the ids (gatherers only),
 * applies it to the settlement, and re-broadcasts the labor state. orderedJobIds is the chosen
 * priority order; caps is parallel to it (worker cap per job, -1 = no limit); disabledJobIds are the
 * jobs switched off; autoAssign toggles auto-distribution (only editable under a government).
 */
@ApiStatus.Internal
public record ProposeLaborPriorityChangePayload(
    List<String> orderedJobIds,
    List<String> disabledJobIds,
    List<Integer> caps,
    boolean autoAssign
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProposeLaborPriorityChangePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "propose_labor_priority"));

    public static final StreamCodec<ByteBuf, ProposeLaborPriorityChangePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.orderedJobIds().size());
            for (String s : p.orderedJobIds()) ByteBufCodecs.STRING_UTF8.encode(buf, s);
            for (int v : p.caps()) ByteBufCodecs.VAR_INT.encode(buf, v);   // no own length prefix: caps must stay parallel to orderedJobIds
            ByteBufCodecs.VAR_INT.encode(buf, p.disabledJobIds().size());
            for (String s : p.disabledJobIds()) ByteBufCodecs.STRING_UTF8.encode(buf, s);
            ByteBufCodecs.BOOL.encode(buf, p.autoAssign());
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) order.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            List<Integer> caps = new ArrayList<>(n);
            for (int i = 0; i < n; i++) caps.add(ByteBufCodecs.VAR_INT.decode(buf));
            int m = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> disabled = new ArrayList<>(m);
            for (int i = 0; i < m; i++) disabled.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            boolean auto = ByteBufCodecs.BOOL.decode(buf);
            return new ProposeLaborPriorityChangePayload(order, disabled, caps, auto);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
