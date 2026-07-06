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

/**
 * Client game-bus hub for every status effect the local player can suffer: poison, blunt-crit stun,
 * grog drunkenness (GROG_PLAN.md Phase 3.5) and curare. Poison senses: crossfades four per-stage
 * ambience drones per poison, drives the wolfsbane world-muffle + steady FOV pull, belladonna's
 * false vanilla-mob sounds (the mobs are stripped from Antiquity but their sound events remain) and
 * phantom figures, oleander's accelerating heartbeat off the synced cardiac-clock deadline, and the
 * antidote heal-flash (fires whenever any poison clears). Every screen post-process here (poison
 * vision, stun blur, drunk swim, pass-out eyelids, hangover vignette, vomit goo, DrunkText glyph
 * jumble) runs at RenderGuiEvent.Pre -- after the world and shaders, before the HUD -- so it is
 * Iris-safe and the HUD stays crisp. Also swaps the vanilla death screen for a poison-flavoured one
 * (the lethal hit leaves the synced PoisonState set; dying of something else while poisoned still
 * shows it -- intended, and a handy fast way to test the screens), and reveals a laced food's
 * poison on its tooltip ONLY to the poisoner and whoever shares their settlement (a pure display
 * gate over the stack's PoisonedFoodData; the enemy sees a clean item). Belladonna's phantoms are
 * pure client-side hallucinations (no entity, no collision, no sound of their own): faint
 * translucent player models wearing phantom.png at the edge of vision that dissolve when looked at,
 * spawning at stage^2 x 0.004 per tick so stage 1 is rare (~1 per 12s) and stage 4 near-constant.
 * Curare rendering lays an unconscious body flat generically for players, citizens and animals: it
 * rolls the model 90 degrees onto its SIDE about its own facing axis, pivoting at the feet and
 * lifting by half the body width so it rests on the ground -- a forward face-plant would stand
 * quadrupeds on their nose (tune the 0.5 lift if a model floats or clips). The kidnap drag rope
 * mirrors RopeRenderEvents: it reads the synced DRAGGED_BY entity id off any rendered LivingEntity
 * (so no Core import is needed) and draws the same plant-fibre ribbon the herder/spear use via
 * RopeRenderer.drawRibbon, tied at ~60% of each body's height.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class StatusClientEffects {

    private static final PoisonAmbienceSound[] LAYERS = new PoisonAmbienceSound[4];
    private static PoisonType ambiencePoison = null;

    private static final SoundEvent[] FALSE_SOUNDS = {
        SoundEvents.ZOMBIE_AMBIENT, SoundEvents.SKELETON_AMBIENT, SoundEvents.SPIDER_AMBIENT,
        SoundEvents.ENDERMAN_AMBIENT, SoundEvents.CREEPER_PRIMED, SoundEvents.WITHER_SKELETON_AMBIENT,
        SoundEvents.HUSK_AMBIENT, SoundEvents.AMBIENT_CAVE.value(), SoundEvents.ZOMBIE_VILLAGER_AMBIENT
    };

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

        if (lastStage > 0 && stage == 0 && mc.level != null) {
            healFlashStart = mc.level.getGameTime();
        }
        lastStage = stage;

        driveAmbience(mc, type, stage);

        int wolfsbaneStage = (type == PoisonType.WOLFSBANE) ? stage : 0;
        SoundMuffle.set(wolfsbaneStage <= 0 ? 1.0F : Math.max(0.36F, 1.0F - wolfsbaneStage * 0.16F));

        if (type == PoisonType.BELLADONNA && stage > 0) {
            RandomSource rng = player.getRandom();
            if (rng.nextFloat() < stage * 0.004F) {
                playFalseSound(mc, player, rng);
            }
            StatusClientEffects.tick(player, stage, rng);
        } else {
            StatusClientEffects.clear();
        }

        if (type == PoisonType.OLEANDER && stage > 0) {
            PoisonHeartbeat.tick(oleanderFraction(player, player.level().getGameTime()));
        } else {
            PoisonHeartbeat.reset();
        }
    }

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
        // playLocalSound: only this client hears it (it's in your head).
        mc.level.playLocalSound(x, y, z, sound, SoundSource.HOSTILE,
            0.5F + rng.nextFloat() * 0.4F, 0.7F + rng.nextFloat() * 0.5F, false);
    }

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
        // A sound instance cannot switch OGGs: on a poison change fade the old drones out and rebuild.
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
                snd.setTarget(0.0F);
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
            return;
        }
        float narrow = 1.0F - Math.min(0.12F, s.stage() * 0.035F);
        event.setNewFovModifier(event.getNewFovModifier() * narrow);
    }

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
        if (!me.getUUID().equals(poisoner) && !ClientPopulationState.isMember(poisoner)) {
            return;
        }
        PoisonType type = PoisonType.fromId(laced.poisonId());
        Component name = Component.translatable("poison.bannerboundantiquity."
            + (type != null ? type.id() : laced.poisonId()));
        event.getToolTip().add(Component.translatable(
            "bannerboundantiquity.poisoned_food.tooltip", name, laced.dose())
            .withStyle(ChatFormatting.DARK_GREEN));
    }

    private static final class Phantom {
        final double x;
        final double y;
        final double z;
        final long spawnTick;
        final long expireTick;
        long lookFadeStart = -1L;

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
    private static final double LOOK_VANISH_DOT = 0.86; // cos of the ~31 deg look half-angle that starts a dissolve
    private static final long LOOK_FADE_TICKS = 4L;
    private static final float MAX_ALPHA = 105.0F;
    private static final ResourceLocation PHANTOM_TEX =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "textures/entity/phantom.png");

    private static PlayerModel<LivingEntity> model;

    public static void clear() {
        PHANTOMS.clear();
    }

    public static void tick(LocalPlayer player, int stage, RandomSource rng) {
        long now = player.level().getGameTime();
        Vec3 eye = player.getEyePosition();
        Vec3 lookFlat = horiz(player.getViewVector(1.0F));
        PHANTOMS.removeIf(p -> now >= p.expireTick
            || (p.lookFadeStart >= 0 && now - p.lookFadeStart >= LOOK_FADE_TICKS));
        for (Phantom p : PHANTOMS) {
            if (p.lookFadeStart < 0) {
                Vec3 to = horiz(new Vec3(p.x - eye.x, 0.0, p.z - eye.z));
                if (lookFlat.dot(to) > LOOK_VANISH_DOT) {
                    p.lookFadeStart = now;
                }
            }
        }
        if (PHANTOMS.size() < MAX && rng.nextFloat() < stage * stage * 0.004F) {
            spawn(player, rng, now);
        }
    }

    private static void spawn(LocalPlayer player, RandomSource rng, long now) {
        double side = rng.nextBoolean() ? 1.0 : -1.0;
        double offset = Math.toRadians(48.0 + rng.nextDouble() * 34.0);
        double yaw = Math.toRadians(player.getYRot()) + side * offset;
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        double dist = 6.0 + rng.nextDouble() * 7.0;
        double x = player.getX() + dx * dist;
        double z = player.getZ() + dz * dist;
        double y = player.getY();
        long life = 40L + rng.nextInt(60);
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
                model.young = false; // EntityModel.young defaults TRUE -> baby proportions; force adult
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
            float faceYaw = (float) Math.toDegrees(Math.atan2(p.x - player.getX(), player.getZ() - p.z));
            pose.pushPose();
            pose.translate(p.x - camPos.x, p.y - camPos.y, p.z - camPos.z);
            pose.mulPose(Axis.YP.rotationDegrees(faceYaw));
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(0.0F, -1.501F, 0.0F); // vanilla entity-model ground offset (magic 1.501)
            int color = (alpha << 24) | 0xFFFFFF;
            m.renderToBuffer(pose, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
            pose.popPose();
        }
        buf.endBatch(rt);
    }

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
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float remaining = until - now;
        if (remaining <= 0.0F || remaining > BluntStun.STUN_TICKS) {
            return;
        }
        float elapsed = BluntStun.STUN_TICKS - remaining;
        float p = elapsed / BluntStun.STUN_TICKS;
        float envelope = p < 0.2F ? p / 0.2F : 1.0F - (p - 0.2F) / 0.8F;
        PoisonPostProcessor.renderStun(envelope, now);
    }

    private static final float FULL_LEVEL = 7.0F;
    private static float smooth = 0.0F;

    @SubscribeEvent
    static void onDrunkRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);

        long hangoverUntil = mc.player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
        if (hangoverUntil > now) {
            float frac = Math.min(1.0F, (hangoverUntil - now) / (float) Intoxication.HANGOVER_TICKS);
            PoisonPostProcessor.renderHangover(0.55F + 0.45F * frac, now);
        }

        long gooUntil = mc.player.getData(BannerboundAntiquity.VOMIT_OVERLAY_UNTIL.get());
        if (gooUntil > now) {
            float seed = (gooUntil % 9973L) * 0.0137F;
            PoisonPostProcessor.renderGoo((gooUntil - now) / (float) Intoxication.VOMIT_OVERLAY_TICKS, seed, now);
        }

        int level = mc.player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        DrunkText.setChance(level < 4 ? 0.0F : Math.min(0.55F, 0.11F * (level - 3)));
        float target = Math.min(1.0F, level / FULL_LEVEL);
        smooth += (target - smooth) * 0.06F;
        if (smooth > 0.002F) {
            PoisonPostProcessor.renderDrunk(smooth, now);
        } else {
            smooth = 0.0F;
        }

        long passOutUntil = mc.player.getData(BannerboundAntiquity.PASS_OUT_UNTIL.get());
        if (passOutUntil > now) {
            float remaining = passOutUntil - now;
            float elapsed = Intoxication.PASS_OUT_TICKS - remaining;
            float closeIn = Math.min(1.0F, elapsed / 35.0F);
            float openOut = Math.min(1.0F, remaining / 28.0F);
            float cover = Math.max(0.0F, Math.min(closeIn, openOut));
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

    @SubscribeEvent
    static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!Poisons.isCurareUnconscious(entity, entity.level().getGameTime())) {
            return;
        }
        PoseStack ps = event.getPoseStack();
        double yaw = Math.toRadians(entity.yBodyRot);
        float fx = (float) -Math.sin(yaw);
        float fz = (float) Math.cos(yaw);
        ps.translate(0.0F, entity.getBbWidth() * 0.5F, 0.0F);
        ps.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(90.0), fx, 0.0F, fz));
    }

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
