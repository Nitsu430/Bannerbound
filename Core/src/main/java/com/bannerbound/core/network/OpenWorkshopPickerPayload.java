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
 * Server -> client: the Job tab assigned a Crafter - pick which workshop the citizen works. One
 * entry per settlement workshop; the client disables rows that are invalid or full. Picking one
 * sends the existing AssignWorkshopWorkerPayload (assign=true), which performs the real assignment
 * (job + binding + roster) server-side. citizenId is a UUID string and all the workshop* / *Names /
 * *Ordinals / *Counts / capacities lists are parallel; customNames "" means the client shows the
 * derived type, and rows whose statusOrdinal (a Workshop.Status ordinal) is non-VALID are disabled.
 */
@ApiStatus.Internal
public record OpenWorkshopPickerPayload(String citizenId, String jobTypeId, List<String> workshopIds,
                                        List<String> customNames, List<String> typeIds,
                                        List<Integer> statusOrdinals, List<Integer> assignedCounts,
                                        List<Integer> capacities)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenWorkshopPickerPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_workshop_picker"));

    public static final StreamCodec<ByteBuf, OpenWorkshopPickerPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.citizenId);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.jobTypeId);
            writeStrings(buf, p.workshopIds);
            writeStrings(buf, p.customNames);
            writeStrings(buf, p.typeIds);
            writeInts(buf, p.statusOrdinals);
            writeInts(buf, p.assignedCounts);
            writeInts(buf, p.capacities);
        },
        buf -> new OpenWorkshopPickerPayload(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            readStrings(buf), readStrings(buf), readStrings(buf),
            readInts(buf), readInts(buf), readInts(buf)));

    public OpenWorkshopPickerPayload(String citizenId, List<String> workshopIds,
                                     List<String> customNames, List<String> typeIds,
                                     List<Integer> statusOrdinals, List<Integer> assignedCounts,
                                     List<Integer> capacities) {
        this(citizenId, com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID,
            workshopIds, customNames, typeIds, statusOrdinals, assignedCounts, capacities);
    }

    private static void writeStrings(ByteBuf buf, List<String> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (String s : list) ByteBufCodecs.STRING_UTF8.encode(buf, s);
    }

    private static List<String> readStrings(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return out;
    }

    private static void writeInts(ByteBuf buf, List<Integer> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (Integer i : list) ByteBufCodecs.VAR_INT.encode(buf, i);
    }

    private static List<Integer> readInts(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.VAR_INT.decode(buf));
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
