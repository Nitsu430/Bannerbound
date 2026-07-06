package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.WorkshopSummarySyncPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of every workshop's display summary (name, type, status, occupancy), keyed by
 * workshop id. Replaced wholesale on each WorkshopSummarySyncPayload; read by SelectionRenderer for
 * the Workshop Orders rod's floating overview labels. In each Summary an empty customName means "show
 * the derived type name", and appealOrdinal is the workplace-appeal ChunkBeauty ordinal (-1 =
 * unscored). A malformed workshop id skips just that row so a bad entry never drops the whole snapshot.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientWorkshopSummaries {

    public record Summary(String customName, String typeId, int statusOrdinal,
                          int workerCount, int capacity, int appealOrdinal) {
    }

    private static final Map<UUID, Summary> SUMMARIES = new HashMap<>();

    private ClientWorkshopSummaries() {
    }

    public static void replace(WorkshopSummarySyncPayload payload) {
        SUMMARIES.clear();
        for (int i = 0; i < payload.workshopIds().size(); i++) {
            try {
                SUMMARIES.put(UUID.fromString(payload.workshopIds().get(i)), new Summary(
                    payload.customNames().get(i), payload.typeIds().get(i),
                    payload.statusOrdinals().get(i), payload.workerCounts().get(i),
                    payload.capacities().get(i),
                    i < payload.appealOrdinals().size() ? payload.appealOrdinals().get(i) : -1));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Nullable
    public static Summary get(UUID workshopId) {
        return SUMMARIES.get(workshopId);
    }
}
