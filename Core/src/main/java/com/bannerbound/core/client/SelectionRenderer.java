package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.entity.HerderWorkGoal;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client renderer that draws Create-style colored wireframes and overlays while the local player
 * holds one of the survey rods. Subscribed to RenderLevelStageEvent and only acts at
 * AFTER_TRANSLUCENT_BLOCKS. Rendering uses vanilla RenderType.lines() + LevelRenderer.renderLineBox
 * so lines are depth-tested (occluded by solids, same look as F3+B hitboxes).
 *
 * What it draws, gated by which rod is held:
 *  - WORKSTATION (Foreman's Rod): one settlement-colored AABB wireframe per active BlockSelection.
 *  - HOME (Housing Orders rod) / WORKSHOP (Workshop Rod): while the matching rod is held, EVERY
 *    home/workshop renders - the rod-bound one at full alpha, the rest dimmed - so the player can
 *    survey them all at a glance. Homes and workshops have no anchor block, so their id rides in the
 *    BlockSelection homeId slot and groups are keyed by it. Each group draws as a merged silhouette
 *    plus a face-culled translucent tint in the settlement color.
 *  - HERDER pens: the enclosure is flood-filled client-side (PenEnclosure.scan) into a pen
 *    silhouette; the pen under the crosshair previews in green so the player can frame it before marking.
 *  - Stockpile debug: green interior silhouette, blue boxes on connected containers, red box on the
 *    exact scan-failure spot.
 *  - Tentative previews: a white A->B box for each rod that has Point A set but not yet Point B,
 *    updated every frame against the crosshair block.
 *  - Workshop overview labels: a floating billboard (icon + name + status + appeal) above each
 *    workshop, distance-culled at LABEL_RANGE.
 *
 * Silhouette pass: draws only edges on the boundary of the merged volume. Each unique edge (deduped
 * via EdgeKey) is classified by the 2x2 of cubes around it; edges interior to a flat face (2 cubes
 * side-by-side) are skipped, which kills the per-block grid look. Tint pass: emits only faces whose
 * neighbour cube is unmarked, so shared interior faces never stack and multiply the overlay into
 * dark bands, and each quad is outset a hair to avoid z-fighting the block surface it hugs.
 *
 * Render-order invariant: getBuffer(debugQuads()) ends the in-flight lines build, so ALL lines()
 * work must finish and endBatch(lines()) must run BEFORE the tint pass, or the next lines call
 * crashes with "Not building!". Anti-cheat: a workshop whose craft the settlement has not researched
 * renders as "Unknown Workshop" with a "?" icon so the overview label can't reveal or operate the
 * craft early; a player-set custom name still shows because it reveals nothing.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class SelectionRenderer {
    private static final float ALPHA_BASE = 0.85f;
    private static final float ALPHA_SWING = 0.15f;
    private static final float FLICKER_HZ = 1.4f;
    private static final float PREVIEW_FLICKER_HZ = 2.4f;
    private static final float TINT_ALPHA = 0.18f;

    private SelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        ItemStack foremanRod = findHeldRod(player, BannerboundCore.FOREMANS_ROD.get());
        ItemStack homeRod = findHeldRod(player, BannerboundCore.HOUSING_ORDERS.get());

        java.util.UUID boundHomeId = homeRod != null
            ? com.bannerbound.core.item.HousingOrdersItem.boundHomeId(homeRod) : null;
        BlockPos foremanPreviewA = foremanRod != null
            ? foremanRod.get(BannerboundCore.FOREMAN_POINT_A.get()) : null;
        BlockPos homePreviewA = homeRod != null
            ? homeRod.get(BannerboundCore.MARKER_POINT_A.get()) : null;
        BlockPos foremanPreviewB = previewBHover(mc, foremanPreviewA);
        BlockPos homePreviewB = previewBHover(mc, homePreviewA);

        ItemStack workshopRod = findHeldRod(player, BannerboundCore.WORKSHOP_ROD.get());
        java.util.UUID boundWorkshopId = workshopRod != null
            ? com.bannerbound.core.item.WorkshopRodItem.boundWorkshopId(workshopRod) : null;
        BlockPos workshopPreviewA = workshopRod != null
            ? workshopRod.get(BannerboundCore.MARKER_POINT_A.get()) : null;
        BlockPos workshopPreviewB = previewBHover(mc, workshopPreviewA);

        String foremanType = foremanRod != null
            ? foremanRod.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get()) : null;
        boolean herderRod = foremanRod != null && HerderWorkGoal.SELECTION_TYPE.equals(foremanType);
        boolean hasWorkstationCommitted = foremanRod != null && !ClientSelectionState.getAll().isEmpty();
        boolean hasHomeSelections = false;
        if (homeRod != null) {
            for (BlockSelection s : ClientSelectionState.getAll()) {
                if (s.kind() == BlockSelection.Kind.HOME) { hasHomeSelections = true; break; }
            }
        }
        boolean stockpileDebug = StockpileDebugState.isActive(mc.level.getGameTime());
        if (!hasHomeSelections && !hasWorkstationCommitted
            && foremanPreviewA == null && homePreviewA == null
            && workshopRod == null
            && !stockpileDebug && !herderRod) return;

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = mc.level.getGameTime() + partial;
        float alpha = ALPHA_BASE + ALPHA_SWING * (float) Math.sin(time * (Math.PI * 2.0 / 20.0) * FLICKER_HZ);

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        var consumer = buffer.getBuffer(RenderType.lines());

        if (foremanRod != null) {
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
                if (sel.completed()) continue;
                if (HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
                if (foremanType != null && !foremanType.isEmpty()
                    && !foremanType.equals(sel.workstationType())) continue;
                int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float g = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                LevelRenderer.renderLineBox(pose, consumer,
                    sel.minX(), sel.minY(), sel.minZ(),
                    sel.maxX() + 1, sel.maxY() + 1, sel.maxZ() + 1,
                    r, g, b, alpha);
            }
        }

        java.util.List<java.util.Map.Entry<java.util.Set<BlockPos>, float[]>> homeRenders =
            new java.util.ArrayList<>();
        if (homeRod != null) {
            java.util.Map<java.util.UUID, java.util.Set<BlockPos>> homeGroups = new java.util.HashMap<>();
            java.util.Map<java.util.UUID, float[]> homeColors = new java.util.HashMap<>();
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.HOME) continue;
                java.util.UUID hid = sel.homeId();
                homeColors.computeIfAbsent(hid, k -> {
                    int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                    float a = hid.equals(boundHomeId) ? alpha : alpha * 0.45f;
                    return new float[]{
                        ((rgb >> 16) & 0xFF) / 255f,
                        ((rgb >> 8) & 0xFF) / 255f,
                        (rgb & 0xFF) / 255f,
                        a
                    };
                });
                java.util.Set<BlockPos> marked =
                    homeGroups.computeIfAbsent(hid, k -> new java.util.HashSet<>());
                for (int x = sel.minX(); x <= sel.maxX(); x++) {
                    for (int y = sel.minY(); y <= sel.maxY(); y++) {
                        for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (marked.contains(p)) continue;
                            if (mc.level.getBlockState(p).isAir()) continue;
                            marked.add(p);
                        }
                    }
                }
            }
            for (java.util.Map.Entry<java.util.UUID, java.util.Set<BlockPos>> e : homeGroups.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                float[] c = homeColors.get(e.getKey());
                drawSilhouette(pose, consumer, e.getValue(), c[0], c[1], c[2], c[3]);
                homeRenders.add(java.util.Map.entry(e.getValue(), c));
            }
        }

        java.util.Map<java.util.UUID, java.util.Set<BlockPos>> workshopGroups = new java.util.HashMap<>();
        if (workshopRod != null) {
            java.util.Map<java.util.UUID, float[]> workshopColors = new java.util.HashMap<>();
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.WORKSHOP) continue;
                java.util.UUID wid = sel.homeId();
                workshopColors.computeIfAbsent(wid, k -> {
                    int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                    float a = wid.equals(boundWorkshopId) ? alpha : alpha * 0.45f;
                    return new float[]{
                        ((rgb >> 16) & 0xFF) / 255f,
                        ((rgb >> 8) & 0xFF) / 255f,
                        (rgb & 0xFF) / 255f,
                        a
                    };
                });
                java.util.Set<BlockPos> marked =
                    workshopGroups.computeIfAbsent(wid, k -> new java.util.HashSet<>());
                for (int x = sel.minX(); x <= sel.maxX(); x++) {
                    for (int y = sel.minY(); y <= sel.maxY(); y++) {
                        for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (marked.contains(p)) continue;
                            if (mc.level.getBlockState(p).isAir()) continue;
                            marked.add(p);
                        }
                    }
                }
            }
            for (java.util.Map.Entry<java.util.UUID, java.util.Set<BlockPos>> e
                    : workshopGroups.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                float[] c = workshopColors.get(e.getKey());
                drawSilhouette(pose, consumer, e.getValue(), c[0], c[1], c[2], c[3]);
                homeRenders.add(java.util.Map.entry(e.getValue(), c));
            }
        }

        if (herderRod) {
            for (BlockSelection sel : ClientSelectionState.getAll()) {
                if (sel.kind() != BlockSelection.Kind.WORKSTATION || sel.completed()) continue;
                if (!HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
                PenEnclosure.Result pr = PenEnclosure.scan(mc.level,
                    new BlockPos(sel.minX(), sel.minY(), sel.minZ()));
                if (!pr.valid()) continue;
                int rgb = ClientIdentityState.primaryRgb(sel.colorIndex());
                float[] c = { ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha };
                drawSilhouette(pose, consumer, pr.interior(), c[0], c[1], c[2], c[3]);
                homeRenders.add(java.util.Map.entry(pr.interior(), c));
            }
            if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
                PenEnclosure.Result pr = PenEnclosure.scan(mc.level, bh.getBlockPos());
                if (pr.valid()) {
                    drawSilhouette(pose, consumer, pr.interior(), 0.2f, 1.0f, 0.2f, alpha);
                    homeRenders.add(java.util.Map.entry(pr.interior(), new float[]{0.2f, 1.0f, 0.2f, alpha}));
                }
            }
        }

        if (stockpileDebug) {
            java.util.Set<BlockPos> spInterior = StockpileDebugState.interior();
            if (!spInterior.isEmpty()) {
                drawSilhouette(pose, consumer, spInterior, 0.2f, 1.0f, 0.2f, alpha);
                homeRenders.add(java.util.Map.entry(spInterior, new float[]{0.2f, 1.0f, 0.2f, alpha}));
            }
            for (BlockPos c : StockpileDebugState.containers()) {
                LevelRenderer.renderLineBox(pose, consumer,
                    c.getX(), c.getY(), c.getZ(), c.getX() + 1, c.getY() + 1, c.getZ() + 1,
                    0.2f, 0.5f, 1.0f, alpha);
            }
            BlockPos spFail = StockpileDebugState.failPos();
            if (spFail != null) {
                LevelRenderer.renderLineBox(pose, consumer,
                    spFail.getX(), spFail.getY(), spFail.getZ(),
                    spFail.getX() + 1, spFail.getY() + 1, spFail.getZ() + 1,
                    1.0f, 0.15f, 0.15f, 1.0f);
            }
        }

        if (foremanRod != null && foremanPreviewA != null && foremanPreviewB != null) {
            drawPreviewBox(pose, consumer, foremanPreviewA, foremanPreviewB, time);
        }
        if (homeRod != null && homePreviewA != null && homePreviewB != null) {
            drawPreviewBox(pose, consumer, homePreviewA, homePreviewB, time);
        }
        if (workshopRod != null && workshopPreviewA != null && workshopPreviewB != null) {
            drawPreviewBox(pose, consumer, workshopPreviewA, workshopPreviewB, time);
        }

        // Must finish all lines() work before getBuffer(debugQuads()) below, or the next lines call crashes.
        buffer.endBatch(RenderType.lines());

        if (!homeRenders.isEmpty()) {
            var quads = buffer.getBuffer(RenderType.debugQuads());
            for (var e : homeRenders) {
                float[] c = e.getValue();
                drawCulledFaces(pose, quads, e.getKey(), c[0], c[1], c[2], TINT_ALPHA);
            }
            buffer.endBatch(RenderType.debugQuads());
        }

        if (workshopRod != null && !workshopGroups.isEmpty()) {
            for (java.util.Map.Entry<java.util.UUID, java.util.Set<BlockPos>> e
                    : workshopGroups.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    drawWorkshopLabel(pose, mc, camera, e.getKey(), e.getValue());
                }
            }
            mc.renderBuffers().bufferSource().endBatch();
        }

        pose.popPose();
    }

    private static void drawWorkshopLabel(PoseStack pose, Minecraft mc, Camera camera,
                                          java.util.UUID workshopId, java.util.Set<BlockPos> marked) {
        com.bannerbound.core.client.ClientWorkshopSummaries.Summary summary =
            com.bannerbound.core.client.ClientWorkshopSummaries.get(workshopId);
        if (summary == null) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        double cx = (minX + maxX + 1) / 2.0;
        double cy = maxY + 1.8;
        double cz = (minZ + maxZ + 1) / 2.0;
        if (camera.getPosition().distanceToSqr(cx, cy, cz) > LABEL_RANGE * LABEL_RANGE) return;

        com.bannerbound.core.api.settlement.Workshop.Status status =
            com.bannerbound.core.api.settlement.Workshop.Status
                .fromOrdinalOrDefault(summary.statusOrdinal());
        boolean valid = status == com.bannerbound.core.api.settlement.Workshop.Status.VALID;
        boolean operating = valid && summary.workerCount() > 0;
        boolean known = ClientResearchState.isWorkshopTypeKnown(summary.typeId());
        net.minecraft.network.chat.Component name = !summary.customName().isEmpty()
            ? net.minecraft.network.chat.Component.literal(summary.customName())
            : known
                ? net.minecraft.network.chat.Component.translatable(
                    com.bannerbound.core.api.workshop.WorkBlockRegistry.displayKey(summary.typeId()))
                : net.minecraft.network.chat.Component.translatable(
                    "bannerbound.workshop.type_unknown");
        net.minecraft.network.chat.Component statusLine = net.minecraft.network.chat.Component
            .translatable(operating ? "bannerbound.workshop.overview.operating"
                : valid ? "bannerbound.workshop.overview.needs_workers"
                : "bannerbound.workshop.overview.invalid",
                summary.workerCount(), summary.capacity())
            .withStyle(operating ? net.minecraft.ChatFormatting.GREEN
                : valid ? net.minecraft.ChatFormatting.YELLOW
                : net.minecraft.ChatFormatting.RED);
        net.minecraft.network.chat.Component appealLine = null;
        if (valid && summary.appealOrdinal() >= 0) {
            com.bannerbound.core.api.settlement.ChunkBeauty beauty =
                com.bannerbound.core.api.settlement.ChunkBeauty.byNetworkId(summary.appealOrdinal());
            appealLine = net.minecraft.network.chat.Component.translatable(beauty.langKey())
                .withStyle(beauty.tierIndex() > 0 ? net.minecraft.ChatFormatting.AQUA
                    : beauty.tierIndex() < 0 ? net.minecraft.ChatFormatting.RED
                    : net.minecraft.ChatFormatting.GRAY);
        }

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        net.minecraft.client.gui.Font font = mc.font;
        int bg = (int) (0.25F * 255.0F) << 24;

        pose.pushPose();
        pose.translate(cx, cy, cz);
        pose.mulPose(camera.rotation());
        pose.scale(0.025F, -0.025F, 0.025F);
        var matrix = pose.last().pose();
        font.drawInBatch(name, -font.width(name) / 2.0F, -12.0F, 0x20FFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            bg, LightTexture.FULL_BRIGHT);
        font.drawInBatch(name, -font.width(name) / 2.0F, -12.0F, 0xFFFFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.NORMAL,
            0, LightTexture.FULL_BRIGHT);
        font.drawInBatch(statusLine, -font.width(statusLine) / 2.0F, 0.0F, 0x20FFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
            bg, LightTexture.FULL_BRIGHT);
        font.drawInBatch(statusLine, -font.width(statusLine) / 2.0F, 0.0F, 0xFFFFFFFF, false,
            matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.NORMAL,
            0, LightTexture.FULL_BRIGHT);
        if (appealLine != null) {
            font.drawInBatch(appealLine, -font.width(appealLine) / 2.0F, 12.0F, 0x20FFFFFF, false,
                matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH,
                bg, LightTexture.FULL_BRIGHT);
            font.drawInBatch(appealLine, -font.width(appealLine) / 2.0F, 12.0F, 0xFFFFFFFF, false,
                matrix, buffer, net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                0, LightTexture.FULL_BRIGHT);
        }
        pose.popPose();

        net.minecraft.world.item.Item icon = known
            ? com.bannerbound.core.api.workshop.WorkBlockRegistry.iconForType(summary.typeId())
            : null;
        if (!known) {
            net.minecraft.network.chat.Component q = net.minecraft.network.chat.Component.literal("?");
            pose.pushPose();
            pose.translate(cx, cy + 0.6, cz);
            pose.mulPose(camera.rotation());
            pose.scale(0.05F, -0.05F, 0.05F);
            var qm = pose.last().pose();
            font.drawInBatch(q, -font.width(q) / 2.0F, -4.0F, 0xFFFFD040, false, qm, buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        if (icon != null) {
            pose.pushPose();
            pose.translate(cx, cy + 0.55, cz);
            pose.mulPose(camera.rotation());
            pose.scale(0.5F, 0.5F, 0.5F);
            mc.getItemRenderer().renderStatic(
                new net.minecraft.world.item.ItemStack(icon),
                net.minecraft.world.item.ItemDisplayContext.GUI,
                LightTexture.FULL_BRIGHT,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                pose, buffer, mc.level, 0);
            pose.popPose();
        }
    }

    private static final double LABEL_RANGE = 64.0;

    private static void drawSilhouette(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                        java.util.Set<BlockPos> marked,
                                        float r, float g, float b, float a) {
        java.util.Set<EdgeKey> seen = new java.util.HashSet<>();
        for (BlockPos cube : marked) {
            int cx = cube.getX(), cy = cube.getY(), cz = cube.getZ();
            for (int yi = 0; yi <= 1; yi++) for (int zi = 0; zi <= 1; zi++) {
                tryDrawEdge(0, cx, cy + yi, cz + zi, marked, seen, pose, consumer, r, g, b, a);
            }
            for (int xi = 0; xi <= 1; xi++) for (int zi = 0; zi <= 1; zi++) {
                tryDrawEdge(1, cx + xi, cy, cz + zi, marked, seen, pose, consumer, r, g, b, a);
            }
            for (int xi = 0; xi <= 1; xi++) for (int yi = 0; yi <= 1; yi++) {
                tryDrawEdge(2, cx + xi, cy + yi, cz, marked, seen, pose, consumer, r, g, b, a);
            }
        }
    }

    private record EdgeKey(int axis, int x, int y, int z) {}

    private static void tryDrawEdge(int axis, int x, int y, int z,
                                     java.util.Set<BlockPos> marked, java.util.Set<EdgeKey> seen,
                                     PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                     float r, float g, float b, float a) {
        EdgeKey key = new EdgeKey(axis, x, y, z);
        if (!seen.add(key)) return;
        boolean q00, q01, q10, q11;
        float x2, y2, z2;
        switch (axis) {
            case 0 -> {
                q00 = marked.contains(new BlockPos(x, y - 1, z - 1));
                q01 = marked.contains(new BlockPos(x, y - 1, z));
                q10 = marked.contains(new BlockPos(x, y,     z - 1));
                q11 = marked.contains(new BlockPos(x, y,     z));
                x2 = x + 1; y2 = y; z2 = z;
            }
            case 1 -> {
                q00 = marked.contains(new BlockPos(x - 1, y, z - 1));
                q01 = marked.contains(new BlockPos(x - 1, y, z));
                q10 = marked.contains(new BlockPos(x,     y, z - 1));
                q11 = marked.contains(new BlockPos(x,     y, z));
                x2 = x; y2 = y + 1; z2 = z;
            }
            case 2 -> {
                q00 = marked.contains(new BlockPos(x - 1, y - 1, z));
                q01 = marked.contains(new BlockPos(x - 1, y,     z));
                q10 = marked.contains(new BlockPos(x,     y - 1, z));
                q11 = marked.contains(new BlockPos(x,     y,     z));
                x2 = x; y2 = y; z2 = z + 1;
            }
            default -> { return; }
        }
        if (!isSilhouetteEdge(q00, q01, q10, q11)) return;
        drawLine(pose, consumer, x, y, z, x2, y2, z2, r, g, b, a);
    }

    private static boolean isSilhouetteEdge(boolean q00, boolean q01, boolean q10, boolean q11) {
        int count = (q00 ? 1 : 0) + (q01 ? 1 : 0) + (q10 ? 1 : 0) + (q11 ? 1 : 0);
        if (count == 0 || count == 4) return false;
        if (count == 2) {
            boolean sideBySide =
                (q00 && q01) || (q10 && q11) ||
                (q00 && q10) || (q01 && q11);
            return !sideBySide;
        }
        return true;
    }

    private static final float TINT_FACE_OUTSET = 0.005f;

    private static void drawCulledFaces(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer quads,
                                         java.util.Set<BlockPos> marked, float r, float g, float b, float a) {
        org.joml.Matrix4f mat = pose.last().pose();
        for (BlockPos cube : marked) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                if (marked.contains(cube.relative(dir))) continue;
                emitFace(mat, quads, cube, dir, r, g, b, a);
            }
        }
    }

    private static void emitFace(org.joml.Matrix4f mat, com.mojang.blaze3d.vertex.VertexConsumer quads,
                                  BlockPos pos, net.minecraft.core.Direction dir,
                                  float r, float g, float b, float a) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        float ox = dir.getStepX() * TINT_FACE_OUTSET;
        float oy = dir.getStepY() * TINT_FACE_OUTSET;
        float oz = dir.getStepZ() * TINT_FACE_OUTSET;
        float x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3;
        switch (dir) {
            case UP -> {
                x0 = x;     y0 = y + 1; z0 = z;
                x1 = x + 1; y1 = y + 1; z1 = z;
                x2 = x + 1; y2 = y + 1; z2 = z + 1;
                x3 = x;     y3 = y + 1; z3 = z + 1;
            }
            case DOWN -> {
                x0 = x;     y0 = y; z0 = z;
                x1 = x + 1; y1 = y; z1 = z;
                x2 = x + 1; y2 = y; z2 = z + 1;
                x3 = x;     y3 = y; z3 = z + 1;
            }
            case NORTH -> {
                x0 = x;     y0 = y;     z0 = z;
                x1 = x + 1; y1 = y;     z1 = z;
                x2 = x + 1; y2 = y + 1; z2 = z;
                x3 = x;     y3 = y + 1; z3 = z;
            }
            case SOUTH -> {
                x0 = x;     y0 = y;     z0 = z + 1;
                x1 = x + 1; y1 = y;     z1 = z + 1;
                x2 = x + 1; y2 = y + 1; z2 = z + 1;
                x3 = x;     y3 = y + 1; z3 = z + 1;
            }
            case WEST -> {
                x0 = x; y0 = y;     z0 = z;
                x1 = x; y1 = y + 1; z1 = z;
                x2 = x; y2 = y + 1; z2 = z + 1;
                x3 = x; y3 = y;     z3 = z + 1;
            }
            case EAST -> {
                x0 = x + 1; y0 = y;     z0 = z;
                x1 = x + 1; y1 = y + 1; z1 = z;
                x2 = x + 1; y2 = y + 1; z2 = z + 1;
                x3 = x + 1; y3 = y;     z3 = z + 1;
            }
            default -> { return; }
        }
        quads.addVertex(mat, x0 + ox, y0 + oy, z0 + oz).setColor(r, g, b, a);
        quads.addVertex(mat, x1 + ox, y1 + oy, z1 + oz).setColor(r, g, b, a);
        quads.addVertex(mat, x2 + ox, y2 + oy, z2 + oz).setColor(r, g, b, a);
        quads.addVertex(mat, x3 + ox, y3 + oy, z3 + oz).setColor(r, g, b, a);
    }

    private static void drawLine(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                  float x1, float y1, float z1, float x2, float y2, float z2,
                                  float r, float g, float b, float a) {
        org.joml.Matrix4f mat = pose.last().pose();
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0f) { nx /= len; ny /= len; nz /= len; }
        consumer.addVertex(mat, x1, y1, z1).setColor(r, g, b, a)
            .setNormal(pose.last(), nx, ny, nz);
        consumer.addVertex(mat, x2, y2, z2).setColor(r, g, b, a)
            .setNormal(pose.last(), nx, ny, nz);
    }

    private static void drawPreviewBox(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer consumer,
                                        BlockPos a, BlockPos b, double time) {
        float previewAlpha = ALPHA_BASE + ALPHA_SWING
            * (float) Math.sin(time * (Math.PI * 2.0 / 20.0) * PREVIEW_FLICKER_HZ);
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        LevelRenderer.renderLineBox(pose, consumer,
            minX, minY, minZ,
            maxX + 1, maxY + 1, maxZ + 1,
            1.0f, 1.0f, 1.0f, previewAlpha);
    }

    private static ItemStack findHeldRod(Player player, net.minecraft.world.item.Item item) {
        ItemStack main = player.getMainHandItem();
        if (main.is(item)) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(item)) return off;
        return null;
    }

    private static BlockPos previewBHover(Minecraft mc, BlockPos previewA) {
        if (previewA == null) return null;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhit)) return null;
        if (bhit.getType() != HitResult.Type.BLOCK) return null;
        return bhit.getBlockPos();
    }
}
