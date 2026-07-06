package com.bannerbound.core.api.farmer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.network.OpenSeedPickerPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Process-wide queue of seed-picker prompts waiting to be delivered, keyed by the player who
 * created the farmer selection. A selection that finishes tilling with no seed assigned is
 * queued here; if the creator is online the popup pushes immediately, otherwise it waits until
 * login and {@code FactionEvents.onPlayerLoggedIn} drains the pending entries. All methods are
 * synchronized because the work goal (server thread) and login handler can both touch PENDING.
 * Not persisted: after a restart the next work-goal tick re-detects "tilled but unseeded"
 * selections and re-queues them.
 */
public final class AwaitingSeedRegistry {
    private static final Map<UUID, Deque<UUID>> PENDING = new HashMap<>();

    private AwaitingSeedRegistry() {
    }

    public static synchronized void queueAndMaybePush(MinecraftServer server, UUID creatorId,
                                                       UUID rodId,
                                                       List<String> candidateSeeds,
                                                       List<String> bonusSeeds) {
        Deque<UUID> q = PENDING.computeIfAbsent(creatorId, k -> new ArrayDeque<>());
        if (!q.contains(rodId)) q.addLast(rodId);
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(creatorId);
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new OpenSeedPickerPayload(rodId, candidateSeeds, bonusSeeds));
        }
    }

    public static synchronized List<UUID> drainFor(UUID creatorId) {
        Deque<UUID> q = PENDING.remove(creatorId);
        if (q == null || q.isEmpty()) return List.of();
        return new ArrayList<>(q);
    }

    public static synchronized boolean isQueued(UUID creatorId, UUID rodId) {
        Deque<UUID> q = PENDING.get(creatorId);
        return q != null && q.contains(rodId);
    }

    public static synchronized void unqueue(UUID rodId) {
        for (Deque<UUID> q : PENDING.values()) {
            q.remove(rodId);
        }
        PENDING.values().removeIf(Deque::isEmpty);
    }
}
