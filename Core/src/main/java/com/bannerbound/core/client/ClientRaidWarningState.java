package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side hold for whether the player's settlement is currently being raided (see
 * {@code RaidWarningPayload}); read by {@code RaidWarningHudLayer}. {@code clear()} wipes it on
 * disconnect so a stale banner doesn't carry into the next world.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientRaidWarningState {
    private static volatile boolean active = false;

    private ClientRaidWarningState() {}

    public static void set(boolean a) { active = a; }

    public static boolean active() { return active; }

    public static void clear() { active = false; }
}
