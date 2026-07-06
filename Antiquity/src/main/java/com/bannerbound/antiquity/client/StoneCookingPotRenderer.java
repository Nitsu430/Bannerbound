package com.bannerbound.antiquity.client;

import java.util.List;

import com.bannerbound.antiquity.block.StoneCookingPotBlock;
import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import org.joml.Matrix4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws what the stone cooking pot holds: the liquid surface plus the raw ingredients bobbing and
 * slowly turning as they float at the surface, until they cook down to stew (then they vanish).
 * The surface is one horizontal quad spanning the inner cavity wall-to-wall (model walls sit at
 * 1px/15px) whose height tracks the fill fraction (2px nearly empty up to 9px full), so a draining
 * stew visibly drops; it is tinted vanilla still-water blue for plain water, ripening toward the
 * would-be stew colour as it cooks, then the finished stew's tint. The pot body is the block's
 * JSON model; when ON_FIRE a deeper on-campfire variant sits the bowl above the flame, so the
 * liquid and floating items drop by the block entity's VISUAL_DROP to match the placed model and
 * block shape. Liquid uses the block's own light (like FermentationTroughRenderer/ClayTankRenderer)
 * so it doesn't glow under shaders.
 */
@OnlyIn(Dist.CLIENT)
public class StoneCookingPotRenderer implements BlockEntityRenderer<StoneCookingPotBlockEntity> {
    private static final ResourceLocation WATER_STILL =
        ResourceLocation.withDefaultNamespace("block/water_still");
    private static final int WATER_BLUE = 0x3F76E4;
    private static final float MIN = 1.0F / 16.0F;
    private static final float MAX = 15.0F / 16.0F;
    private static final float DROP = (float) StoneCookingPotBlockEntity.VISUAL_DROP;
    private static final float FLOAT_Y_BASE = 0.62F;

    private final ItemRenderer itemRenderer;

    public StoneCookingPotRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(StoneCookingPotBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null) return;

        float drop = be.getBlockState().getValue(StoneCookingPotBlock.ON_FIRE) ? DROP : 0.0F;
        renderLiquid(be, pose, buffers, drop);

        List<ItemStack> items = be.ingredients();
        if (items.isEmpty()) return;
        int n = items.size();
        float t = be.getLevel().getGameTime() + partialTick;
        float floatY = FLOAT_Y_BASE - drop;
        float radius = n <= 1 ? 0.0F : (n <= 4 ? 0.16F : 0.21F);
        float scale = n <= 4 ? 0.42F : 0.34F;

        for (int i = 0; i < n; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;

            double angle = (Math.PI * 2.0 / n) * i;
            float cx = 0.5F + (float) Math.cos(angle) * radius;
            float cz = 0.5F + (float) Math.sin(angle) * radius;
            float bob = (float) Math.sin(t * 0.12F + i * 1.9F) * 0.022F;
            float spin = t * 1.6F + i * 137.0F;

            pose.pushPose();
            pose.translate(cx, floatY + bob, cz);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.scale(scale, scale, scale);
            itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, be.getLevel(), i);
            pose.popPose();
        }
    }

    private void renderLiquid(StoneCookingPotBlockEntity be, PoseStack pose, MultiBufferSource buffers,
                              float drop) {
        float frac = be.fillFraction();
        if (frac <= 0.001F) return;

        int rgb = liquidColor(be);
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float y = (2.0F + frac * 7.0F) / 16.0F - drop;

        int packed = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos());
        TextureAtlasSprite sprite = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER_STILL);
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();

        Matrix4f mat = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.translucent());
        vertex(vc, mat, MIN, y, MAX, r, g, b, packed, u0, v1);
        vertex(vc, mat, MAX, y, MAX, r, g, b, packed, u1, v1);
        vertex(vc, mat, MAX, y, MIN, r, g, b, packed, u1, v0);
        vertex(vc, mat, MIN, y, MIN, r, g, b, packed, u0, v0);
    }

    private static int liquidColor(StoneCookingPotBlockEntity be) {
        if (be.hasStew()) return be.liquidTint();
        if (be.isCooking()) return lerpRgb(WATER_BLUE, be.previewTint(), be.cookFraction());
        return WATER_BLUE;
    }

    private static int lerpRgb(int a, int b, float tt) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = (int) (ar + (br - ar) * tt);
        int gg = (int) (ag + (bg - ag) * tt);
        int bl = (int) (ab + (bb - ab) * tt);
        return (rr << 16) | (gg << 8) | bl;
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, float x, float y, float z,
                               float r, float g, float b, int light, float u, float v) {
        vc.addVertex(mat, x, y, z)
            .setColor(r, g, b, 1.0F)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(0.0F, 1.0F, 0.0F);
    }
}
