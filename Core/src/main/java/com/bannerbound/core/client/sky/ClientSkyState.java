package com.bannerbound.core.client.sky;

import com.bannerbound.core.celestial.SkyField;
import com.bannerbound.core.celestial.WorldCalendar;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the world's sky: holds the synced sky seed and the {@link SkyField} generated
 * from it, plus the synced celestialSpeed gamerule (orbital/seasonal time multiplier), the
 * meteorAmount gamerule (~ambient meteors per minute), and the server's {@link WorldCalendar}
 * (month lengths from its config; DEFAULT until synced). set() is called by
 * {@link com.bannerbound.core.network.SkySeedPayload} on login/re-send and only regenerates the
 * SkyField when the seed or year length actually changes, so a bare calendar update doesn't rebuild
 * it. clear() runs on logout so a different server's sky never bleeds across connections.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientSkyState {
    private static SkyField field;
    private static long seed;
    private static boolean hasSeed;
    private static int celestialSpeed = 1;
    private static int meteorAmount = 2;
    private static WorldCalendar calendar = WorldCalendar.DEFAULT;

    private ClientSkyState() {
    }

    public static void set(long newSeed, int speed, int meteors, int[] monthDays) {
        celestialSpeed = speed;
        meteorAmount = meteors;
        WorldCalendar newCalendar = new WorldCalendar(monthDays);
        boolean sameSky = hasSeed && newSeed == seed && field != null
                && newCalendar.yearDays() == calendar.yearDays();
        calendar = newCalendar;
        if (sameSky) return;
        seed = newSeed;
        hasSeed = true;
        field = SkyField.generate(newSeed, newCalendar.yearDays());
    }

    public static WorldCalendar calendar() {
        return calendar;
    }

    @Nullable
    public static SkyField field() {
        return field;
    }

    public static int celestialSpeed() {
        return celestialSpeed;
    }

    public static int meteorAmount() {
        return meteorAmount;
    }

    public static void clear() {
        hasSeed = false;
        field = null;
        celestialSpeed = 1;
        meteorAmount = 2;
        calendar = WorldCalendar.DEFAULT;
    }
}
