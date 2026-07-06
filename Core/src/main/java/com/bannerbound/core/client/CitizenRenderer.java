package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renderer for {@link CitizenEntity}. Two things vary per citizen:
 * <ul>
 *   <li><b>Body model</b> -- male citizens use the wide (Steve) player model, female citizens the
 *       slim (Alex) model, baked from PLAYER_SLIM. The active model is swapped into the reassignable
 *       {@code this.model} in {@link #render} before the super call; the armor layers keep their own
 *       models, so swapping the body does not disturb them.</li>
 *   <li><b>Texture</b> -- {@code textures/entity/citizen/<man|woman>_<era>_<NN>.png}, chosen from
 *       the citizen's gender, era, and stable variant seed. The number of {@code _NN} variants per
 *       (gender, era) is discovered by probing the resource manager (contiguous from 01, up to
 *       MAX_VARIANT_PROBE) and cached. The era is the settlement's <i>current</i> era, so citizens
 *       restyle as it advances. When no era/gender texture exists the renderer falls back to
 *       {@code citizen.png}.</li>
 * </ul>
 *
 * <p>Generic mob renderers omit several things PlayerRenderer does, so {@link #render} re-adds them
 * each frame against the shared/reused gender models: item-use arm poses (THROW_SPEAR while a held
 * item's use-anim is SPEAR, BOW_AND_ARROW while BOW -- the fisher/hunter windup) and the crouch-stalk
 * flag. Because the models are shared, both arm poses are reset every frame or stale poses leak
 * between citizens. Child citizens draw at CHILD_SCALE (0.65) scaled around the feet pivot so they
 * stand on the ground (0.65 stays clear of pathfinding-bbox trouble); only super.render is wrapped in
 * that push/pop so overhead draws stay in unscaled world space.
 *
 * <p>Overhead cues (speech bubble, red "!" BLOCKED marker, occasional angry/happy villager particles)
 * are drawn AFTER super.render in clean world space -- NOT as render layers, because layer iteration
 * in LivingEntityRenderer runs inside the model flip + translate, which mangles font orientation.
 * Particles are client-only and heavily probability-gated per frame so they read as the odd puff, not
 * a fog (very-unhappy/very-happy fire a little more often; children skipped; happy rates sit below the
 * angry ones so contentment stays the quieter cue).
 *
 * <p>{@link #CURRENT_RENDER} exposes the citizen being drawn to expansion held-item model wrappers
 * (which receive only an ItemDisplayContext, no entity) so they can key their pose on this NPC the way
 * the player wrapper keys on {@code Minecraft#player} (e.g. the Antiquity spear raise-flip). Render
 * thread only; set around super.render and cleared in a finally. This whole class is render thread only.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, HumanoidModel<CitizenEntity>> {
    public static CitizenEntity CURRENT_RENDER;

    private static final ResourceLocation FALLBACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/entity/citizen.png");
    private static final int MAX_VARIANT_PROBE = 16;

    private final HumanoidModel<CitizenEntity> wideModel;
    private final HumanoidModel<CitizenEntity> slimModel;
    private final Map<String, Integer> variantCountCache = new HashMap<>();

    public CitizenRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        this.wideModel = this.getModel();
        this.slimModel = new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM));
        this.addLayer(new HumanoidArmorLayer<>(this,
            new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()));
    }

    private static final float CHILD_SCALE = 0.65f;

    @Override
    public void render(CitizenEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // swap body model before super.render; armor layers keep their own models
        HumanoidModel<CitizenEntity> body = entity.getGender() == CitizenGender.FEMALE ? slimModel : wideModel;
        this.model = body;
        HumanoidModel.ArmPose usePose = HumanoidModel.ArmPose.EMPTY;
        boolean usedMainHand = true;
        if (entity.isUsingItem()) {
            net.minecraft.world.item.UseAnim anim = entity.getUseItem().getUseAnimation();
            if (anim == net.minecraft.world.item.UseAnim.SPEAR) {
                usePose = HumanoidModel.ArmPose.THROW_SPEAR;
            } else if (anim == net.minecraft.world.item.UseAnim.BOW) {
                usePose = HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
            if (usePose != HumanoidModel.ArmPose.EMPTY) {
                usedMainHand = entity.getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND;
            }
        }
        boolean rightIsMain = entity.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        boolean useRightArm = usedMainHand == rightIsMain;
        // reset both arms each frame: the gender models are shared/reused across citizens
        body.rightArmPose = useRightArm ? usePose : HumanoidModel.ArmPose.EMPTY;
        body.leftArmPose = useRightArm ? HumanoidModel.ArmPose.EMPTY : usePose;
        body.crouching = entity.isCrouching();
        CURRENT_RENDER = entity;
        try {
            if (entity.isChild()) {
                poseStack.pushPose();
                poseStack.scale(CHILD_SCALE, CHILD_SCALE, CHILD_SCALE);
                super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
                poseStack.popPose();
            } else {
                super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
            }
        } finally {
            CURRENT_RENDER = null;
        }
        // draw AFTER super.render: back in clean world space, before the model flip mangles font
        SpeechBubbleLayer.draw(entity, poseStack, buffer, packedLight);
        SpeechBubbleLayer.drawBlocked(entity, poseStack, buffer, packedLight);
        maybeEmitUnhappyParticles(entity);
        maybeEmitHappyParticles(entity);
    }

    private static final int UNHAPPY_THRESHOLD = 30;
    private static final int VERY_UNHAPPY_THRESHOLD = 15;
    private static final int HAPPY_THRESHOLD = 80;
    private static final int VERY_HAPPY_THRESHOLD = 95;

    private static void maybeEmitUnhappyParticles(CitizenEntity entity) {
        if (!entity.level().isClientSide || entity.isChild()) {
            return;
        }
        int happiness = entity.getHappiness();
        if (happiness > UNHAPPY_THRESHOLD) {
            return;
        }
        var random = entity.getRandom();
        float chance = happiness <= VERY_UNHAPPY_THRESHOLD ? 0.010f : 0.004f;
        if (random.nextFloat() >= chance) {
            return;
        }
        double jitter = 0.30;
        double x = entity.getX() + (random.nextDouble() - 0.5) * jitter;
        double z = entity.getZ() + (random.nextDouble() - 0.5) * jitter;
        double y = entity.getEyeY() + 0.5;
        entity.level().addParticle(
            net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER, x, y, z, 0.0, 0.0, 0.0);
    }

    private static void maybeEmitHappyParticles(CitizenEntity entity) {
        if (!entity.level().isClientSide || entity.isChild()) {
            return;
        }
        int happiness = entity.getHappiness();
        if (happiness < HAPPY_THRESHOLD) {
            return;
        }
        var random = entity.getRandom();
        float chance = happiness >= VERY_HAPPY_THRESHOLD ? 0.005f : 0.0025f;
        if (random.nextFloat() >= chance) {
            return;
        }
        double jitter = 0.30;
        double x = entity.getX() + (random.nextDouble() - 0.5) * jitter;
        double z = entity.getZ() + (random.nextDouble() - 0.5) * jitter;
        double y = entity.getEyeY() + 0.5;
        entity.level().addParticle(
            net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, x, y, z, 0.0, 0.0, 0.0);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        CitizenGender gender = entity.getGender();
        Era era = entity.getEra();
        String setKey = gender.texturePrefix() + "_" + era.key();
        int variantCount = variantCountCache.computeIfAbsent(setKey, this::probeVariantCount);
        if (variantCount <= 0) {
            return FALLBACK_TEXTURE;
        }
        int variant = Math.floorMod(entity.getTextureVariant(), variantCount) + 1;
        return textureFor(setKey, variant);
    }

    private int probeVariantCount(String setKey) {
        var resourceManager = Minecraft.getInstance().getResourceManager();
        int count = 0;
        for (int n = 1; n <= MAX_VARIANT_PROBE; n++) {
            if (resourceManager.getResource(textureFor(setKey, n)).isPresent()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static ResourceLocation textureFor(String setKey, int variant) {
        return ResourceLocation.fromNamespaceAndPath("bannerbound",
            String.format("textures/entity/citizen/%s_%02d.png", setKey, variant));
    }
}
