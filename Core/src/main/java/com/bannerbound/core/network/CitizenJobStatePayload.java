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
 * S->C: the full state the citizen Job tab needs. Sent once right after {@link OpenCitizenScreenPayload}
 * (so the tab has data on open) and again on each live-state poll (so it stays fresh while another
 * player edits the same citizen). Most fields are self-describing; the load-bearing semantics:
 *
 * <p>Icons ride as item registry ids (0 = none) so the client renders the settlement's current
 * tool-age tool without knowing the age. jobIconItemId / unlockedJobIconItemIds run parallel to the
 * job-id lists; allowedToolItemIds / allowedPickaxeItemIds are the items each slot accepts (current
 * age or lower). pickaxeUnlocked / foresterPlantationUnlocked mirror research gates (Quarry,
 * Silviculture) that add a second slot or an area-select button.
 *
 * <p>Forager: forageEnabledBits is the player's on switches, forageUnlockedBits the researched
 * categories (the rest render LOCKED). Hunter: only hunterPreyOffIds (the exclusions) travel - the
 * full species list is the {@code #bannerbound:huntable} tag the client reads locally. Anarchy: with
 * no government the tab shows the auto-assigned gatherer job with a "request switch" control instead
 * of free assign/unassign; switchRefused greys that control while a NO_WORK_AS_JOB thought is active
 * so requests can't be spammed. Crafter: workshopId/Name/TypeId bind a workshop ("" = none). jobXp is
 * the whole-number XP for the current profession bucket (workshop profession for crafters, else the
 * job id). Stocker: the four stockerTask* lists are parallel rows in queue order; stockerTaskStates
 * is 0 = open, 1 = claimed by another stocker, 2 = this citizen's current haul; a "" dest = the
 * stockpile (client renders the translatable label). outpostManaged means the work site is an outpost
 * claim whose storage is auto-decided (nearest chest in its chunk), so "Set drop location" is greyed.
 * workStatus is a {@link com.bannerbound.core.entity.CitizenWorkStatus} ordinal - the glanceable live
 * verdict headline.
 */
@ApiStatus.Internal
public record CitizenJobStatePayload(
    int entityId,
    boolean canManageJobs,
    String jobTypeId,
    int jobIconItemId,
    boolean hasTool,
    int toolItemId,
    String preferredLogId,
    boolean dropOffSet,
    List<String> unlockedJobTypeIds,
    List<Integer> unlockedJobIconItemIds,
    List<Integer> allowedToolItemIds,
    boolean pickaxeUnlocked,
    boolean hasPickaxe,
    int pickaxeItemId,
    List<Integer> allowedPickaxeItemIds,
    boolean seedSourceSet,
    int forageEnabledBits,
    int forageUnlockedBits,
    List<String> hunterPreyOffIds,
    List<Integer> seedCacheItemIds,
    List<Integer> seedCacheCounts,
    boolean anarchy,
    boolean foresterKeepExtras,
    boolean jobPinned,
    boolean switchRefused,
    String workshopId,
    String workshopName,
    String workshopTypeId,
    int jobXp,
    List<Integer> stockerTaskItemIds,
    List<Integer> stockerTaskCounts,
    List<String> stockerTaskDests,
    List<Integer> stockerTaskStates,
    boolean outpostManaged,
    int workStatus,
    boolean foresterPlantationUnlocked,
    boolean tradingCourier
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CitizenJobStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "citizen_job_state"));

    public static final StreamCodec<ByteBuf, CitizenJobStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.BOOL.encode(buf, p.canManageJobs());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.jobTypeId());
            ByteBufCodecs.VAR_INT.encode(buf, p.jobIconItemId());
            ByteBufCodecs.BOOL.encode(buf, p.hasTool());
            ByteBufCodecs.VAR_INT.encode(buf, p.toolItemId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.preferredLogId());
            ByteBufCodecs.BOOL.encode(buf, p.dropOffSet());
            encodeStrings(buf, p.unlockedJobTypeIds());
            encodeInts(buf, p.unlockedJobIconItemIds());
            encodeInts(buf, p.allowedToolItemIds());
            ByteBufCodecs.BOOL.encode(buf, p.pickaxeUnlocked());
            ByteBufCodecs.BOOL.encode(buf, p.hasPickaxe());
            ByteBufCodecs.VAR_INT.encode(buf, p.pickaxeItemId());
            encodeInts(buf, p.allowedPickaxeItemIds());
            ByteBufCodecs.BOOL.encode(buf, p.seedSourceSet());
            ByteBufCodecs.VAR_INT.encode(buf, p.forageEnabledBits());
            ByteBufCodecs.VAR_INT.encode(buf, p.forageUnlockedBits());
            encodeStrings(buf, p.hunterPreyOffIds());
            encodeInts(buf, p.seedCacheItemIds());
            encodeInts(buf, p.seedCacheCounts());
            ByteBufCodecs.BOOL.encode(buf, p.anarchy());
            ByteBufCodecs.BOOL.encode(buf, p.foresterKeepExtras());
            ByteBufCodecs.BOOL.encode(buf, p.jobPinned());
            ByteBufCodecs.BOOL.encode(buf, p.switchRefused());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.workshopId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.workshopName());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.workshopTypeId());
            ByteBufCodecs.VAR_INT.encode(buf, p.jobXp());
            encodeInts(buf, p.stockerTaskItemIds());
            encodeInts(buf, p.stockerTaskCounts());
            encodeStrings(buf, p.stockerTaskDests());
            encodeInts(buf, p.stockerTaskStates());
            ByteBufCodecs.BOOL.encode(buf, p.outpostManaged());
            ByteBufCodecs.VAR_INT.encode(buf, p.workStatus());
            ByteBufCodecs.BOOL.encode(buf, p.foresterPlantationUnlocked());
            ByteBufCodecs.BOOL.encode(buf, p.tradingCourier());
        },
        buf -> new CitizenJobStatePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            decodeStrings(buf),
            decodeInts(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            decodeStrings(buf),
            decodeInts(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            decodeInts(buf),
            decodeInts(buf),
            decodeStrings(buf),
            decodeInts(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    private static void encodeStrings(ByteBuf buf, List<String> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (String s : list) ByteBufCodecs.STRING_UTF8.encode(buf, s);
    }

    private static List<String> decodeStrings(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return out;
    }

    private static void encodeInts(ByteBuf buf, List<Integer> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (int v : list) ByteBufCodecs.VAR_INT.encode(buf, v);
    }

    private static List<Integer> decodeInts(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.VAR_INT.decode(buf));
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
