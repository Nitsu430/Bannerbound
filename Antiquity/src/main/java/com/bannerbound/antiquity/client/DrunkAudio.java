package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Grog drunkenness audio (GROG_PLAN.md Phase 3.5): everything the player hears warps with
 * intoxication, reaching full strength at level FULL_LEVEL. Three layers: (1) pitch wobble -
 * {@link #pitchFactor()} returns a slow sine wobble around a slightly lowered pitch, deeper with
 * intoxication; needs no EFX and is sampled once per sound (MC sounds are mostly short, so they
 * warble). (2) Muffle - an OpenAL low-pass filter on each source's direct path, available on any
 * device with ALC_EXT_EFX, scaled by max(drunk, hangover) so a hangover dulls the world even when
 * sober (hangover() is a strong constant muffle until HANGOVER_UNTIL). (3) Reverb + slapback echo
 * (echo only past half drunk) via auxiliary effect slots - the swimmy DRUNK feel, never applied
 * for hangover; if the context exposes no aux sends these silently no-op while the muffle still
 * applies. applyEfx is called from DrunkSoundMixin as each sound channel is configured, on the
 * render thread where the AL context is current. Shared EFX objects are created lazily on first
 * use; init retries until an AL context exists, then latches - false forever if EFX is absent or
 * Sound Physics Remastered is installed (it owns the OpenAL effect chain; we stand down to pitch
 * wobble only). Fully defensive: any EFX/driver failure just leaves the pitch wobble carrying the
 * effect. Does NOT touch Simple Voice Chat - SVC has its own audio pipeline; slurring voice would
 * need a separate SVC-plugin integration.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class DrunkAudio {
    private static final float FULL_LEVEL = 6.0F;

    private static boolean triedInit;
    private static boolean efxReady;
    private static int lowpass;
    private static int reverbSlot, reverbEffect;
    private static int echoSlot, echoEffect;

    private DrunkAudio() {}

    public static float pitchFactor() {
        int level = level();
        if (level <= 0) {
            return 1.0F;
        }
        float depth = Math.min(0.14F, 0.02F * level);
        float wob = (float) Math.sin((System.nanoTime() / 1.0E9) * 2.2);
        return 1.0F + wob * depth - depth * 0.25F;
    }

    public static void applyEfx(int source) {
        if (!ensure()) {
            return;
        }
        float drunk = Math.min(1.0F, level() / FULL_LEVEL);
        float muffle = Math.max(drunk, hangover());
        if (muffle <= 0.0F) {
            clear(source);
            return;
        }
        try {
            EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAIN, 1.0F);
            EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAINHF, 1.0F - 0.72F * muffle);
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, lowpass);
            AL10.alGetError();

            if (reverbSlot != 0 && drunk > 0.0F) {
                EXTEfx.alAuxiliaryEffectSlotf(reverbSlot, EXTEfx.AL_EFFECTSLOT_GAIN, Math.min(1.0F, 0.5F * drunk));
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, reverbSlot, 0, EXTEfx.AL_FILTER_NULL);
                AL10.alGetError();
            }
            if (echoSlot != 0 && drunk > 0.5F) {
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, echoSlot, 1, EXTEfx.AL_FILTER_NULL);
                AL10.alGetError();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clear(int source) {
        try {
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 1, EXTEfx.AL_FILTER_NULL);
            AL10.alGetError();
        } catch (Throwable ignored) {
        }
    }

    private static boolean ensure() {
        if (triedInit) {
            return efxReady;
        }
        long context = ALC10.alcGetCurrentContext();
        if (context == 0L) {
            return false; // audio not up yet: must NOT set triedInit here, so we retry on the next sound
        }
        triedInit = true;
        if (ModList.get().isLoaded("sound_physics_remastered")) {
            BannerboundAntiquity.LOGGER.info("Sound Physics present — drunk audio uses pitch wobble only.");
            return false;
        }
        try {
            long device = ALC10.alcGetContextsDevice(context);
            if (!ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                BannerboundAntiquity.LOGGER.info("No ALC_EXT_EFX — drunk audio is pitch wobble only.");
                return false;
            }
            lowpass = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(lowpass, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);

            reverbEffect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_TIME, 2.6F);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, 0.32F);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DIFFUSION, 1.0F);
                reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
                EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
            }

            echoEffect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(echoEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_ECHO);
            if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                EXTEfx.alEffectf(echoEffect, EXTEfx.AL_ECHO_DELAY, 0.12F);
                EXTEfx.alEffectf(echoEffect, EXTEfx.AL_ECHO_FEEDBACK, 0.45F);
                echoSlot = EXTEfx.alGenAuxiliaryEffectSlots();
                EXTEfx.alAuxiliaryEffectSloti(echoSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, echoEffect);
            }
            AL10.alGetError(); // not dead code: clears the AL error left when this context lacks aux sends
            efxReady = true;
            BannerboundAntiquity.LOGGER.info("Drunk audio EFX ready (muffle{}{}).",
                reverbSlot != 0 ? " + reverb" : "", echoSlot != 0 ? " + echo" : "");
        } catch (Throwable t) {
            efxReady = false;
            BannerboundAntiquity.LOGGER.warn("Drunk audio EFX init failed; pitch wobble only.", t);
        }
        return efxReady;
    }

    private static int level() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0 : player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
    }

    private static float hangover() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return 0.0F;
        }
        long until = mc.player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
        return until > mc.level.getGameTime() ? 0.85F : 0.0F;
    }
}
