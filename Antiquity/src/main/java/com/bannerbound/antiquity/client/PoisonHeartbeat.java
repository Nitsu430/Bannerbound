package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Oleander's heartbeat: as the cardiac clock runs down (tick(fraction): 0 at infection, 1 at
 * cardiac arrest) the local player hears their own heart thud at an accelerating cadence -- the
 * beat gap lerps from ~26 ticks (a calm ~1.3s) down to a ~6-tick floor (a racing ~0.3s) -- with
 * volume building from silence to full over the countdown and pitch randomised 0.9..1.1 so it is
 * not repetitive. A single {@code oleander_heartbeat} sound is replayed via forUI (non-positional,
 * "in your head"); no per-beat asset is needed. {@link #pulse} exposes a 0..1 envelope decaying
 * over ~5 ticks after each beat so {@link PoisonPostProcessor}'s oleander path can pump the red
 * vignette in time with the thuds. Open: audibly silent until the heartbeat .ogg asset is added.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PoisonHeartbeat {
    private static long lastBeat = Long.MIN_VALUE;
    private static long nextBeat = Long.MIN_VALUE;

    private PoisonHeartbeat() {}

    public static void tick(float fraction) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = mc.level.getGameTime();
        if (nextBeat == Long.MIN_VALUE || now >= nextBeat) {
            lastBeat = now;
            nextBeat = now + beatInterval(fraction);
            playBeat(mc, fraction);
        }
    }

    private static int beatInterval(float fraction) {
        return Math.max(5, Math.round(Mth.lerp(Mth.clamp(fraction, 0.0F, 1.0F), 26.0F, 6.0F)));
    }

    private static void playBeat(Minecraft mc, float fraction) {
        float volume = Mth.clamp(fraction, 0.0F, 1.0F);
        float pitch = 0.9F + mc.level.getRandom().nextFloat() * 0.2F;
        mc.getSoundManager().play(
            SimpleSoundInstance.forUI(BannerboundAntiquity.OLEANDER_HEARTBEAT.get(), pitch, volume));
    }

    public static float pulse(float nowTicks) {
        if (lastBeat == Long.MIN_VALUE) {
            return 0.0F;
        }
        float t = nowTicks - lastBeat;
        if (t < 0.0F || t > 5.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, 1.0F - t / 5.0F);
    }

    public static void reset() {
        lastBeat = Long.MIN_VALUE;
        nextBeat = Long.MIN_VALUE;
    }
}
