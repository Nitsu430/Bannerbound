package com.bannerbound.antiquity.client;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side "who is cold-hammering right now" state, so the third-person player model can raise the
 * hammer arm (see {@code PlayerArmRaiseMixin}) - both your own avatar in F5 and other players nearby.
 * The active set is fed two ways: the local player from {@link HammerScreen#MINIGAME_ACTIVE} each
 * client tick (via {@link #tick}), and remote players from a server broadcast ({@code HammerArmPayload})
 * on session start/end via {@link #setActive}. A per-UUID raise value (read by the mixin through
 * {@link #raise}) eases toward 1 while active and back to 0 when it stops, so the arm lifts and settles
 * smoothly; the per-strike vanilla swing animation drives the down-smack.
 */
@OnlyIn(Dist.CLIENT)
public final class HammerArmState {
    private HammerArmState() {}

    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Float> RAISE = new ConcurrentHashMap<>();
    private static final float EASE = 0.22F;

    public static void setActive(UUID id, boolean active) {
        if (active) ACTIVE.add(id);
        else ACTIVE.remove(id);
    }

    public static float raise(UUID id) {
        Float r = RAISE.get(id);
        return r == null ? 0F : r;
    }

    public static void tick(UUID localId, boolean localActive) {
        if (localId != null) setActive(localId, localActive);
        Set<UUID> ids = new HashSet<>(RAISE.keySet());
        ids.addAll(ACTIVE);
        for (UUID id : ids) {
            float cur = raise(id);
            float target = ACTIVE.contains(id) ? 1F : 0F;
            float next = cur + (target - cur) * EASE;
            if (target == 0F && next < 0.01F) RAISE.remove(id);
            else RAISE.put(id, next);
        }
    }
}
