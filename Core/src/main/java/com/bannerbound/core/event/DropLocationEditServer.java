package com.bannerbound.core.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

/**
 * Server-side registry of which players are currently marking a drop-off location, keyed by player
 * UUID (the client-side {@link com.bannerbound.core.client.DropLocationEditState} only blocks the
 * local interaction). The server tracks it so {@link DropLocationServerGuard} can cancel the
 * block-use on the server thread too - otherwise, in single-player, the integrated server would
 * still open the chest the player right-clicked to mark it. Backed by a ConcurrentHashMap.
 *
 * Each Edit records the target citizen, whether the edit targets the farmer's seed source (seed ==
 * true) vs the harvest drop-off, and the citizen screen to reopen afterwards. getReturnCitizenId
 * returns null for settlement-level edits - signalled by the PREFERRED_STORAGE_TARGET sentinel -
 * so no citizen screen is reopened.
 */
@ApiStatus.Internal
public final class DropLocationEditServer {
    private record Edit(int citizenId, boolean seed, int returnCitizenId) {}

    private static final Map<UUID, Edit> EDITING = new ConcurrentHashMap<>();

    private DropLocationEditServer() {
    }

    public static void begin(UUID player, int citizenEntityId, boolean seed) {
        EDITING.put(player, new Edit(citizenEntityId, seed, citizenEntityId));
    }

    public static boolean isActive(UUID player) {
        return EDITING.containsKey(player);
    }

    public static Integer getCitizenId(UUID player) {
        Edit e = EDITING.get(player);
        return e == null ? null : e.citizenId();
    }

    public static boolean isSeed(UUID player) {
        Edit e = EDITING.get(player);
        return e != null && e.seed();
    }

    public static Integer getReturnCitizenId(UUID player) {
        Edit e = EDITING.get(player);
        if (e == null || e.returnCitizenId() == com.bannerbound.core.network.OpenDropLocationEditPayload.PREFERRED_STORAGE_TARGET) {
            return null;
        }
        return e.returnCitizenId();
    }

    public static void clear(UUID player) {
        EDITING.remove(player);
    }
}
