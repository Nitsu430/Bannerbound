package com.bannerbound.antiquity.item;

import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Player drunkenness from grog (GROG_PLAN.md Phase 3.5). Every sip ({@link #sip}) restores food,
 * applies the grog's per-sip effects (e.g. berry grog -> regeneration), and bumps an intoxication
 * level by the grog's strength; sips within the {@link #WINDOW_TICKS} (30s) window STACK
 * (Sea-of-Thieves-style) so chugging gets you hammered, and the level decays one step per window of
 * abstinence. Escalating tiers: the swimming drunk shader, a stumble (slowness at L4, worse at L6),
 * then inverted controls - the visuals and the high-tier control inversion are CLIENT-side off the
 * synced level (no vanilla Nausea); the server applies only the stumble. From {@link #VOMIT_MIN}
 * you randomly retch (chance/second scales ~6% at L5 -> ~18% at L7): green bile that costs HUNGER
 * (never health) and splatters {@link #VOMIT_OVERLAY_TICKS} of goo on the screen of anyone caught
 * in the {@link #VOMIT_RANGE}/{@link #VOMIT_CONE} face cone ({@link #splatter} is reused by the
 * {@code /bannerbound vomit_overlay} test command). Drink past {@link #MAX} and you BLACK OUT
 * (curare-style: out cold for {@link #PASS_OUT_TICKS} with input locked, then you come to at
 * {@link #RECOVER_LEVEL}, still drunk). Waking at or above {@link #HANGOVER_THRESHOLD} clears the
 * drink but starts a {@link #HANGOVER_TICKS} hangover ({@link #startHangover}, called from the
 * wake-up event): groggy slowness/weakness, a client vignette/muffle, and the {@link #craftQuality}
 * penalty - hungover hands always craft CRUDE; otherwise the rolled hand-craft tier (knapping /
 * fletching / hammer) is steady when tipsy, drops 1 tier when drunk (L3-4), 2 when very drunk
 * (L5-6), and bottoms at CRUDE once hammered (L7+). Server-authoritative: the level and the
 * pass-out/hangover deadlines live in synced player data attachments; {@link #serverTick} runs the
 * hangover, then the black-out, then decay + tier effects, throttled per player.
 */
public final class Intoxication {
    public static final int MAX = 8;
    public static final int WINDOW_TICKS = 600;
    public static final int PASS_OUT_TICKS = 220;
    public static final int RECOVER_LEVEL = 3;
    public static final int HANGOVER_TICKS = 600;
    public static final int HANGOVER_THRESHOLD = 4;
    public static final int VOMIT_MIN = 5;
    public static final int VOMIT_OVERLAY_TICKS = 200;
    public static final double VOMIT_RANGE = 3.5;
    public static final double VOMIT_CONE = 0.86;

    private Intoxication() {
    }

    public static void sip(Player player, List<MobEffectInstance> effects, int strength, int foodValue) {
        Level level = player.level();
        if (level.isClientSide) return;
        if (foodValue > 0) player.getFoodData().eat(foodValue, 0.1F * Math.max(1, strength));
        for (MobEffectInstance e : effects) player.addEffect(new MobEffectInstance(e));

        long now = level.getGameTime();
        int lvl = player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        long last = player.getData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get());
        int add = Math.max(1, strength);
        lvl = (now - last <= WINDOW_TICKS) ? lvl + add : add;
        player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), Math.min(lvl, MAX));
        player.setData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get(), now);
    }

    public static void serverTick(Player player) {
        Level level = player.level();
        if (level.isClientSide) return;
        // No decay while asleep: a night-skip would sober past HANGOVER_THRESHOLD before waking and dodge the hangover.
        if (player.isSleeping()) return;
        long now = level.getGameTime();

        long hangover = player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
        if (hangover > 0) {
            if (now < hangover) {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, false, false, false));
            } else {
                player.setData(BannerboundAntiquity.HANGOVER_UNTIL.get(), 0L);
            }
        }

        long passOut = player.getData(BannerboundAntiquity.PASS_OUT_UNTIL.get());
        if (passOut > 0) {
            if (now < passOut) {
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 9, false, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.JUMP, 30, 128, false, false, false)); // amplifier 128 makes jump strength negative -> no jumping
                return;
            }
            player.setData(BannerboundAntiquity.PASS_OUT_UNTIL.get(), 0L);
            player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), RECOVER_LEVEL);
            player.setData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get(), now);
        }

        int lvl = player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        if (lvl <= 0) return;

        if (lvl >= MAX) {
            player.setData(BannerboundAntiquity.PASS_OUT_UNTIL.get(), now + PASS_OUT_TICKS);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4F, 0.5F);
            return;
        }

        if (now - player.getData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get()) >= WINDOW_TICKS) {
            lvl = Math.max(0, lvl - 1);
            player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), lvl);
            player.setData(BannerboundAntiquity.INTOXICATION_LAST_SIP.get(), now);
            if (lvl <= 0) return;
        }
        if (lvl >= 4) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0, false, false, false));
        }
        if (lvl >= 6) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false, false));
        }

        if (lvl >= VOMIT_MIN && player instanceof ServerPlayer sp && level instanceof ServerLevel sl
                && sp.getRandom().nextFloat() < 0.06F * (lvl - (VOMIT_MIN - 1))) {
            vomit(sp, sl);
        }
    }

    private static void vomit(ServerPlayer player, ServerLevel level) {
        Vec3 look = player.getLookAngle();
        Vec3 mouth = player.getEyePosition().add(look.scale(0.3)).subtract(0.0, 0.15, 0.0);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.SLIME_BALL)),
            mouth.x, mouth.y, mouth.z, 16, look.x * 0.14, 0.02, look.z * 0.14, 0.2);

        FoodData food = player.getFoodData();
        food.setFoodLevel(Math.max(0, food.getFoodLevel() - 3));
        food.setSaturation(Math.min(food.getSaturationLevel(), food.getFoodLevel()));

        SoundEvent belch = BannerboundAntiquity.WOLFSBANE_BELCH.get();
        player.playNotifySound(belch, SoundSource.PLAYERS, 0.9F, 1.1F);
        // playSound(player, ...) EXCLUDES that player; the notify above covers first-person.
        level.playSound(player, player.getX(), player.getY(), player.getZ(),
            belch, SoundSource.PLAYERS, 0.7F, 1.1F);

        Vec3 eye = player.getEyePosition();
        for (Player other : level.players()) {
            if (other == player || !other.isAlive()) {
                continue;
            }
            Vec3 to = other.getEyePosition().subtract(eye);
            if (to.length() <= VOMIT_RANGE && look.dot(to.normalize()) >= VOMIT_CONE) {
                splatter(other);
            }
        }
    }

    public static void splatter(Player target) {
        Level level = target.level();
        if (level.isClientSide) return;
        target.setData(BannerboundAntiquity.VOMIT_OVERLAY_UNTIL.get(),
            level.getGameTime() + VOMIT_OVERLAY_TICKS);
    }

    public static void startHangover(Player player) {
        Level level = player.level();
        if (level.isClientSide) return;
        if (player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get()) < HANGOVER_THRESHOLD) return;
        player.setData(BannerboundAntiquity.INTOXICATION_LEVEL.get(), 0);
        player.setData(BannerboundAntiquity.PASS_OUT_UNTIL.get(), 0L);
        player.setData(BannerboundAntiquity.HANGOVER_UNTIL.get(),
            level.getGameTime() + HANGOVER_TICKS);
    }

    public static int level(Player player) {
        return player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
    }

    public static boolean isHungover(Player player) {
        return player.level().getGameTime() < player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
    }

    public static QualityTier craftQuality(Player player, QualityTier rolled) {
        if (isHungover(player)) {
            return QualityTier.CRUDE;
        }
        int lvl = level(player);
        if (lvl < 3) {
            return rolled;
        }
        int drop = lvl >= 7 ? QualityTier.values().length : (lvl >= 5 ? 2 : 1);
        int idx = Math.max(QualityTier.CRUDE.ordinal(), rolled.ordinal() - drop);
        return QualityTier.values()[idx];
    }
}
