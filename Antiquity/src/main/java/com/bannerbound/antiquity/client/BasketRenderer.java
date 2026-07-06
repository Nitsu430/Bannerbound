package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.BasketBlock;
import com.bannerbound.antiquity.block.entity.BasketBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Block entity renderer for the Basket. The basket body is a normal JSON block model; this renderer
 * only adds the contents of the first slot, drawn sitting on top: a block item shows as a mini 3D
 * block, a regular item as a flat sprite lying in the basket. The display anchors mirror the "Block"
 * (8x8x8 cube, centre y ~= 6px) and "Item" (flat quad near y = 1px) elements from the Blockbench
 * model; the cavity centre is traced from the model floor (x 2->14, z 5->12), giving x = 0.5 and
 * z = 8.5px/16 = 0.53125. The pose is first spun to match the basket's FACING (mirroring the
 * blockstate "y" spin: north=0 / east=90 / south=180 / west=270) so the contents stay centred in the
 * cavity and a flat item stays aligned with the basket's long axis at every rotation -- without it
 * the item sits in NORTH-space while the body rotates, drifting off-centre and lying diagonally
 * across a sideways basket.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class BasketRenderer implements BlockEntityRenderer<BasketBlockEntity> {
    private final ItemRenderer itemRenderer;

    public BasketRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(BasketBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        ItemStack stack = be.getDisplayStack();
        if (stack.isEmpty()) {
            return;
        }

        pose.pushPose();

        float blockstateY = switch (be.getBlockState().getValue(BasketBlock.FACING)) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
        pose.translate(0.5, 0.0, 0.5);
        // blockstate "y":deg == Axis.YP.rotationDegrees(-deg); must stay in step with BasketBlock.rotateY
        pose.mulPose(Axis.YP.rotationDegrees(-blockstateY));
        pose.translate(-0.5, 0.0, -0.5);

        // renderStatic applies its own translate(-0.5,-0.5,-0.5); these transforms only place that centre
        if (stack.getItem() instanceof BlockItem) {
            pose.translate(0.5, 0.375, 0.53125);
            pose.scale(0.5F, 0.5F, 0.5F);
        } else {
            pose.translate(0.5, 0.13, 0.53125);
            pose.scale(0.5F, 0.5F, 0.5F);
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        }
        itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
            pose, buffers, be.getLevel(), 0);
        pose.popPose();
    }
}
