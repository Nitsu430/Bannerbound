package com.bannerbound.antiquity.poison;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;

/**
 * The five biome poisons. Each attacks a different game system rather than being a different number
 * (see POISON_PLAN). WOLFSBANE (mountain) is a pure hunting paralytic: a constant movement slow plus
 * periodic ~0.5s root pulses that pin fleeing prey; lethal only if a player runs out the final stage,
 * and it deals animals no damage at all (toxicToAnimals=false - it only holds them still so the
 * hunter can make the kill; an un-killed animal's dose simply wears off). CURARE (jungle) is the
 * two-phase kidnap/control poison: a brief heavy-slow stun, then the victim passes out - fully
 * immobilised, rendered prone, draggable with fiber rope (players 15s, animals 30s). It is
 * non-lethal, single-stage/non-escalating, deals no damage-over-time, and re-darting refreshes the
 * synced-deadline timeline rather than restarting it. OLEANDER (desert) attacks the healing system:
 * it blocks ALL regeneration and runs a fixed cardiac countdown from infection that kills regardless
 * of stage - single stage, but maxDose() is 5 because each extra food coating accelerates the clock
 * instead of raising a (non-existent) stage; the race is to the cinchona antidote, not surviving
 * chip damage. WATER_HEMLOCK (swamp) is convulsions + contagion (town outbreaks) - not yet
 * implemented. BELLADONNA (forest) is the deliriant: hallucinations / visual warp / false sounds are
 * client-side (driven by the synced poison type), while its server tick makes poisoned mobs bolt and
 * flail so bystanders can see the madness; its lethality comes from the generic DoT / final-stage
 * timeout.
 *
 * <p>Every constant carries a stable string {@code id}; serialization ({@link #CODEC} and
 * {@link PoisonState}'s hand-written wire format) always uses the id, NEVER the ordinal, so adding
 * poisons can't corrupt saves ({@link #fromId} maps ""/unknown to null = not poisoned; no
 * StreamCodec is needed here). The behaviour hooks ({@link #tick}, run server-side every poisoned
 * tick; {@link #onApplied}; {@link #onCleared}, the cure/replace teardown) and the sound cues
 * (hit/heal, played only to the afflicted player; belch = the lethal-stage retch; null = none)
 * default to no-ops so a not-yet-implemented poison still compiles and serializes - the shared
 * lifecycle lives in {@link Poisons}. {@code onApplied}'s freshInfection flag is true only for a
 * brand-new infection (not a stage-escalating re-dose), letting a poison arm a one-shot clock
 * without resetting it on re-hits. When {@code escalates()} is false the stage clock never advances
 * or times out. Below its max (1-based) stage every poison is non-lethal; {@code lethalAtMaxStage}
 * says whether the final stage may actually kill. {@code maxDose()} caps how many times a poison can
 * coat one food item (default: the dose sets the opening stage, so it caps at maxStage).
 * {@code tintColor} is the ARGB tint of the dart's {@code dart_poison_layer} coating overlay.
 */
public enum PoisonType {
    WOLFSBANE("wolfsbane", true, 4, false, 0xFF9A40D0) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.wolfsbaneTick(victim, stage, level);
        }

        @Override
        public void onCleared(LivingEntity victim) {
            Poisons.clearParalysis(victim);
        }

        @Override
        public SoundEvent hitSound() {
            return BannerboundAntiquity.WOLFSBANE_HIT_CLIENT.get();
        }

        @Override
        public SoundEvent healSound() {
            return BannerboundAntiquity.WOLFSBANE_HEAL_CLIENT.get();
        }

        @Override
        public SoundEvent belchSound() {
            return BannerboundAntiquity.WOLFSBANE_BELCH.get();
        }
    },
    CURARE("curare", false, 1, true, 0xFF2E7D32) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.curareTick(victim, level);
        }

        @Override
        public boolean escalates() {
            return false;
        }

        @Override
        public boolean dealsDamageOverTime() {
            return false;
        }

        @Override
        public void onApplied(LivingEntity victim, boolean freshInfection) {
            if (freshInfection) {
                Poisons.startCurareClocks(victim);
            } else {
                Poisons.refreshCurare(victim);
            }
        }

        @Override
        public void onCleared(LivingEntity victim) {
            Poisons.clearCurare(victim);
        }
    },
    OLEANDER("oleander", true, 1, true, 0xFFFF5ECF) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.oleanderTick(victim, stage, level);
        }

        @Override
        public boolean escalates() {
            return false;
        }

        @Override
        public void onApplied(LivingEntity victim, boolean freshInfection) {
            if (freshInfection) {
                Poisons.startOleanderClock(victim);
            } else {
                Poisons.accelerateOleanderClock(victim);
            }
        }

        @Override
        public int maxDose() {
            return 5;
        }

        @Override
        public void onCleared(LivingEntity victim) {
            Poisons.clearOleanderClock(victim);
        }
    },
    WATER_HEMLOCK("water_hemlock", true, 4, true, 0xFFB5C334),
    BELLADONNA("belladonna", true, 4, true, 0xFF3A1556) {
        @Override
        public void tick(LivingEntity victim, int stage, ServerLevel level) {
            Poisons.belladonnaTick(victim, stage, level);
        }

        @Override
        public SoundEvent hitSound() {
            return BannerboundAntiquity.BELLADONNA_HIT_CLIENT.get();
        }

        @Override
        public SoundEvent healSound() {
            return BannerboundAntiquity.BELLADONNA_HEAL_CLIENT.get();
        }
    };

    private final String id;
    private final boolean lethalAtMaxStage;
    private final int maxStage;
    private final boolean toxicToAnimals;
    private final int tintColor;

    PoisonType(String id, boolean lethalAtMaxStage, int maxStage, boolean toxicToAnimals, int tintColor) {
        this.id = id;
        this.lethalAtMaxStage = lethalAtMaxStage;
        this.maxStage = maxStage;
        this.toxicToAnimals = toxicToAnimals;
        this.tintColor = tintColor;
    }

    public String id() {
        return id;
    }

    public int tintColor() {
        return tintColor;
    }

    public int maxStage() {
        return maxStage;
    }

    public boolean escalates() {
        return true;
    }

    public int maxDose() {
        return maxStage();
    }

    public boolean dealsDamageOverTime() {
        return true;
    }

    public boolean toxicToAnimals() {
        return toxicToAnimals;
    }

    public SoundEvent hitSound() {
        return null;
    }

    public SoundEvent healSound() {
        return null;
    }

    public SoundEvent belchSound() {
        return null;
    }

    public boolean lethalAtMaxStage() {
        return lethalAtMaxStage;
    }

    public void tick(LivingEntity victim, int stage, ServerLevel level) {
    }

    public void onApplied(LivingEntity victim, boolean freshInfection) {
    }

    public void onCleared(LivingEntity victim) {
    }

    @Nullable
    public static PoisonType fromId(String id) {
        if (id != null && !id.isEmpty()) {
            for (PoisonType t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return null;
    }

    private static String toId(@Nullable PoisonType t) {
        return t == null ? "" : t.id;
    }

    // Serialized by string id, NOT ordinal - adding a poison must never shift existing saves.
    public static final Codec<PoisonType> CODEC = Codec.STRING.xmap(PoisonType::fromId, PoisonType::toId);
}
