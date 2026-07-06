package com.bannerbound.antiquity.client;

import java.util.List;

import com.bannerbound.antiquity.block.entity.CrucibleBlockEntity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
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

import org.joml.Matrix4f;

/**
 * Draws what a placed crucible holds: the raw charge items piled in the bowl (laid flat, each with
 * a deterministic scatter rotation so the pile does not z-fight), or - once molten - a glowing
 * full-bright tinted liquid quad. The bowl itself is the block's own model; this only draws the
 * contents. MIN/MAX are the bowl's interior bounds derived from the block model (a box spanning
 * 4..12 horizontally and 0..6 vertically), with the liquid surface at 5.5/16.
 */
@OnlyIn(Dist.CLIENT)
public class CrucibleRenderer implements BlockEntityRenderer<CrucibleBlockEntity> {
    private static final float MIN = 5.0F / 16.0F;
    private static final float MAX = 11.0F / 16.0F;

    private final ItemRenderer itemRenderer;

    public CrucibleRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(CrucibleBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        CrucibleContents c = be.contents();
        if (c.molten()) {
            renderLiquid(c, pose, buffers, light);
            return;
        }
        List<ItemStack> charge = c.charge();
        for (int i = 0; i < charge.size() && i < 8; i++) {
            ItemStack s = charge.get(i);
            if (s.isEmpty()) continue;
            pose.pushPose();
            pose.translate(0.5, 0.16 + i * 0.03, 0.5);
            pose.scale(0.34F, 0.34F, 0.34F);
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(((i * 73) % 41) - 20));
            itemRenderer.renderStatic(s, ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    private void renderLiquid(CrucibleContents c, PoseStack pose, MultiBufferSource buffers, int light) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "block/crucible/molten_metal"));
        int rgb = c.tintColor();
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        Matrix4f mat = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.translucent());
        float y = 5.5F / 16.0F;
        vertex(vc, mat, MIN, y, MIN, r, g, b, sprite.getU0(), sprite.getV0(), light);
        vertex(vc, mat, MIN, y, MAX, r, g, b, sprite.getU0(), sprite.getV1(), light);
        vertex(vc, mat, MAX, y, MAX, r, g, b, sprite.getU1(), sprite.getV1(), light);
        vertex(vc, mat, MAX, y, MIN, r, g, b, sprite.getU1(), sprite.getV0(), light);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, float x, float y, float z,
                               float r, float g, float b, float u, float v, int light) {
        vc.addVertex(mat, x, y, z).setColor(r, g, b, 1.0F).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0, 1, 0);
    }
}
