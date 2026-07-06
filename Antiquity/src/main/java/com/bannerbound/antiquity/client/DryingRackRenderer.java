package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.block.entity.DryingRackBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import org.joml.Matrix4f;

/**
 * Draws the items hung on a Drying Rack - a small flat square decal per occupied spot,
 * cross-fading from the input to its dried result as it dries (input alpha 1-p, result alpha p,
 * driven by {@link DryingRackBlockEntity#progress(int)}; the result plane is nudged +0.002 in z so
 * the two never z-fight). The frame itself is a normal block model; only the hanging items are
 * drawn here, double-sided at the line plane. Geometry is authored in the model's local space with
 * facing=north as the baseline, then rotated to match the static frame's blockstate y-rotation
 * (north=0, east=90, south=180, west=270). Constants: Y_TOP hangs decals just under the line bar,
 * Z=0.5 is where the line sits, SIZE keeps the sprite a fixed square (~2x the slot, never
 * stretched), and QUARTER_TURNS rotates the sprite 90 degrees left per turn (0 = upright). Spot
 * spread along local X depends on the block's {@link ChestType} - a connected half spans into its
 * neighbour; the lineStart/lineEnd bounds mirror the rack geometry models.
 */
public class DryingRackRenderer implements BlockEntityRenderer<DryingRackBlockEntity> {
    private static final float Y_TOP = 1.12F;
    private static final float Z = 0.5F;
    private static final float SIZE = 0.34F;
    private static final int QUARTER_TURNS = 1;

    public DryingRackRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(DryingRackBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (be.isEmpty()) return;

        Direction facing = be.getBlockState().getBlock() instanceof HorizontalDirectionalBlock
            ? be.getBlockState().getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
        ChestType type = be.getBlockState().hasProperty(BlockStateProperties.CHEST_TYPE)
            ? be.getBlockState().getValue(BlockStateProperties.CHEST_TYPE) : ChestType.SINGLE;

        float yRot = switch (facing) {
            case EAST -> 90F;
            case SOUTH -> 180F;
            case WEST -> 270F;
            default -> 0F;
        };

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-yRot));
        pose.translate(-0.5, -0.5, -0.5);
        Matrix4f mat = pose.last().pose();

        float lineStart = switch (type) {
            case LEFT -> 2F / 16F;
            case RIGHT -> 0F / 16F;
            default -> 2F / 16F;
        };
        float lineEnd = switch (type) {
            case LEFT -> 16F / 16F;
            case RIGHT -> 14F / 16F;
            default -> 14F / 16F;
        };
        float step = (lineEnd - lineStart) / DryingRackBlockEntity.SLOTS;
        float half = SIZE * 0.5F;

        for (int i = 0; i < DryingRackBlockEntity.SLOTS; i++) {
            ItemStack input = be.input(i);
            if (input.isEmpty()) continue;
            float xc = lineStart + (i + 0.5F) * step;
            float p = be.progress(i);
            decal(input, 1.0F - p, xc, half, Z, mat, buffer, packedLight);
            ItemStack result = be.result(i);
            if (!result.isEmpty()) {
                decal(result, p, xc, half, Z + 0.002F, mat, buffer, packedLight);
            }
        }
        pose.popPose();
    }

    private void decal(ItemStack stack, float alpha, float xc, float half, float z, Matrix4f mat,
                       MultiBufferSource buffer, int light) {
        if (stack.isEmpty() || alpha <= 0.01F) return;
        BakedModel model = Minecraft.getInstance().getItemRenderer()
            .getModel(stack, Minecraft.getInstance().level, null, 0);
        TextureAtlasSprite sprite = model.getParticleIcon();
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        VertexConsumer vc = buffer.getBuffer(RenderType.translucent());
        face(vc, mat, light, alpha, xc, half, z, u0, u1, v0, v1, false);
        face(vc, mat, light, alpha, xc, half, z, u0, u1, v0, v1, true);
    }

    private static void face(VertexConsumer vc, Matrix4f mat, int light, float a, float xc, float half,
                             float z, float u0, float u1, float v0, float v1, boolean back) {
        float x0 = xc - half, x1 = xc + half;
        float y0 = Y_TOP - 2F * half, y1 = Y_TOP;
        // UV corners MUST stay in BL,BR,TR,TL order; each QUARTER_TURN shifts them 90 deg left
        float[][] uv = { {u0, v1}, {u1, v1}, {u1, v0}, {u0, v0} };
        int r = ((QUARTER_TURNS % 4) + 4) % 4;
        float[] bl = uv[r], br = uv[(r + 1) % 4], tr = uv[(r + 2) % 4], tl = uv[(r + 3) % 4];
        if (!back) {
            v(vc, mat, x0, y0, z, bl[0], bl[1], light, a);
            v(vc, mat, x1, y0, z, br[0], br[1], light, a);
            v(vc, mat, x1, y1, z, tr[0], tr[1], light, a);
            v(vc, mat, x0, y1, z, tl[0], tl[1], light, a);
        } else {
            v(vc, mat, x0, y0, z, bl[0], bl[1], light, a);
            v(vc, mat, x0, y1, z, tl[0], tl[1], light, a);
            v(vc, mat, x1, y1, z, tr[0], tr[1], light, a);
            v(vc, mat, x1, y0, z, br[0], br[1], light, a);
        }
    }

    private static void v(VertexConsumer vc, Matrix4f mat, float x, float y, float z,
                          float u, float vTex, int light, float a) {
        vc.addVertex(mat, x, y, z)
            .setColor(1.0F, 1.0F, 1.0F, a)
            .setUv(u, vTex)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(0.0F, 0.0F, 1.0F);
    }
}
