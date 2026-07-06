package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector3f;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Block entity renderer for the Mortar and Pestle. Draws the body from {@link MortarAndPestleModel},
 * a translucent tinted liquid-surface quad ({@link MortarLiquids}), the ingredient resting in the
 * bowl, and the pestle "Mix" animation; it stays a BER (not a JSON block model) precisely so the
 * pestle can animate. Body and liquid render in entity-model space: translate(0.5, 1.5, 0.5) +
 * scale(1, -1, -1) is the standard BER adapter for entity models baked head-up around a y=24
 * baseline, while the ingredient is drawn afterwards in plain block space (non-BlockItems rotate
 * 90 deg to lie flat in the bowl). Pestle animation priority: a live local minigame session
 * ({@link MortarGrindState}) drives the pestle from the player's grind angle and press depth;
 * otherwise a finished grind plays a flourish from the block entity's synced mix timer so nearby
 * players see it too. The liquid surface is a hand-built double-sided quad at the Liquid Holder
 * bone's top face (cube local x[-6,1], z[-1,6], visible face y=-1).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class MortarAndPestleRenderer implements BlockEntityRenderer<MortarAndPestleBlockEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/liquid_mortar_and_pestle.png");

    private final MortarAndPestleModel model;
    private final ItemRenderer itemRenderer;
    private final Vector3f animationCache = new Vector3f();

    public MortarAndPestleRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new MortarAndPestleModel(context.bakeLayer(MortarAndPestleModel.LAYER_LOCATION));
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(MortarAndPestleBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5, 1.5, 0.5);
        pose.scale(1.0F, -1.0F, -1.0F);

        model.root().getAllParts().forEach(ModelPart::resetPose);
        if (MortarGrindState.activeFor(be.getBlockPos())) {
            KeyframeAnimations.animate(model, MortarAndPestleAnimations.MIX,
                MortarGrindState.grindElapsedMs(), 1.0F, animationCache);
            model.pestle().y += MortarGrindState.pressDepth() * 1.2F;
        } else if (be.isMixing()) {
            float elapsedTicks = (MortarAndPestleBlockEntity.MIX_CYCLE_TICKS - be.getMixAnimTicks()) + partialTick;
            long elapsedMs = (long) (elapsedTicks * 50.0F);
            KeyframeAnimations.animate(model, MortarAndPestleAnimations.MIX, elapsedMs, 1.0F, animationCache);
        }

        model.renderBody(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light, overlay);

        MortarLiquids.Entry liquid = MortarLiquids.get(be.getLiquidId());
        if (liquid != null) {
            renderLiquid(pose, buffers, liquid, light);
        }
        pose.popPose();

        ItemStack ingredient = be.getIngredient();
        if (!ingredient.isEmpty()) {
            renderIngredient(pose, buffers, ingredient, light, be.getLevel());
        }
    }

    private void renderIngredient(PoseStack pose, MultiBufferSource buffers, ItemStack stack,
                                  int light, Level level) {
        pose.pushPose();
        pose.translate(0.5, 0.35, 0.5);
        pose.scale(0.34F, 0.34F, 0.34F);
        if (!(stack.getItem() instanceof BlockItem)) {
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        }
        itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
            pose, buffers, level, 0);
        pose.popPose();
    }

    private void renderLiquid(PoseStack pose, MultiBufferSource buffers, MortarLiquids.Entry liquid,
                              int light) {
        TextureAtlasSprite sprite = liquid.sprite();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS));

        pose.pushPose();
        model.master().translateAndRotate(pose);
        model.liquidHolder().translateAndRotate(pose);
        PoseStack.Pose p = pose.last();

        int color = liquid.tint();
        int overlay = OverlayTexture.NO_OVERLAY;
        // ModelPart.render divides cube pixel coords by 16 internally; a hand-built quad must scale them itself.
        float s = 1.0F / 16.0F;
        float y = -1.0F * s;
        float x0 = -6.0F * s, x1 = 1.0F * s, z0 = -1.0F * s, z1 = 6.0F * s;
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();

        vertex(vc, p, x0, y, z1, u0, v1, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x1, y, z1, u1, v1, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x1, y, z0, u1, v0, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x0, y, z0, u0, v0, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x0, y, z0, u0, v0, color, light, overlay, 0.0F, 1.0F, 0.0F);
        vertex(vc, p, x1, y, z0, u1, v0, color, light, overlay, 0.0F, 1.0F, 0.0F);
        vertex(vc, p, x1, y, z1, u1, v1, color, light, overlay, 0.0F, 1.0F, 0.0F);
        vertex(vc, p, x0, y, z1, u0, v1, color, light, overlay, 0.0F, 1.0F, 0.0F);

        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z,
                               float u, float v, int color, int light, int overlay,
                               float nx, float ny, float nz) {
        vc.addVertex(p, x, y, z)
            .setColor(color)
            .setUv(u, v)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(p, nx, ny, nz);
    }
}
