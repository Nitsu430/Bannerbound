package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * Renders the Carpenter's Table's dynamic items: the low decorative log pile (counts live on the
 * tabletop readout, so the pile stays compact), the spinning translucent picker result with browse
 * arrows, and the saw-minigame animation. Everything is drawn at the master block's centre
 * (x/z = 0.5) - the blockstate rotation pivot, which stays on the tabletop for any facing and
 * matches the ghost click hitbox. All text (counts, names, the queue) is drawn separately by
 * {@link StationReadoutEvents} via a level-stage pass, because glyphs added inside a block-entity
 * renderer don't get flushed reliably. While CarpentrySawState is active the saw scene takes over
 * the tabletop and faces the player via a yaw computed geometrically from the camera->block vector
 * (atan2(dx, dz)) - captured player-yaw sign conventions kept landing 180 degrees off; this works
 * because the BER pose is world-axis-aligned, and the minigame freezes the view so the yaw is
 * effectively fixed. getRenderBoundingBox is grown up and outward because the picker/chips/saw
 * float well above the block and the model spans a second block toward its facing, so the default
 * 1x1 per-block cull box popped the floating content out of view at steep or oblique angles.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WoodworkingTableRenderer implements BlockEntityRenderer<WoodworkingTableBlockEntity> {
    // The model's tabletop top face sits at ~15px (= 0.94); items rest just above it.
    static final double TOP_Y = 0.96;
    static final double CELL = 0.16;
    static final int MAX_PILE_LAYERS = 2;
    private static final float BLOCK_SCALE = 0.16F;
    private static final float ITEM_SCALE = 0.26F;

    private final ItemRenderer itemRenderer;

    public WoodworkingTableRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(WoodworkingTableBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (CarpentrySawState.activeFor(be.getBlockPos())) {
            renderSaw(be, pose, buffers, light);
            return;
        }
        renderPile(be, partialTick, pose, buffers, light);
        renderGhost(be, partialTick, pose, buffers);
    }

    private void renderPile(WoodworkingTableBlockEntity be, float partialTick, PoseStack pose,
                            MultiBufferSource buffers, int light) {
        List<ItemStack> contents = be.getLogs();
        net.minecraft.core.Direction dir = be.getInsertDir();
        int slideCell = be.getLastSlideCell();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / WoodworkingTableBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.45F;
        }
        for (int cell = 0; cell < contents.size() && cell < 9; cell++) {
            ItemStack s = contents.get(cell);
            if (s.isEmpty()) continue;
            double ox = ((cell % 3) - 1) * CELL - 0.1;
            double oz = ((cell / 3) - 1) * CELL + 0.06;
            boolean isBlock = s.getItem() instanceof BlockItem;
            float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
            double layerH = isBlock ? scale + 0.005 : 0.022;
            int shown = Math.min(s.getCount(), MAX_PILE_LAYERS);
            for (int layer = 0; layer < shown; layer++) {
                boolean slides = cell == slideCell && layer == shown - 1;
                double sx = slides ? dir.getStepX() * slide : 0.0;
                double sz = slides ? dir.getStepZ() * slide : 0.0;
                pose.pushPose();
                pose.translate(0.5 + ox + sx, TOP_Y + layer * layerH, 0.5 + oz + sz);
                pose.scale(scale, scale, scale);
                pose.mulPose(Axis.YP.rotationDegrees(((cell * 61 + layer * 97) % 41) - 20));
                if (!isBlock) pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                itemRenderer.renderStatic(s, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                    pose, buffers, be.getLevel(), 0);
                pose.popPose();
            }
        }
    }

    private void renderGhost(WoodworkingTableBlockEntity be, float partialTick, PoseStack pose,
                             MultiBufferSource buffers) {
        ItemStack ghost = be.getGhostResult();
        if (ghost.isEmpty()) return;
        long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
        float t = time + partialTick;
        double y = be.ghostPreviewY();
        pose.pushPose();
        pose.translate(0.5, y + Math.sin(t * 0.1F) * 0.04F, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
        pose.scale(0.34F, 0.34F, 0.34F);
        itemRenderer.renderStatic(ghost, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, pose, GhostItemRenderer.wrap(buffers, 155), be.getLevel(), 0);
        pose.popPose();
        GhostArrowRenderer.render(be, pose, buffers);
    }

    private void renderSaw(WoodworkingTableBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        float progress = CarpentrySawState.progress;
        float pulse = CarpentrySawState.pulseAmount();
        ItemStack log = be.representativeBudgetLog();
        if (log.isEmpty()) log = new ItemStack(net.minecraft.world.level.block.Blocks.OAK_LOG);
        ItemStack saw = new ItemStack(com.bannerbound.antiquity.BannerboundAntiquity.BONE_SAW.get());

        pose.pushPose();
        pose.translate(0.5, TOP_Y + 0.16, 0.5);
        // sceneYaw is derived from the camera->block vector, never a captured player yaw (see class doc).
        pose.mulPose(Axis.YP.rotationDegrees(CarpentrySawState.sceneYaw));

        double slideX = -0.36 + progress * 0.62;
        pose.pushPose();
        pose.translate(slideX, 0.0, 0.0);
        pose.scale(0.68F, 0.68F, 0.68F);
        pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        itemRenderer.renderStatic(log, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
            pose, buffers, be.getLevel(), 0);
        pose.popPose();
        renderSawdust(be, pose, buffers, progress, pulse, light);

        pose.pushPose();
        pose.translate(0.0, 0.18 - pulse * 0.035, 0.24 - CarpentrySawState.sawY * 0.36);
        pose.scale(0.8F + pulse * 0.05F, 0.8F + pulse * 0.05F, 0.8F + pulse * 0.05F);
        pose.mulPose(Axis.XP.rotationDegrees(-45.0F));
        pose.mulPose(Axis.YP.rotationDegrees(-85.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(-0.0F + pulse * 5.0F));
        itemRenderer.renderStatic(saw, ItemDisplayContext.NONE, light,
            OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
        pose.popPose();

        pose.popPose();
    }

    private void renderSawdust(WoodworkingTableBlockEntity be, PoseStack pose, MultiBufferSource buffers,
                               float progress, float pulse, int light) {
        ItemStack shaving = new ItemStack(net.minecraft.world.level.block.Blocks.OAK_PLANKS);
        int chips = 3 + Math.min(8, (int) (progress * 10.0F)) + (pulse > 0.0F ? 2 : 0);
        for (int i = 0; i < chips; i++) {
            float scatter = ((i * 37) % 100) / 100.0F;
            double side = (i % 2 == 0 ? -1.0 : 1.0);
            pose.pushPose();
            pose.translate(side * (0.08 + scatter * 0.12), -0.22 + scatter * 0.03,
                0.16 + ((i * 17) % 7) * 0.025);
            pose.scale(0.045F, 0.018F, 0.045F);
            pose.mulPose(Axis.YP.rotationDegrees(i * 47.0F));
            itemRenderer.renderStatic(shaving, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    @Override
    public AABB getRenderBoundingBox(WoodworkingTableBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        return new AABB(pos).inflate(1.5, 0.0, 1.5).expandTowards(0.0, 4.0, 0.0);
    }

    static float snappedYawTowardCamera(BlockPos pos, Vec3 camera) {
        double dx = camera.x - (pos.getX() + 0.5);
        double dz = camera.z - (pos.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0.0 ? 90.0F : -90.0F;
        }
        return dz >= 0.0 ? 0.0F : 180.0F;
    }

    static double snappedOffsetX(float yaw, double distance) {
        if (yaw == 90.0F) return distance;
        if (yaw == -90.0F) return -distance;
        return 0.0;
    }

    static double snappedOffsetZ(float yaw, double distance) {
        if (yaw == 0.0F) return distance;
        if (yaw == 180.0F) return -distance;
        return 0.0;
    }
}
