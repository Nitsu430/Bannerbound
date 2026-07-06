package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.FletchingStationBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * BER for the Fletching Station's placed-item pile: a 3x3 grid of stacks on the tabletop (one cell
 * per stack, multiples pile up as layers in that cell) plus, when the pile matches a recipe, the
 * base result floating and spinning above. Mirrors CraftingStoneRenderer; the station block itself
 * renders from its blockstate model. Items sit at TOP_Y = 0.875, i.e. just above the model's 13px
 * tabletop (13/16 + 1px), matching the crafting stone's +1px convention. A half-made in-progress
 * piece lies flat in the middle while a minigame session runs (the pile is consumed at commit, so
 * the grid is empty by then). Ghost recipe preview renders still-missing ingredients as low-alpha
 * silhouettes (via {@link GhostItemRenderer#wrap}) and the candidate's result ghosted where the
 * real preview will appear; a ghost can coexist with a solid result (locked recipe plus an
 * incidental exact match), in which case the solid result floats higher (1.8 vs 1.35).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class FletchingStationRenderer implements BlockEntityRenderer<FletchingStationBlockEntity> {
    private static final double TOP_Y = 0.875;
    private static final double CELL = 0.2;
    private static final float BLOCK_SCALE = 0.2F;
    private static final float ITEM_SCALE = 0.3F;

    private final ItemRenderer itemRenderer;

    public FletchingStationRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(FletchingStationBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        List<ItemStack> contents = be.getContents();
        Direction dir = be.getInsertDir();
        int slideCell = be.getLastSlideCell();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / FletchingStationBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.6F;
        }

        for (int cell = 0; cell < contents.size() && cell < 9; cell++) {
            ItemStack s = contents.get(cell);
            if (s.isEmpty()) continue;
            double ox = ((cell % 3) - 1) * CELL;
            double oz = ((cell / 3) - 1) * CELL;
            boolean isBlock = s.getItem() instanceof BlockItem;
            float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
            double layerH = isBlock ? scale + 0.005 : 0.022;
            for (int layer = 0; layer < s.getCount() && layer < 9; layer++) {
                boolean slides = cell == slideCell && layer == s.getCount() - 1;
                double sx = slides ? dir.getStepX() * slide : 0.0;
                double sz = slides ? dir.getStepZ() * slide : 0.0;
                pose.pushPose();
                pose.translate(0.5 + ox + sx, TOP_Y + layer * layerH, 0.5 + oz + sz);
                pose.scale(scale, scale, scale);
                pose.mulPose(Axis.YP.rotationDegrees(((cell * 61 + layer * 97) % 41) - 20));
                if (!isBlock) {
                    pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                }
                itemRenderer.renderStatic(s, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                    pose, buffers, be.getLevel(), 0);
                pose.popPose();
            }
        }

        ItemStack inProgress = be.getInProgress();
        if (!inProgress.isEmpty()) {
            pose.pushPose();
            pose.translate(0.5, TOP_Y, 0.5);
            pose.scale(0.5F, 0.5F, 0.5F);
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            itemRenderer.renderStatic(inProgress, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }

        ItemStack ghostResult = be.getGhostResult();
        ItemStack result = be.getResult();
        if (!ghostResult.isEmpty()) {
            MultiBufferSource ghostBuffers = GhostItemRenderer.wrap(buffers);
            int nextFree = contents.size();
            for (ItemStack g : be.getGhostIngredients()) {
                int cell = -1;
                int baseLayer = 0;
                for (int i = 0; i < contents.size(); i++) {
                    if (contents.get(i).is(g.getItem())) {
                        cell = i;
                        baseLayer = contents.get(i).getCount();
                        break;
                    }
                }
                if (cell < 0) {
                    if (nextFree >= 9) continue;
                    cell = nextFree++;
                }
                double ox = ((cell % 3) - 1) * CELL;
                double oz = ((cell / 3) - 1) * CELL;
                boolean isBlock = g.getItem() instanceof BlockItem;
                float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
                double layerH = isBlock ? scale + 0.005 : 0.022;
                for (int layer = baseLayer; layer < baseLayer + g.getCount() && layer < 9; layer++) {
                    pose.pushPose();
                    pose.translate(0.5 + ox, TOP_Y + layer * layerH, 0.5 + oz);
                    pose.scale(scale, scale, scale);
                    pose.mulPose(Axis.YP.rotationDegrees(((cell * 61 + layer * 97) % 41) - 20));
                    if (!isBlock) {
                        pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                    }
                    itemRenderer.renderStatic(g, ItemDisplayContext.NONE, light,
                        OverlayTexture.NO_OVERLAY, pose, ghostBuffers, be.getLevel(), 0);
                    pose.popPose();
                }
            }
            long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
            float t = time + partialTick;
            pose.pushPose();
            pose.translate(0.5, 1.35 + (float) Math.sin(t * 0.1F) * 0.04F, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
            pose.scale(0.5F, 0.5F, 0.5F);
            itemRenderer.renderStatic(ghostResult, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, pose, ghostBuffers, be.getLevel(), 0);
            pose.popPose();
            GhostArrowRenderer.render(be, pose, buffers);
        }

        if (!result.isEmpty()) {
            double resultY = ghostResult.isEmpty() ? 1.35 : 1.8;
            long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
            float t = time + partialTick;
            float spin = t * 3.0F;
            float bob = (float) Math.sin(t * 0.1F) * 0.04F;
            pose.pushPose();
            pose.translate(0.5, resultY + bob, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.scale(0.5F, 0.5F, 0.5F);
            itemRenderer.renderStatic(result, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }
}
