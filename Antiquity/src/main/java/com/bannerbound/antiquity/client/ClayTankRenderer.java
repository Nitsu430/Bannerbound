package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.level.block.Blocks;

import org.joml.Matrix4f;

/**
 * Draws the liquid surface inside a clay tank pillar -- one upward-facing tinted quad (blue for
 * water, pale for the curing liquid) at the fill height, textured with the vanilla still-water
 * sprite and lit with the block's local light for a steady look. Only the controller cell
 * (PART == 0) carries the block entity, so this renders the whole pillar's liquid from the bottom;
 * the tank walls are the block's own baked model. INSET (1.1/16) tucks the quad just inside the
 * 1px-thick walls (interior 1..15), and a near-empty tank's surface is lifted to a minimum 1.5/16
 * so it still shows a film. shouldRenderOffScreen is true because the controller can be just
 * off-screen while its tall pillar is not.
 */
public class ClayTankRenderer implements BlockEntityRenderer<ClayTankBlockEntity> {
    private static final float INSET = 1.1F / 16.0F;

    public ClayTankRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(ClayTankBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        ClayTankBlockEntity.LiquidType liquid = be.getLiquid();
        if (liquid == ClayTankBlockEntity.LiquidType.EMPTY || be.getBuckets() <= 0) {
            return;
        }
        int height = be.pillarHeight();
        float surfaceY = be.fillFraction() * height;
        if (surfaceY <= 0.02F) return;
        surfaceY = Math.max(surfaceY, 1.5F / 16.0F);

        ModelManager models = Minecraft.getInstance().getModelManager();
        TextureAtlasSprite sprite = models.getBlockModelShaper()
            .getParticleIcon(Blocks.WATER.defaultBlockState());

        int color = liquid.color();
        float a = (color >>> 24) / 255.0F;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;

        int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(be.getLevel(),
            be.getBlockPos());

        VertexConsumer vc = buffer.getBuffer(RenderType.translucent());
        Matrix4f mat = pose.last().pose();
        float min = INSET;
        float max = 1.0F - INSET;
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        quad(vc, mat, min, surfaceY, min, max, surfaceY, max, r, g, b, a, light, u0, u1, v0, v1);
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, float x0, float y, float z0,
                             float x1, float yMax, float z1, float r, float g, float b, float a,
                             int light, float u0, float u1, float v0, float v1) {
        vertex(vc, mat, x0, y, z0, r, g, b, a, light, u0, v0);
        vertex(vc, mat, x0, y, z1, r, g, b, a, light, u0, v1);
        vertex(vc, mat, x1, y, z1, r, g, b, a, light, u1, v1);
        vertex(vc, mat, x1, y, z0, r, g, b, a, light, u1, v0);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, float x, float y, float z,
                               float r, float g, float b, float a, int light, float u, float v) {
        vc.addVertex(mat, x, y, z)
            .setColor(r, g, b, a)
            .setUv(u, v)
            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(0.0F, 1.0F, 0.0F);
    }

    @Override
    public boolean shouldRenderOffScreen(ClayTankBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}
