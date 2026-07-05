package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Quaternionf;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;
import com.bannerbound.antiquity.combat.BluntStun;
import com.bannerbound.antiquity.item.Intoxication;
import com.bannerbound.antiquity.item.PoisonedFoodData;
import com.bannerbound.antiquity.poison.PoisonState;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.core.client.ClientPopulationState;
import com.bannerbound.core.client.SoundMuffle;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Client game-bus handlers for the local player's status effects (poison, stun, drunkenness, curare). */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class StatusClientEffects {

    /*
     * Client game-bus driver for the local player's poison senses: crossfades the four stage ambience
     * drones of whichever poison is active, drives the wolfsbane world-muffle + FOV pull, the belladonna
     * false-sounds + phantom figures, the screen post-process, and the antidote heal-flash.
     */
    /** The four stage drones for the currently-afflicting poison (recreated when the poison changes). */
    private static final PoisonAmbienceSound[] LAYERS = new PoisonAmbienceSound[4];
    private static PoisonType ambiencePoison = null;

    /** Random vanilla sounds belladonna plays from nowhere — threats that aren't there. (Antiquity's
     *  vanilla mobs are gone, but their sound events still exist.) */
    private static final SoundEvent[] FALSE_SOUNDS = {
        SoundEvents.ZOMBIE_AMBIENT, SoundEvents.SKELETON_AMBIENT, SoundEvents.SPIDER_AMBIENT,
        SoundEvents.ENDERMAN_AMBIENT, SoundEvents.CREEPER_PRIMED, SoundEvents.WITHER_SKELETON_AMBIENT,
        SoundEvents.HUSK_AMBIENT, SoundEvents.AMBIENT_CAVE.value(), SoundEvents.ZOMBIE_VILLAGER_AMBIENT
    };

    /** Duration of the antidote relief-flash (1.5s). */
    private static final float FLASH_TICKS = 30.0F;
    private static int lastStage = 0;
    private static long healFlashStart = Long.MIN_VALUE;

    private StatusClientEffects() {}

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            stopAll();
            ambiencePoison = null;
            SoundMuffle.set(1.0F);
            lastStage = 0;
            StatusClientEffects.clear();
            PoisonHeartbeat.reset();
            return;
        }
        PoisonState s = player.getData(BannerboundAntiquity.POISON_STATE.get());
        PoisonType type = s.active() ? s.type() : null;
        int stage = s.active() ? s.stage() : 0;

        // Heal-flash fires when ANY poison clears.
        if (lastStage > 0 && stage == 0 && mc.level != null) {
            healFlashStart = mc.level.getGameTime();
        }
        lastStage = stage;

        driveAmbience(mc, type, stage);

        // Wolfsbane: world recedes (muffle). Belladonna: no muffle.
        int wolfsbaneStage = (type == PoisonType.WOLFSBANE) ? stage : 0;
        SoundMuffle.set(wolfsbaneStage <= 0 ? 1.0F : Math.max(0.36F, 1.0F - wolfsbaneStage * 0.16F));

        // Belladonna: false sounds + phantom figures.
        if (type == PoisonType.BELLADONNA && stage > 0) {
            RandomSource rng = player.getRandom();
            if (rng.nextFloat() < stage * 0.004F) {
                playFalseSound(mc, player, rng);
            }
            StatusClientEffects.tick(player, stage, rng);
        } else {
            StatusClientEffects.clear();
        }

        // Oleander: an accelerating heartbeat as the cardiac clock runs down.
        if (type == PoisonType.OLEANDER && stage > 0) {
            PoisonHeartbeat.tick(oleanderFraction(player, player.level().getGameTime()));
        } else {
            PoisonHeartbeat.reset();
        }
    }

    /** How far oleander's fixed cardiac clock has run: 0 at infection → 1 at arrest. Reads the synced
     *  deadline attachment and the (server-synced) clock-length config. 0 when no clock is set. */
    private static float oleanderFraction(LocalPlayer player, long now) {
        long deadline = player.getData(BannerboundAntiquity.POISON_CARDIAC_AT.get());
        if (deadline <= 0L) {
            return 0.0F;
        }
        float total = Config.POISON_OLEANDER_CLOCK_TICKS.get();
        if (total <= 0.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, 1.0F - (deadline - now) / total));
    }

    private static void playFalseSound(Minecraft mc, LocalPlayer player, RandomSource rng) {
        if (mc.level == null) {
            return;
        }
        SoundEvent sound = FALSE_SOUNDS[rng.nextInt(FALSE_SOUNDS.length)];
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = 3.0 + rng.nextDouble() * 7.0;
        double x = player.getX() + Math.cos(angle) * dist;
        double z = player.getZ() + Math.sin(angle) * dist;
        double y = player.getY() + rng.nextDouble() * 2.0 - 0.4;
        // playLocalSound = only this client hears it (it's in your head).
        mc.level.playLocalSound(x, y, z, sound, SoundSource.HOSTILE,
            0.5F + rng.nextFloat() * 0.4F, 0.7F + rng.nextFloat() * 0.5F, false);
    }

    /** Heal-flash envelope (0 = inactive): a quick rise then a soft fade over {@link #FLASH_TICKS}. */
    public static float healFlash(float time) {
        if (healFlashStart == Long.MIN_VALUE) {
            return 0.0F;
        }
        float elapsed = time - healFlashStart;
        if (elapsed < 0.0F || elapsed > FLASH_TICKS) {
            return 0.0F;
        }
        float p = elapsed / FLASH_TICKS;
        return p < 0.15F ? p / 0.15F : Math.max(0.0F, 1.0F - (p - 0.15F) / 0.85F);
    }

    private static void driveAmbience(Minecraft mc, PoisonType type, int stage) {
        boolean hasAmbience = type == PoisonType.WOLFSBANE || type == PoisonType.BELLADONNA;
        if (!hasAmbience || stage <= 0) {
            stopAll();
            ambiencePoison = null;
            return;
        }
        // A different poison than the loaded drones → fade the old set out and start fresh (a layer
        // can't change which OGG it plays).
        if (ambiencePoison != type) {
            stopAll();
            for (int i = 0; i < LAYERS.length; i++) {
                LAYERS[i] = null;
            }
            ambiencePoison = type;
        }
        for (int i = 0; i < LAYERS.length; i++) {
            boolean active = (i + 1) == stage;
            PoisonAmbienceSound snd = LAYERS[i];
            if (active) {
                if (snd == null || snd.isStopped()) {
                    snd = new PoisonAmbienceSound(ambienceFor(type, i + 1));
                    LAYERS[i] = snd;
                    mc.getSoundManager().play(snd);
                }
                snd.setTarget(1.0F);
            } else if (snd != null && !snd.isStopped()) {
                snd.setTarget(0.0F); // crossfade out (self-stops at silence)
            }
        }
    }

    private static void stopAll() {
        for (PoisonAmbienceSound snd : LAYERS) {
            if (snd != null && !snd.isStopped()) {
                snd.setTarget(0.0F);
            }
        }
    }

    private static SoundEvent ambienceFor(PoisonType type, int stage) {
        if (type == PoisonType.BELLADONNA) {
            return switch (stage) {
                case 1 -> BannerboundAntiquity.BELLADONNA_AMBIENCE_1.get();
                case 2 -> BannerboundAntiquity.BELLADONNA_AMBIENCE_2.get();
                case 3 -> BannerboundAntiquity.BELLADONNA_AMBIENCE_3.get();
                default -> BannerboundAntiquity.BELLADONNA_AMBIENCE_4.get();
            };
        }
        return switch (stage) {
            case 1 -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_1.get();
            case 2 -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_2.get();
            case 3 -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_3.get();
            default -> BannerboundAntiquity.WOLFSBANE_AMBIENCE_4.get();
        };
    }

    /** Run the poison-vision post-process after the world renders but before the HUD (so the HUD
     *  stays crisp). Iris-safe — this is the GUI stage, after the world/shaders are done. */
    @SubscribeEvent
    static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (!s.active()) {
            return;
        }
        float time = (float) mc.level.getGameTime();
        if (s.type() == PoisonType.WOLFSBANE || s.type() == PoisonType.BELLADONNA) {
            PoisonPostProcessor.render(s.type(), s.stage(), time, 0.0F, 0.0F);
        } else if (s.type() == PoisonType.OLEANDER) {
            PoisonPostProcessor.render(s.type(), s.stage(), time,
                oleanderFraction(mc.player, mc.level.getGameTime()), PoisonHeartbeat.pulse(time));
        }
    }

    /** Swap the vanilla death screen for a poison-flavoured one when the local player died while
     *  poisoned. Reads the still-set synced poison attachment (the lethal hit doesn't clear it). The
     *  poisoned-but-killed-by-something-else case still shows the poison screen — intended (it's a handy
     *  fast way to test the screens: fall off a cliff at a high stage instead of waiting out the clock). */
    @SubscribeEvent
    static void onDeathScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof DeathScreen) || event.getNewScreen() instanceof PoisonDeathScreen) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (s.active() && (s.type() == PoisonType.WOLFSBANE || s.type() == PoisonType.BELLADONNA
            || s.type() == PoisonType.OLEANDER)) {
            boolean hardcore = mc.level != null && mc.level.getLevelData().isHardcore();
            event.setNewScreen(new PoisonDeathScreen(s.type(), hardcore));
        }
    }

    @SubscribeEvent
    static void onComputeFov(ComputeFovModifierEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (!s.active() || s.type() != PoisonType.WOLFSBANE) {
            return; // FOV pull is wolfsbane's numbing; belladonna leaves FOV alone
        }
        float narrow = 1.0F - Math.min(0.12F, s.stage() * 0.035F); // up to ~-12% FOV, steady
        event.setNewFovModifier(event.getNewFovModifier() * narrow);
    }

    /*
     * Reveals a laced food's poison on its tooltip — but ONLY to players in the SAME settlement that
     * poisoned it (so your own people know which rations to avoid, while the enemy you're feeding it to
     * sees a perfectly normal apple). The poison data rides the stack everywhere; this is purely a
     * display gate, comparing the stored poisoner-settlement to the local player's synced settlement id.
     */
    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        PoisonedFoodData laced = event.getItemStack().get(BannerboundAntiquity.POISONED_FOOD.get());
        if (laced == null) {
            return;
        }
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null || laced.poisoner().isEmpty()) {
            return;
        }
        UUID poisoner;
        try {
            poisoner = UUID.fromString(laced.poisoner());
        } catch (IllegalArgumentException e) {
            return;
        }
        // Reveal only to the poisoner themselves, or to whoever currently shares their settlement.
        if (!me.getUUID().equals(poisoner) && !ClientPopulationState.isMember(poisoner)) {
            return; // looks like a clean apple to you
        }
        PoisonType type = PoisonType.fromId(laced.poisonId());
        Component name = Component.translatable("poison.bannerboundantiquity."
            + (type != null ? type.id() : laced.poisonId()));
        event.getToolTip().add(Component.translatable(
            "bannerboundantiquity.poisoned_food.tooltip", name, laced.dose())
            .withStyle(ChatFormatting.DARK_GREEN));
    }

    /*
     * Belladonna's phantom dread — featureless black FIGURES (a translucent humanoid model wearing the
     * {@code phantom.png} skin) that appear at the EDGE of the player's vision and vanish the instant
     * they're looked at directly. Pure client-side hallucination: no real entity, no collision, no sound
     * of their own. Driven each client tick by {@link StatusClientEffects} while belladonna-poisoned.
     */
    private static final class Phantom {
        final double x;
        final double y;
        final double z;
        final long spawnTick;
        final long expireTick;
        long lookFadeStart = -1L; // game-time the player looked at it (-1 = not yet dissolving)

        Phantom(double x, double y, double z, long spawnTick, long expireTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.spawnTick = spawnTick;
            this.expireTick = expireTick;
        }
    }

    private static final List<Phantom> PHANTOMS = new ArrayList<>();
    private static final int MAX = 2;
    /** Looking within this cosine of a phantom (≈ this side-angle) makes it begin to dissolve. */
    private static final double LOOK_VANISH_DOT = 0.86; // ~31°
    /** How long a phantom takes to fade out once you look at it (≈ 0.2s) — a quick dissolve, not a pop. */
    private static final long LOOK_FADE_TICKS = 4L;
    /** Peak opacity (0-255) — kept low so the figures are faint/ghostly. */
    private static final float MAX_ALPHA = 105.0F;
    private static final ResourceLocation PHANTOM_TEX =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "textures/entity/phantom.png");

    private static PlayerModel<LivingEntity> model;

    public static void clear() {
        PHANTOMS.clear();
    }

    /** Cull figures that expired or that the player turned to face; occasionally spawn a new one at
     *  the edge of view (more often at higher stages). */
    public static void tick(LocalPlayer player, int stage, RandomSource rng) {
        long now = player.level().getGameTime();
        Vec3 eye = player.getEyePosition();
        Vec3 lookFlat = horiz(player.getViewVector(1.0F));
        // Drop figures that naturally expired or that have finished dissolving from a look.
        PHANTOMS.removeIf(p -> now >= p.expireTick
            || (p.lookFadeStart >= 0 && now - p.lookFadeStart >= LOOK_FADE_TICKS));
        // Looking at one BEGINS its dissolve (a smooth fade, not an instant pop).
        for (Phantom p : PHANTOMS) {
            if (p.lookFadeStart < 0) {
                Vec3 to = horiz(new Vec3(p.x - eye.x, 0.0, p.z - eye.z));
                if (lookFlat.dot(to) > LOOK_VANISH_DOT) {
                    p.lookFadeStart = now;
                }
            }
        }
        // Steep per-stage curve so low stages are rare and stage 4 is frequent: stage² × 0.004 →
        // ~1 every 12s at stage 1, ~1 every 4s at stage 2, climbing to ~1 per second at stage 4.
        if (PHANTOMS.size() < MAX && rng.nextFloat() < stage * stage * 0.004F) {
            spawn(player, rng, now);
        }
    }

    private static void spawn(LocalPlayer player, RandomSource rng, long now) {
        double side = rng.nextBoolean() ? 1.0 : -1.0;
        double offset = Math.toRadians(48.0 + rng.nextDouble() * 34.0); // 48-82° off to the side
        double yaw = Math.toRadians(player.getYRot()) + side * offset;
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        double dist = 6.0 + rng.nextDouble() * 7.0;
        double x = player.getX() + dx * dist;
        double z = player.getZ() + dz * dist;
        double y = player.getY(); // stands at the player's feet height (fine on roughly level ground)
        long life = 40L + rng.nextInt(60); // 2–5 s
        PHANTOMS.add(new Phantom(x, y, z, now, now + life));
    }

    private static Vec3 horiz(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0, v.z);
        return f.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : f.normalize();
    }

    private static PlayerModel<LivingEntity> model() {
        if (model == null) {
            try {
                ModelPart root = Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER);
                model = new PlayerModel<>(root, false);
                model.young = false; // EntityModel.young defaults TRUE → baby proportions; force adult
            } catch (Exception e) {
                return null;
            }
        }
        return model;
    }

    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || PHANTOMS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            return;
        }
        PlayerModel<LivingEntity> m = model();
        if (m == null) {
            return;
        }
        long now = mc.level.getGameTime();
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        RenderType rt = RenderType.entityTranslucent(PHANTOM_TEX);
        var vc = buf.getBuffer(rt);
        for (Phantom p : PHANTOMS) {
            int alpha = phantomAlpha(p, now);
            if (alpha <= 0) {
                continue;
            }
            // Body yaw to face the player.
            float faceYaw = (float) Math.toDegrees(Math.atan2(p.x - player.getX(), player.getZ() - p.z));
            pose.pushPose();
            pose.translate(p.x - camPos.x, p.y - camPos.y, p.z - camPos.z);
            pose.mulPose(Axis.YP.rotationDegrees(faceYaw));
            pose.scale(-1.0F, -1.0F, 1.0F);   // entity-model space (Y/X flipped)
            pose.translate(0.0F, -1.501F, 0.0F); // feet to the ground
            int color = (alpha << 24) | 0xFFFFFF;
            m.renderToBuffer(pose, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
            pose.popPose();
        }
        buf.endBatch(rt);
    }

    /** Fade in over the first ~8 ticks, hold, fade out over the last ~8 — and dissolve when looked at. */
    private static int phantomAlpha(Phantom p, long now) {
        long age = now - p.spawnTick;
        long left = p.expireTick - now;
        float fade = Math.min(age / 8.0F, Math.min(left / 8.0F, 1.0F));
        if (p.lookFadeStart >= 0) {
            float lookFade = 1.0F - (now - p.lookFadeStart) / (float) LOOK_FADE_TICKS;
            fade = Math.min(fade, Math.max(0.0F, lookFade));
        }
        return (int) (Math.max(0.0F, Math.min(1.0F, fade)) * MAX_ALPHA);
    }

    /*
     * Client driver for the blunt-weapon crit DAZE: while the local player is mid-stun, blur their vision
     * in and out for the 1s stagger. Reads the synced {@link BannerboundAntiquity#STUN_UNTIL} deadline and
     * drives the shared {@link PoisonPostProcessor} blur pass. Run at the GUI stage (after the world +
     * shaders, before the HUD) so it's Iris-safe and the HUD stays crisp — same as the poison vision.
     */
    @SubscribeEvent
    static void onStunRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        long until = mc.player.getData(BannerboundAntiquity.STUN_UNTIL.get());
        if (until <= 0L) {
            return;
        }
        // Smooth game time (with the partial tick) so the fade doesn't step at 20 Hz.
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float remaining = until - now;
        if (remaining <= 0.0F || remaining > BluntStun.STUN_TICKS) {
            return;
        }
        // Fade IN over the first ~0.2s, then OUT over the rest of the 1s — a quick daze that lifts.
        float elapsed = BluntStun.STUN_TICKS - remaining;             // 0 → 20 ticks
        float p = elapsed / BluntStun.STUN_TICKS;                     // 0 → 1
        float envelope = p < 0.2F ? p / 0.2F : 1.0F - (p - 0.2F) / 0.8F;
        PoisonPostProcessor.renderStun(envelope, now);
    }

    /*
     * Client driver for grog drunkenness (GROG_PLAN.md Phase 3.5), at the GUI stage (after the world +
     * shaders, before the HUD) so it's Iris-safe and the HUD stays crisp:
     * <ul>
     *   <li>the swimming drunk shader, eased off the synced intoxication level;</li>
     *   <li>a fade-to-black while you're {@link BannerboundAntiquity#PASS_OUT_UNTIL black-out} cold;</li>
     *   <li>a pounding, throbbing vignette for the morning-after {@link BannerboundAntiquity#HANGOVER_UNTIL
     *       hangover}.</li>
     * </ul>
     */
    /** Level at which the drunk visuals peak — L7, right before the L8 black-out, so 5→6→7 ramp up hard. */
    private static final float FULL_LEVEL = 7.0F;
    /** Smoothed visual intensity 0→1, eased toward the level each frame so it fades in/out cleanly. */
    private static float smooth = 0.0F;

    @SubscribeEvent
    static void onDrunkRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);

        // Hangover: pounding vignette, fading over the 30s (a touch stronger near the start).
        long hangoverUntil = mc.player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
        if (hangoverUntil > now) {
            float frac = Math.min(1.0F, (hangoverUntil - now) / (float) Intoxication.HANGOVER_TICKS);
            PoisonPostProcessor.renderHangover(0.55F + 0.45F * frac, now);
        }

        // Vomited on: green goo splattered across the screen, fading over the 10s (sober or not).
        long gooUntil = mc.player.getData(BannerboundAntiquity.VOMIT_OVERLAY_UNTIL.get());
        if (gooUntil > now) {
            // Seed off the deadline: constant for this splat, different for the next → unique blob layout.
            float seed = (gooUntil % 9973L) * 0.0137F;
            PoisonPostProcessor.renderGoo((gooUntil - now) / (float) Intoxication.VOMIT_OVERLAY_TICKS, seed, now);
        }

        // Drunk swim, eased so a sip fades it in and sobering fades it out.
        int level = mc.player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        // Jumble a growing fraction of glyphs once drunk (text goes weird) — set for the font mixin.
        DrunkText.setChance(level < 4 ? 0.0F : Math.min(0.55F, 0.11F * (level - 3)));
        float target = Math.min(1.0F, level / FULL_LEVEL);
        smooth += (target - smooth) * 0.06F;
        if (smooth > 0.002F) {
            PoisonPostProcessor.renderDrunk(smooth, now);
        } else {
            smooth = 0.0F;
        }

        // Black-out: heavy eyelids droop shut (curare-style), slowly — not a snap to black. Two black
        // bars close from top + bottom to the centre; fully shut while out cold, then open as you come to.
        long passOutUntil = mc.player.getData(BannerboundAntiquity.PASS_OUT_UNTIL.get());
        if (passOutUntil > now) {
            float remaining = passOutUntil - now;
            float elapsed = Intoxication.PASS_OUT_TICKS - remaining;
            float closeIn = Math.min(1.0F, elapsed / 35.0F);    // ~1.7s for the eyes to droop shut
            float openOut = Math.min(1.0F, remaining / 28.0F);  // ~1.4s for them to crack back open
            float cover = Math.max(0.0F, Math.min(closeIn, openOut));
            // Heavy-lidded flutter as you fight it (only while not yet fully shut).
            cover = Math.min(1.0F, cover + 0.05F * (float) Math.sin(now * 0.5F) * (1.0F - cover));
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            int lid = (int) (cover * h * 0.5F);
            if (lid > 0) {
                GuiGraphics g = event.getGuiGraphics();
                g.fill(0, 0, w, lid, 0xFF000000);
                g.fill(0, h - lid, w, h, 0xFF000000);
            }
        }
    }

    /*
     * Lays a curare-UNCONSCIOUS entity flat on the ground (passed out). One {@link RenderLivingEvent.Pre}
     * handler covers players, citizens and animals alike — it tips the whole model 90° about its forward
     * axis before the renderer draws the body, so it works generically without per-model code. Reads the
     * synced curare deadlines, so no Core import is needed (citizens included).
     */
    @SubscribeEvent
    static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!Poisons.isCurareUnconscious(entity, entity.level().getGameTime())) {
            return;
        }
        // Roll the model 90° onto its SIDE about its own facing axis, pivoting at the feet — works for
        // bipeds (fall on side) AND quadrupeds (knocked over), unlike a forward face-plant which would
        // stand a four-legged mob on its nose. Lift by half the body width first so the side-lying body
        // rests on the ground instead of half-sinking. (Tune the 0.5 if a model floats or clips.)
        PoseStack ps = event.getPoseStack();
        double yaw = Math.toRadians(entity.yBodyRot);
        float fx = (float) -Math.sin(yaw);
        float fz = (float) Math.cos(yaw);
        ps.translate(0.0F, entity.getBbWidth() * 0.5F, 0.0F);
        ps.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(90.0), fx, 0.0F, fz));
    }

    /*
     * The kidnap rope — a fibre ribbon from a dragger to whatever curare-unconscious creature they're
     * towing. Mirrors {@link RopeRenderEvents} exactly, but reads the synced {@code DRAGGED_BY} claim
     * (the dragger's entity id) off any rendered {@link LivingEntity} (players, citizens, animals), so it
     * draws the same plant-fibre green ribbon ({@link RopeRenderer#drawRibbon}) the herder/spear use.
     */
    /** Rope attaches at ~60% of each entity's height (chest-ish), like a held lead. */
    private static final double TIE_FRACTION = 0.6;

    @SubscribeEvent
    public static void onCurareRopeRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean drewAny = false;

        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity victim)) {
                continue;
            }
            Integer id = victim.getExistingDataOrNull(BannerboundAntiquity.DRAGGED_BY.get());
            if (id == null || id == 0) {
                continue;
            }
            Entity dragger = level.getEntity(id);
            if (dragger == null || !dragger.isAlive()) {
                continue;
            }

            Vec3 a = lerpPos(victim, partial);
            Vec3 h = lerpPos(dragger, partial);
            double ay = a.y + victim.getBbHeight() * TIE_FRACTION;
            double hy = h.y + dragger.getBbHeight() * TIE_FRACTION;
            float dx = (float) (h.x - a.x);
            float dy = (float) (hy - ay);
            float dz = (float) (h.z - a.z);
            double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
            float sag = (float) Mth.clamp(0.12 * horiz, 0.08, 0.5);

            pose.pushPose();
            pose.translate(a.x - cam.x, ay - cam.y, a.z - cam.z);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(a.x, ay, a.z));
            RopeRenderer.drawRibbon(pose, buffers, light, dx, dy, dz, sag);
            pose.popPose();
            drewAny = true;
        }
        if (drewAny) {
            buffers.endBatch(RenderType.leash());
        }
    }

    private static Vec3 lerpPos(Entity e, float partial) {
        return new Vec3(Mth.lerp(partial, e.xOld, e.getX()),
            Mth.lerp(partial, e.yOld, e.getY()),
            Mth.lerp(partial, e.zOld, e.getZ()));
    }
}
