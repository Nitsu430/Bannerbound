package com.bannerbound.antiquity.poison;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import com.bannerbound.antiquity.event.PoisonEvents;

/**
 * The poison "spine" - the {@code HuntingFear}-style helper every poison reads and writes, backed by
 * the synced {@code POISON_STATE} attachment and ticked for every poisoned {@link LivingEntity} by
 * {@code PoisonEvents}. The shared lifecycle (apply, escalation, damage-over-time, cure) lives here;
 * each poison's signature behaviour is its per-constant {@link PoisonType#tick}.
 *
 * <p>Application: a second dart of the SAME poison escalates one stage immediately; a different
 * poison tears the old one down (onCleared) and takes over at stage 1. {@link #applyPoisonAtStage}
 * is the poisoned-food path - the dose (number of coatings) sets the opening stage, and each extra
 * dose replays onApplied(false) (a no-op for staged poisons, a clock acceleration for oleander).
 * The causedBy overloads record WHO administered the dose so the eventual poison death still credits
 * their settlement (DropGatingEvents kill-credit resolution plus the "succumbed to poison whilst
 * fighting" death line); a null causedBy deliberately CLEARS any previous inflictor so an
 * unattributed re-dose can't keep crediting the old attacker. Escalation: when a stage's deadline
 * passes the stage climbs; past the final stage the deadline IS the moment of death - players die by
 * running out the final stage (their DoT is multiplied way down and clamped above
 * POISON_NONLETHAL_FLOOR, never lethal), while a non-lethal timeout (wolfsbane on an animal) simply
 * wears off, since it only ever held the creature still for the hunter. A non-escalating poison gets
 * stageEndsAt = Long.MAX_VALUE. {@link #cure(LivingEntity, PoisonType)} is the cross-biome cure
 * cycle (each remedy treats exactly one poison, e.g. yarrow -> wolfsbane); curing curare grants
 * brief immunity (POISON_CURARE_IMMUNE_UNTIL) so a kidnapper can't instantly re-dart a freed victim.
 *
 * <p>Signature mechanics: wolfsbane pins the victim in ~0.5s root-pulse windows whose gap tightens
 * per stage (PULSE_* constants, in ticks: every ~3s at stage 1 down to ~1.5s), staggers a visible
 * leg-buckle stumble, and from stage 2 rolls a jump-lock per JUMP_WINDOW_TICKS window -
 * deterministic within a window (no flicker) but unpredictable across windows, because the
 * uncertainty is scarier than a flat lock; the final stage adds blood vomits every 1200-2400t,
 * scheduled per-victim. Oleander's one-shot cardiac clock lives in the SYNCED POISON_CARDIAC_AT
 * attachment (the client drives countdown visuals from it); re-doses never reset it but cut the
 * REMAINING time to OLEANDER_DOSE_KEEP of itself (floor OLEANDER_MIN_TICKS), blood coughs cost extra
 * health on a cadence tightening from 40-60s toward 15s, and {@link #blocksHealing} drives
 * PoisonEvents' LivingHealEvent cancel that blocks ALL healing. Curare's stun -> unconscious ->
 * recover deadlines are synced, so {@link #isCurareUnconscious} works on both sides; a re-dose on an
 * already-unconscious victim EXTENDS the wake deadline (restarting the stun phase would briefly wake
 * them), capped at CURARE_MAX_OUT_MULT x the base out-duration since the faint - holding someone
 * under forever means letting them wake and landing a fresh dart each cycle, not dart spam. Curing
 * curare also releases both ends of any kidnap-drag rope link. Belladonna's server tick only affects
 * mobs (erratic bolting/flailing others can see); a poisoned player's hallucinations are
 * client-side. Paralysis is a shared pair of transient ADD_MULTIPLIED_BASE attribute modifiers
 * (-1.0 = fully rooted, idempotent); transient modifiers vanish on death/relog, so
 * {@link #clearParalysis} only matters for a live cure.
 *
 * <p>{@link #POISON_STAGE_TAG} is the cross-mod bridge: Core's CitizenEntity cannot import this
 * Antiquity attachment, so setState mirrors the 1-based stage (0 = not poisoned) into shared
 * persistent data for its thought / stamina / name-tag glyph - the same bridge pattern as
 * HuntingFear's DOMESTICATED_TAG.
 */
public final class Poisons {
    private Poisons() {}

    public static final String POISON_STAGE_TAG = "BannerboundPoisonStage";

    private static final int PULSE_LENGTH = 10;
    private static final int PULSE_BASE_PERIOD = 60;
    private static final int PULSE_PERIOD_STEP = 10;
    private static final int PULSE_MIN_PERIOD = 30;
    private static final int JUMP_WINDOW_TICKS = 30;

    private static final ResourceLocation PARALYSIS_SPEED_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "poison_paralysis_speed");
    private static final ResourceLocation PARALYSIS_JUMP_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "poison_paralysis_jump");

    public static boolean isPoisoned(LivingEntity e) {
        return e.getData(BannerboundAntiquity.POISON_STATE.get()).active();
    }

    public static PoisonState getPoison(LivingEntity e) {
        return e.getData(BannerboundAntiquity.POISON_STATE.get());
    }

    public static void applyPoison(LivingEntity victim, PoisonType type) {
        applyPoison(victim, type, null);
    }

    public static void applyPoison(LivingEntity victim, PoisonType type, @Nullable Entity causedBy) {
        if (!Config.POISON_ENABLED.get() || type == null) {
            return;
        }
        if (type == PoisonType.CURARE && isCurareImmune(victim)) {
            return;
        }
        PoisonState cur = getPoison(victim);
        if (cur.type() != null && cur.type() != type) {
            cur.type().onCleared(victim);
        }
        boolean freshInfection = !(cur.active() && cur.type() == type);
        int stage = freshInfection ? 1 : Math.min(type.maxStage(), cur.stage() + 1);
        long stageEndsAt = type.escalates()
            ? victim.level().getGameTime() + Config.POISON_STAGE_ADVANCE_TICKS.get()
            : Long.MAX_VALUE;
        setState(victim, new PoisonState(type, stage, stageEndsAt));
        setPoisonedBy(victim, causedBy);
        type.onApplied(victim, freshInfection);
        if (victim instanceof ServerPlayer player && type.hitSound() != null) {
            player.playNotifySound(type.hitSound(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    public static void applyPoisonAtStage(LivingEntity victim, PoisonType type, int startStage) {
        applyPoisonAtStage(victim, type, startStage, null);
    }

    public static void applyPoisonAtStage(LivingEntity victim, PoisonType type, int startStage,
            @Nullable Entity causedBy) {
        if (!Config.POISON_ENABLED.get() || type == null) {
            return;
        }
        if (type == PoisonType.CURARE && isCurareImmune(victim)) {
            return;
        }
        PoisonState cur = getPoison(victim);
        if (cur.type() != null && cur.type() != type) {
            cur.type().onCleared(victim);
        }
        boolean freshInfection = !(cur.active() && cur.type() == type);
        int stage = Math.max(1, Math.min(type.maxStage(), startStage));
        long stageEndsAt = type.escalates()
            ? victim.level().getGameTime() + Config.POISON_STAGE_ADVANCE_TICKS.get()
            : Long.MAX_VALUE;
        setState(victim, new PoisonState(type, stage, stageEndsAt));
        setPoisonedBy(victim, causedBy);
        type.onApplied(victim, freshInfection);
        for (int i = 1; i < startStage; i++) {
            type.onApplied(victim, false);
        }
        if (victim instanceof ServerPlayer player && type.hitSound() != null) {
            player.playNotifySound(type.hitSound(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    public static void cure(LivingEntity victim, PoisonType onlyType) {
        if (getPoison(victim).type() == onlyType) {
            cure(victim);
            if (onlyType == PoisonType.CURARE && Config.POISON_CURARE_IMMUNE_TICKS.get() > 0) {
                victim.setData(BannerboundAntiquity.POISON_CURARE_IMMUNE_UNTIL.get(),
                    victim.level().getGameTime() + Config.POISON_CURARE_IMMUNE_TICKS.get());
            }
        }
    }

    private static boolean isCurareImmune(LivingEntity victim) {
        return victim.level().getGameTime() < victim.getData(BannerboundAntiquity.POISON_CURARE_IMMUNE_UNTIL.get());
    }

    public static void cure(LivingEntity victim) {
        PoisonState cur = getPoison(victim);
        if (cur.type() != null) {
            cur.type().onCleared(victim);
            if (victim instanceof ServerPlayer player && cur.type().healSound() != null) {
                player.playNotifySound(cur.type().healSound(), SoundSource.PLAYERS, 1.0F, 1.0F);
            }
        }
        setState(victim, PoisonState.NONE);
        victim.removeData(BannerboundAntiquity.POISON_BY.get());
    }

    private static void setPoisonedBy(LivingEntity victim, @Nullable Entity causedBy) {
        if (causedBy != null) {
            victim.setData(BannerboundAntiquity.POISON_BY.get(), causedBy.getStringUUID());
        } else {
            victim.removeData(BannerboundAntiquity.POISON_BY.get());
        }
    }

    private static DamageSource poisonDamage(LivingEntity victim) {
        Entity owner = null;
        if (victim.level() instanceof ServerLevel sl) {
            String by = victim.getData(BannerboundAntiquity.POISON_BY.get());
            if (!by.isEmpty()) {
                try {
                    owner = sl.getEntity(UUID.fromString(by));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return victim.damageSources().source(BannerboundAntiquity.POISON_DAMAGE, owner);
    }

    private static void setState(LivingEntity victim, PoisonState state) {
        victim.setData(BannerboundAntiquity.POISON_STATE.get(), state);
        // Bridge for Core citizens: mirror the stage into shared persistent data; 0 clears it.
        victim.getPersistentData().putInt(POISON_STAGE_TAG, state.active() ? state.stage() : 0);
    }

    public static void tickPoison(LivingEntity victim, ServerLevel level) {
        PoisonState s = getPoison(victim);
        if (!s.active()) {
            return;
        }
        PoisonType type = s.type();
        boolean isPlayer = victim instanceof Player;
        long now = level.getGameTime();
        int stage = s.stage();
        if (now >= s.stageEndsAt()) {
            if (stage < type.maxStage()) {
                stage++;
                setState(victim, new PoisonState(type, stage, now + Config.POISON_STAGE_ADVANCE_TICKS.get()));
            } else if (type.lethalAtMaxStage() && (isPlayer || type.toxicToAnimals())) {
                victim.hurt(poisonDamage(victim),
                    victim.getMaxHealth() * 10.0F + 1000.0F);
                return;
            } else {
                cure(victim);
                return;
            }
        }
        if (type.dealsDamageOverTime() && now % Config.POISON_DOT_INTERVAL_TICKS.get() == 0) {
            applyDot(victim, type, stage, isPlayer);
        }
        type.tick(victim, stage, level);
        if (now % 10 == 0) {
            emitPoisonHaze(victim, type, level);
        }
    }

    private static void emitPoisonHaze(LivingEntity victim, PoisonType type, ServerLevel level) {
        int c = type.tintColor();
        level.sendParticles(
            ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,
                ((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F),
            victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
            2, victim.getBbWidth() * 0.4, victim.getBbHeight() * 0.35, victim.getBbWidth() * 0.4, 0.0);
    }

    private static void applyDot(LivingEntity victim, PoisonType type, int stage, boolean isPlayer) {
        if (!isPlayer && !type.toxicToAnimals()) {
            return;
        }
        float dmg = (float) (double) Config.POISON_DOT_PER_STAGE.get() * stage;
        if (isPlayer) {
            dmg *= (float) (double) Config.POISON_PLAYER_DOT_MULT.get();
        }
        boolean lethal = type.lethalAtMaxStage() && stage >= type.maxStage() && !isPlayer;
        if (!lethal) {
            float floor = (float) (double) Config.POISON_NONLETHAL_FLOOR.get();
            dmg = Math.min(dmg, Math.max(0.0F, victim.getHealth() - floor));
        }
        if (dmg <= 0.0F) {
            return;
        }
        victim.hurt(poisonDamage(victim), dmg);
    }

    public static boolean blocksHealing(LivingEntity victim) {
        return getPoison(victim).type() == PoisonType.OLEANDER;
    }

    static void startOleanderClock(LivingEntity victim) {
        victim.setData(BannerboundAntiquity.POISON_CARDIAC_AT.get(),
            victim.level().getGameTime() + Config.POISON_OLEANDER_CLOCK_TICKS.get());
        victim.setData(BannerboundAntiquity.POISON_NEXT_VOMIT.get(), 0L);
    }

    static void clearOleanderClock(LivingEntity victim) {
        victim.setData(BannerboundAntiquity.POISON_CARDIAC_AT.get(), 0L);
    }

    private static final long OLEANDER_MIN_TICKS = 300L;
    private static final double OLEANDER_DOSE_KEEP = 0.6;

    static void accelerateOleanderClock(LivingEntity victim) {
        long now = victim.level().getGameTime();
        long deadline = victim.getData(BannerboundAntiquity.POISON_CARDIAC_AT.get());
        if (deadline <= 0L) {
            startOleanderClock(victim);
            return;
        }
        long remaining = Math.max(0L, deadline - now);
        long shortened = Math.max(OLEANDER_MIN_TICKS, (long) (remaining * OLEANDER_DOSE_KEEP));
        victim.setData(BannerboundAntiquity.POISON_CARDIAC_AT.get(), now + shortened);
    }

    static void oleanderTick(LivingEntity victim, int stage, ServerLevel level) {
        long now = level.getGameTime();
        long deadline = victim.getData(BannerboundAntiquity.POISON_CARDIAC_AT.get());
        if (deadline <= 0L) {
            startOleanderClock(victim);
            return;
        }
        if (now >= deadline) {
            clearOleanderClock(victim);
            victim.hurt(poisonDamage(victim),
                victim.getMaxHealth() * 10.0F + 1000.0F);
            return;
        }
        long nextCough = victim.getData(BannerboundAntiquity.POISON_NEXT_VOMIT.get());
        if (now >= nextCough) {
            if (nextCough > 0L) {
                vomit(victim, level, PoisonType.WOLFSBANE.belchSound());
                victim.hurt(poisonDamage(victim), 3.0F);
            }
            double clock = Math.max(1.0, Config.POISON_OLEANDER_CLOCK_TICKS.get());
            double f = Math.max(0.0, Math.min(1.0, 1.0 - (deadline - now) / clock));
            long slow = 800L + victim.getRandom().nextInt(401);
            long interval = Math.round(slow + (300.0 - slow) * f);
            victim.setData(BannerboundAntiquity.POISON_NEXT_VOMIT.get(), now + interval);
        }
    }

    public static boolean isCurareUnconscious(LivingEntity victim, long now) {
        if (getPoison(victim).type() != PoisonType.CURARE) {
            return false;
        }
        long faintAt = victim.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        long wakeAt = victim.getData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get());
        return faintAt > 0L && now >= faintAt && now < wakeAt;
    }

    static void startCurareClocks(LivingEntity victim) {
        long faintAt = victim.level().getGameTime() + Config.POISON_CURARE_STUN_TICKS.get();
        victim.setData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get(), faintAt);
        victim.setData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get(), faintAt + curareOutTicks(victim));
    }

    private static final int CURARE_MAX_OUT_MULT = 3;

    static void refreshCurare(LivingEntity victim) {
        long now = victim.level().getGameTime();
        long faintAt = victim.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        if (faintAt > 0L && now >= faintAt) {
            long cap = faintAt + (long) CURARE_MAX_OUT_MULT * curareOutTicks(victim);
            victim.setData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get(),
                Math.min(now + curareOutTicks(victim), cap));
        } else {
            startCurareClocks(victim);
        }
    }

    private static int curareOutTicks(LivingEntity victim) {
        return (victim instanceof Player)
            ? Config.POISON_CURARE_PLAYER_OUT_TICKS.get()
            : Config.POISON_CURARE_ANIMAL_OUT_TICKS.get();
    }

    static void clearCurare(LivingEntity victim) {
        clearParalysis(victim);
        victim.setData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get(), 0L);
        victim.setData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get(), 0L);
        int dragger = victim.getData(BannerboundAntiquity.DRAGGED_BY.get());
        if (dragger != 0) {
            victim.setData(BannerboundAntiquity.DRAGGED_BY.get(), 0);
            if (victim.level().getEntity(dragger) instanceof LivingEntity d) {
                d.setData(BannerboundAntiquity.DRAGGING.get(), 0);
            }
        }
    }

    static void curareTick(LivingEntity victim, ServerLevel level) {
        long now = level.getGameTime();
        long wakeAt = victim.getData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get());
        if (wakeAt <= 0L) {
            startCurareClocks(victim);
            return;
        }
        if (now >= wakeAt) {
            cure(victim);
            return;
        }
        boolean unconscious = now >= victim.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        setParalysisSlow(victim, unconscious ? -1.0 : -0.8);
        applyJumpLock(victim);
        if (victim instanceof ServerPlayer player) {
            player.setSprinting(false);
        }
        if (victim instanceof PathfinderMob mob) {
            mob.getNavigation().stop();
        }
        if (unconscious && victim.getData(BannerboundAntiquity.DRAGGED_BY.get()) == 0) {
            // Pin in place only when NOT being towed - the drag owns the velocity then.
            Vec3 v = victim.getDeltaMovement();
            victim.setDeltaMovement(0.0, v.y, 0.0);
            victim.hurtMarked = true;
        }
    }

    static void belladonnaTick(LivingEntity victim, int stage, ServerLevel level) {
        if (!(victim instanceof PathfinderMob mob)) {
            return;
        }
        var rng = mob.getRandom();
        if (rng.nextInt(Math.max(10, 50 - stage * 9)) == 0) {
            double a = rng.nextDouble() * Math.PI * 2.0;
            double d = 3.0 + rng.nextDouble() * 5.0;
            mob.getNavigation().moveTo(mob.getX() + Math.cos(a) * d, mob.getY(), mob.getZ() + Math.sin(a) * d, 1.4);
        }
        if (rng.nextInt(Math.max(8, 40 - stage * 8)) == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
            if (rng.nextInt(3) == 0) {
                List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
                    mob.getBoundingBox().inflate(2.0), e -> e != mob && e.isAlive());
                if (!near.isEmpty()) {
                    near.get(rng.nextInt(near.size())).hurt(mob.damageSources().mobAttack(mob), 1.0F);
                }
            }
        }
    }

    static void wolfsbaneTick(LivingEntity victim, int stage, ServerLevel level) {
        applyParalysisSlow(victim, stage);
        int period = Math.max(PULSE_MIN_PERIOD, PULSE_BASE_PERIOD - (stage - 1) * PULSE_PERIOD_STEP);
        if (level.getGameTime() % period < PULSE_LENGTH) {
            rootPulse(victim);
        }
        if (victim.onGround() && victim.getRandom().nextInt(Math.max(15, 45 - stage * 8)) == 0) {
            double a = victim.getRandom().nextDouble() * Math.PI * 2.0;
            victim.setDeltaMovement(Math.cos(a) * 0.18, victim.getDeltaMovement().y, Math.sin(a) * 0.18);
            victim.hurtMarked = true;
        }
        if (victim instanceof ServerPlayer player) {
            player.setSprinting(false);
            if (jumpBucklesNow(victim, stage, level.getGameTime())) {
                applyJumpLock(victim);
            } else {
                removeJumpLock(victim);
            }
        }
        if (stage >= PoisonType.WOLFSBANE.maxStage()) {
            long now = level.getGameTime();
            long next = victim.getData(BannerboundAntiquity.POISON_NEXT_VOMIT.get());
            if (now >= next) {
                if (next > 0L) {
                    vomit(victim, level, PoisonType.WOLFSBANE.belchSound());
                }
                victim.setData(BannerboundAntiquity.POISON_NEXT_VOMIT.get(),
                    now + 1200L + victim.getRandom().nextInt(1201));
            }
        }
    }

    private static boolean jumpBucklesNow(LivingEntity victim, int stage, long gameTime) {
        if (stage < 2) {
            return false;
        }
        long window = gameTime / JUMP_WINDOW_TICKS;
        long h = window * 0x9E3779B97F4A7C15L ^ ((long) victim.getId() * 0x2545F4914F6CDD1DL);
        h ^= (h >>> 29);
        int roll = (int) Math.floorMod(h, 100);
        int chance = stage == 2 ? 35 : stage == 3 ? 55 : 75; // % of windows the legs give out
        return roll < chance;
    }

    private static void vomit(LivingEntity victim, ServerLevel level, SoundEvent belch) {
        Vec3 look = victim.getLookAngle();
        Vec3 mouth = victim.getEyePosition().add(look.scale(0.3)).subtract(0.0, 0.15, 0.0);
        level.sendParticles(BannerboundAntiquity.BLOOD_DROP.get(),
            mouth.x, mouth.y, mouth.z, 18, look.x * 0.12, 0.02, look.z * 0.12, 0.18);
        if (belch == null) {
            return;
        }
        Player except = victim instanceof Player p ? p : null;
        if (victim instanceof ServerPlayer sp) {
            sp.playNotifySound(belch, SoundSource.PLAYERS, 0.9F, 1.0F);
        }
        level.playSound(except, victim.getX(), victim.getY(), victim.getZ(),
            belch, SoundSource.PLAYERS, 0.7F, 1.0F);
    }

    private static void rootPulse(LivingEntity victim) {
        Vec3 v = victim.getDeltaMovement();
        victim.setDeltaMovement(0.0, v.y, 0.0);
        victim.hurtMarked = true; // force a velocity packet or the client never sees the freeze
        if (victim instanceof PathfinderMob mob) {
            mob.getNavigation().stop();
        }
    }

    private static void applyParalysisSlow(LivingEntity victim, int stage) {
        setParalysisSlow(victim, -Math.min(0.9, Config.POISON_SLOW_PER_STAGE.get() * stage));
    }

    private static void setParalysisSlow(LivingEntity victim, double slow) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        AttributeModifier existing = speed.getModifier(PARALYSIS_SPEED_ID);
        if (existing == null || existing.amount() != slow) {
            speed.removeModifier(PARALYSIS_SPEED_ID);
            speed.addTransientModifier(new AttributeModifier(PARALYSIS_SPEED_ID, slow,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static void applyJumpLock(LivingEntity victim) {
        AttributeInstance jump = victim.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null && jump.getModifier(PARALYSIS_JUMP_ID) == null) {
            jump.addTransientModifier(new AttributeModifier(PARALYSIS_JUMP_ID, -1.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static void removeJumpLock(LivingEntity victim) {
        AttributeInstance jump = victim.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(PARALYSIS_JUMP_ID);
        }
    }

    static void clearParalysis(LivingEntity victim) {
        AttributeInstance speed = victim.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(PARALYSIS_SPEED_ID);
        }
        AttributeInstance jump = victim.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(PARALYSIS_JUMP_ID);
        }
    }
}
