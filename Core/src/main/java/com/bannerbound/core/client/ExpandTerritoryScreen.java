package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bannerbound.core.api.settlement.ChunkBeauty;
import com.bannerbound.core.network.ExpandTerritoryClaimPayload;
import com.bannerbound.core.network.OpenExpandTerritoryScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Birdseye chunk-claim picker: the world itself is the UI. On open the player's camera lifts to
 * a fixed altitude above the town hall (pitch=90, yaw snapped to a cardinal, north-up) and
 * BirdseyeClientEvents paints chunks as translucent slabs the player clicks directly on the
 * terrain. ClientBirdseyeState carries the claimed/foreign/purchasable sets to that renderer;
 * this screen owns the camera, input, and hover tooltip.
 * <p>
 * Three phases drive a cubic ease-in/out camera move (ANIM_DURATION_MS): ENTERING lifts from the
 * player's eye to cameraY with input suppressed; OPEN is fully interactive (hover tooltip,
 * click-to-claim, left-drag pan clamped so the camera can only sit over a CLAIMED chunk, so you
 * can never lose your settlement off-screen); EXITING reverses and only calls setScreen(parent)
 * once the animation finishes. Phase changes happen in tick() but the camera is re-anchored every
 * frame in render()/syncCameraPosition so motion is smooth at frame rate, not 20 Hz, and Y always
 * reads the player's LIVE eye Y (not a capture) so falling mid-animation can't clip the exit
 * through the ground.
 * <p>
 * {@code data} is swapped in place by refreshData after every successful claim (server re-pushes
 * the payload) so the view and camera survive claims; {@code parent} likewise survives a refresh
 * and returns to the Town Hall (null = close to world). Claims route through one packet: a direct
 * claim (anarchy / Chiefdom chief) is resource-gated client-side, but a Council vote or a Chiefdom
 * non-chief suggestion is NOT (see clickRequiresResources) - the server re-checks resources when
 * the vote passes / chief claims. The screen->world mapping (pickChunk / screenToWorldOffset /
 * chunkToScreen) is EMPIRICAL: at yaw=0/pitch=90 the MC projection is inverted on both axes and
 * each 90 step rotates that mapping, with picking suppressed mid-rotation. Subclasses (the wall
 * preview) reuse the camera but override allowChunkClaiming/drawsBaseHud so they neither claim nor
 * double-draw the base HUD.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ExpandTerritoryScreen extends Screen {
    protected enum Phase { ENTERING, OPEN, EXITING }

    private static final int RADIUS = 4;
    private static final int SLAB_OFFSET_ABOVE_PLAYER = 20;
    private static final int CAMERA_OFFSET_ABOVE_PLAYER = 100;
    private static final long ANIM_DURATION_MS = 500L;

    private OpenExpandTerritoryScreenPayload data;
    private final Set<Long> claimed = new HashSet<>();
    private final Set<Long> foreign = new HashSet<>();
    private final Set<Long> purchasable = new HashSet<>();
    private final TransientClickFeedback feedback = new TransientClickFeedback();
    private final Map<Long, ChunkBeauty> beautyByChunk = new HashMap<>();
    private final Map<Long, Integer> adjacencyByChunk = new HashMap<>();
    private final Map<Long, ChunkBeauty> effectiveByChunk = new HashMap<>();
    private final ChunkPos centerChunk;
    private Entity ghostCamera;
    private Entity originalCamera;
    protected int slabY;
    protected int cameraY;
    protected double dragCamX, dragCamZ;
    private double playerEyeY;

    private boolean prevHideGui;
    private int prevMenuBlur;
    private CloudStatus prevClouds;

    protected Phase phase = Phase.ENTERING;
    private long phaseStartMs;

    @org.jetbrains.annotations.Nullable
    private Screen parent;

    public void setParent(@org.jetbrains.annotations.Nullable Screen parent) {
        this.parent = parent;
    }

    private int yawTarget = 0;
    private float yawAnimStart = 0.0f;
    protected long yawAnimStartMs = -1L;
    private static final long YAW_ANIM_MS = 300L;

    public ExpandTerritoryScreen(OpenExpandTerritoryScreenPayload data) {
        super(Component.translatable("bannerbound.territory.title"));
        this.data = data;
        this.claimed.addAll(data.claimedChunks());
        this.foreign.addAll(data.foreignChunks());
        loadBeauty(data);
        this.centerChunk = new ChunkPos(data.townHallChunkPacked());
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                long packed = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz).toLong();
                if (claimed.contains(packed) || foreign.contains(packed)) continue;
                if (isAdjacentToOwned(centerChunk.x + dx, centerChunk.z + dz)) {
                    purchasable.add(packed);
                }
            }
        }
    }

    private boolean isAdjacentToOwned(int cx, int cz) {
        return claimed.contains(new ChunkPos(cx - 1, cz).toLong())
            || claimed.contains(new ChunkPos(cx + 1, cz).toLong())
            || claimed.contains(new ChunkPos(cx, cz - 1).toLong())
            || claimed.contains(new ChunkPos(cx, cz + 1).toLong());
    }

    @Override
    protected void init() {
        super.init();
        Minecraft mc = this.minecraft;
        if (mc == null || mc.level == null || mc.player == null || mc.options == null) return;

        int playerY = (int) Math.floor(mc.player.getY());
        this.playerEyeY = mc.player.getEyeY();
        this.slabY = playerY + SLAB_OFFSET_ABOVE_PLAYER;
        this.cameraY = playerY + CAMERA_OFFSET_ABOVE_PLAYER;
        this.dragCamX = centerChunk.getMinBlockX() + 8.5;
        this.dragCamZ = centerChunk.getMinBlockZ() + 8.5;

        prevHideGui = mc.options.hideGui;
        prevMenuBlur = mc.options.menuBackgroundBlurriness().get();
        prevClouds = mc.options.cloudStatus().get();
        mc.options.hideGui = true;
        mc.options.menuBackgroundBlurriness().set(0);
        mc.options.cloudStatus().set(CloudStatus.OFF);

        // Marker eye height 0 -> Camera.eyeHeight interp can't oscillate.
        Marker marker = new Marker(EntityType.MARKER, mc.level);
        marker.moveTo(dragCamX, playerEyeY, dragCamZ, 0.0F, 90.0F);
        marker.setNoGravity(true);
        this.ghostCamera = marker;
        this.originalCamera = mc.getCameraEntity();
        mc.setCameraEntity(marker);

        this.phase = Phase.ENTERING;
        this.phaseStartMs = System.currentTimeMillis();

        ClientBirdseyeState.enter(marker, originalCamera, claimed, foreign, purchasable,
            data.colorOrdinal(), data.townHallChunkPacked(), data.canAfford(),
            slabY, cameraY);
    }

    private float currentYaw() {
        if (yawAnimStartMs < 0) return (float) yawTarget;
        long elapsed = System.currentTimeMillis() - yawAnimStartMs;
        if (elapsed >= YAW_ANIM_MS) {
            yawAnimStartMs = -1L;
            yawAnimStart = (float) yawTarget;
            return (float) yawTarget;
        }
        double t = (double) elapsed / (double) YAW_ANIM_MS;
        double eased = easeInOut(t);
        return (float) (yawAnimStart + (yawTarget - yawAnimStart) * eased);
    }

    private void startRotation(int delta) {
        if (yawAnimStartMs >= 0) return;
        yawAnimStart = currentYaw();
        // Do NOT wrap yawTarget to [0,360): wrapping makes the lerp take the long way around.
        yawTarget += delta;
        yawAnimStartMs = System.currentTimeMillis();
    }

    protected int snappedYawQuadrant() {
        return ((yawTarget % 360) + 360) % 360;
    }

    private static double easeInOut(double t) {
        if (t < 0.0) return 0.0;
        if (t > 1.0) return 1.0;
        return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
    }

    private double phaseProgress() {
        if (phase == Phase.OPEN) return 1.0;
        long elapsed = System.currentTimeMillis() - phaseStartMs;
        return Math.min(1.0, (double) elapsed / (double) ANIM_DURATION_MS);
    }

    @Override
    public void tick() {
        super.tick();
        double progress = phaseProgress();
        if (phase == Phase.ENTERING && progress >= 1.0) {
            phase = Phase.OPEN;
        } else if (phase == Phase.EXITING && progress >= 1.0) {
            superClose();
        }
    }

    private double currentAnimatedCameraY() {
        Minecraft mc = this.minecraft;
        double liveEyeY = (mc != null && mc.player != null) ? mc.player.getEyeY() : playerEyeY;
        double progress = phaseProgress();
        double eased = easeInOut(progress);
        if (phase == Phase.ENTERING) return liveEyeY + (cameraY - liveEyeY) * eased;
        if (phase == Phase.EXITING) return cameraY + (liveEyeY - cameraY) * eased;
        return cameraY;
    }

    private void syncCameraPosition() {
        if (ghostCamera == null) return;
        double y = currentAnimatedCameraY();
        float yaw = currentYaw();
        ghostCamera.setPos(dragCamX, y, dragCamZ);
        ghostCamera.setYRot(yaw);
        ghostCamera.setXRot(90.0F);
        // Force old == new so Camera.setup's lerp is identity (no MC interp wobble on our anim).
        ghostCamera.xo = dragCamX;
        ghostCamera.yo = y;
        ghostCamera.zo = dragCamZ;
        ghostCamera.xOld = dragCamX;
        ghostCamera.yOld = y;
        ghostCamera.zOld = dragCamZ;
        ghostCamera.yRotO = yaw;
        ghostCamera.xRotO = 90.0F;
    }

    private void superClose() {
        Minecraft mc = this.minecraft;
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void removed() {
        Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.setCameraEntity(mc.player);
            if (mc.options != null) {
                mc.options.hideGui = prevHideGui;
                mc.options.menuBackgroundBlurriness().set(prevMenuBlur);
                if (prevClouds != null) mc.options.cloudStatus().set(prevClouds);
            }
        }
        ClientBirdseyeState.exit();
        super.removed();
    }

    @Override
    public void onClose() {
        if (phase == Phase.EXITING) return;
        phase = Phase.EXITING;
        phaseStartMs = System.currentTimeMillis();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        syncCameraPosition();

        Long hovered = null;
        Long tooltipChunk = null;
        if (phase == Phase.OPEN) {
            Long under = pickChunk(mouseX, mouseY);
            if (under != null && purchasable.contains(under)) {
                hovered = under;
                tooltipChunk = under;
            } else if (under != null && claimed.contains(under)) {
                tooltipChunk = under;
            }
        }
        ClientBirdseyeState.setHoveredChunk(hovered);

        if (phase == Phase.OPEN) {
            if (drawsBaseHud()) {
                g.drawCenteredString(this.font,
                    Component.translatable("bannerbound.territory.title")
                        .copy().withStyle(ChatFormatting.WHITE),
                    this.width / 2, 10, 0xFFFFFFFF);
                g.drawCenteredString(this.font,
                    Component.translatable("bannerbound.territory.expansions",
                        data.expansionsInEra(), data.maxExpansions()),
                    this.width / 2, 22, 0xFFAAAAAA);
                if (data.expansionsInEra() >= data.maxExpansions()) {
                    g.drawCenteredString(this.font,
                        Component.translatable("bannerbound.territory.cap_reached")
                            .withStyle(ChatFormatting.YELLOW),
                        this.width / 2, 34, 0xFFFFAA00);
                }
            }
            if (tooltipChunk != null) drawTooltip(g, mouseX, mouseY, tooltipChunk);
            drawChunkMarkers(g);
            if (drawsBaseHud()) {
                g.drawCenteredString(this.font,
                    Component.translatable("bannerbound.territory.esc_hint")
                        .withStyle(ChatFormatting.DARK_GRAY),
                    this.width / 2, this.height - 14, 0xFF666677);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
        feedback.render(g);
    }

    private void drawChunkMarkers(GuiGraphics g) {
        for (OpenExpandTerritoryScreenPayload.ChunkMarker m : data.votes()) {
            int[] xy = chunkToScreen(m.packedChunkPos());
            if (xy == null) continue;
            drawMarkerBadge(g, xy[0], xy[1], m.playerIds(),
                "(" + m.playerIds().size() + "/" + data.councilVoteThreshold() + ")",
                0xFFE0D055, 0xFFFFE066);
        }
        for (OpenExpandTerritoryScreenPayload.ChunkMarker m : data.suggestions()) {
            int[] xy = chunkToScreen(m.packedChunkPos());
            if (xy == null) continue;
            drawMarkerBadge(g, xy[0], xy[1], m.playerIds(),
                "+" + m.playerIds().size(),
                0xFF55E055, 0xFF66FF66);
        }
    }

    private static final int MAX_FACES = 3;

    private void drawMarkerBadge(GuiGraphics g, int cx, int cy, java.util.List<java.util.UUID> players,
                                  String text, int outlineColor, int textColor) {
        int textW = this.font.width(text);
        int headSize = 8;
        int padding = 2;
        int shown = Math.min(MAX_FACES, players.size());
        int boxW = textW + 4 + shown * (headSize + padding) + 4;
        int boxH = Math.max(headSize, this.font.lineHeight) + 4;
        int boxX = cx - boxW / 2;
        int boxY = cy - boxH / 2;
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xE0101010);
        g.renderOutline(boxX, boxY, boxW, boxH, outlineColor);
        int cursorX = boxX + 3;
        int textY = boxY + (boxH - this.font.lineHeight) / 2 + 1;
        g.drawString(this.font, text, cursorX, textY, textColor, false);
        cursorX += textW + 3;
        int faceY = boxY + (boxH - headSize) / 2;
        java.util.UUID localId = this.minecraft != null && this.minecraft.player != null
            ? this.minecraft.player.getUUID() : null;
        for (int i = 0; i < shown; i++) {
            java.util.UUID id = players.get(i);
            net.minecraft.resources.ResourceLocation skin = resolveSkin(id);
            if (skin != null) {
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(
                    g, new net.minecraft.client.resources.PlayerSkin(
                        skin, null, null, null,
                        net.minecraft.client.resources.PlayerSkin.Model.WIDE, true),
                    cursorX, faceY, headSize);
            } else {
                g.fill(cursorX, faceY, cursorX + headSize, faceY + headSize, outlineColor);
            }
            if (id.equals(localId)) {
                g.renderOutline(cursorX - 1, faceY - 1, headSize + 2, headSize + 2, 0xFFFFFFFF);
            }
            cursorX += headSize + padding;
        }
    }

    private net.minecraft.resources.ResourceLocation resolveSkin(java.util.UUID id) {
        if (this.minecraft == null || this.minecraft.getConnection() == null) return null;
        net.minecraft.client.multiplayer.PlayerInfo info =
            this.minecraft.getConnection().getPlayerInfo(id);
        return info == null ? null : info.getSkin().texture();
    }

    private void loadBeauty(OpenExpandTerritoryScreenPayload payload) {
        beautyByChunk.clear();
        adjacencyByChunk.clear();
        effectiveByChunk.clear();
        List<Long> chunks = payload.beautyChunks();
        List<Integer> tags = payload.beautyTagIds();
        List<Integer> adjacency = payload.beautyAdjacency();
        List<Integer> effective = payload.beautyEffective();
        for (int i = 0; i < chunks.size() && i < tags.size(); i++) {
            beautyByChunk.put(chunks.get(i), ChunkBeauty.byNetworkId(tags.get(i)));
            if (i < adjacency.size()) {
                adjacencyByChunk.put(chunks.get(i), adjacency.get(i));
            }
            if (i < effective.size()) {
                effectiveByChunk.put(chunks.get(i), ChunkBeauty.byNetworkId(effective.get(i)));
            }
        }
    }

    private void drawTooltip(GuiGraphics g, int mouseX, int mouseY, long hoveredPacked) {
        List<Component> lines = new ArrayList<>();
        if (data.biome() != null && !data.biome().isEmpty()) {
            lines.add(Component.translatable("bannerbound.territory.biome", data.biome())
                .withStyle(ChatFormatting.GRAY));
        }
        ChunkBeauty beauty = beautyByChunk.get(hoveredPacked);
        if (beauty != null) {
            lines.add(Component.translatable("bannerbound.territory.beauty",
                    Component.translatable(beauty.langKey()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));

            int adj = adjacencyByChunk.getOrDefault(hoveredPacked, 0);
            ChunkBeauty effective = effectiveByChunk.getOrDefault(hoveredPacked, beauty);
            String adjText = adj > 0 ? "+" + adj : String.valueOf(adj);
            lines.add(Component.translatable("bannerbound.territory.adjacency",
                    adjText, Component.translatable(effective.langKey()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));

            double cps = effective.culturePerSecond();
            ChatFormatting cultureColor = cps > 0 ? ChatFormatting.GREEN
                : cps < 0 ? ChatFormatting.RED : ChatFormatting.GRAY;
            lines.add(Component.translatable("bannerbound.territory.culture_line",
                    String.format("%+.2f", cps), Icons.culture())
                .withStyle(cultureColor));
        }

        if (claimed.contains(hoveredPacked)) {
            lines.add(Component.translatable("bannerbound.territory.owned")
                .withStyle(ChatFormatting.GREEN));
            g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
            return;
        }

        boolean popOk = data.currentPopulation() >= data.currentTierPopulation();
        lines.add(Component.translatable("bannerbound.territory.population_line",
            data.currentPopulation(), data.currentTierPopulation())
            .withStyle(popOk ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Component.translatable("bannerbound.territory.items_header")
            .withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < data.currentTierItemIds().size(); i++) {
            String id = data.currentTierItemIds().get(i);
            int need = data.currentTierItemCounts().get(i);
            int have = countInInventory(id);
            ChatFormatting col = have >= need ? ChatFormatting.GREEN : ChatFormatting.RED;
            lines.add(Component.literal("  " + have + " / " + need + "  " + idShortName(id))
                .withStyle(col));
        }
        lines.add(Component.literal(""));
        lines.add(data.canAfford()
            ? Component.translatable("bannerbound.territory.click_to_claim")
                .withStyle(ChatFormatting.AQUA)
            : Component.translatable("bannerbound.territory.cannot_afford")
                .withStyle(ChatFormatting.RED));

        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    protected Long pickChunk(int mouseX, int mouseY) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return null;
        if (yawAnimStartMs >= 0) return null;

        double fovDeg = mc.options.fov().get();
        double fovRad = Math.toRadians(fovDeg);
        double distToSlab = cameraY - slabY;
        double visibleH = 2.0 * distToSlab * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;

        double nx = (double) mouseX / (double) this.width - 0.5;
        double ny = (double) mouseY / (double) this.height - 0.5;
        double[] offset = screenToWorldOffset(nx * visibleW, ny * visibleH);
        double worldX = dragCamX + offset[0];
        double worldZ = dragCamZ + offset[1];
        int cx = (int) Math.floor(worldX / 16.0);
        int cz = (int) Math.floor(worldZ / 16.0);
        return new ChunkPos(cx, cz).toLong();
    }

    private int[] chunkToScreen(long packedChunk) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return null;
        if (yawAnimStartMs >= 0) return null;
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(packedChunk);
        double worldX = cp.x * 16.0 + 8.0;
        double worldZ = cp.z * 16.0 + 8.0;
        double dWX = worldX - dragCamX;
        double dWZ = worldZ - dragCamZ;
        double screenRight;
        double screenDown;
        switch (snappedYawQuadrant()) {
            case 0   -> { screenRight = -dWX; screenDown = -dWZ; }
            case 90  -> { screenRight = -dWZ; screenDown =  dWX; }
            case 180 -> { screenRight =  dWX; screenDown =  dWZ; }
            case 270 -> { screenRight =  dWZ; screenDown = -dWX; }
            default  -> { screenRight = -dWX; screenDown = -dWZ; }
        }
        double fovDeg = mc.options.fov().get();
        double fovRad = Math.toRadians(fovDeg);
        double distToSlab = cameraY - slabY;
        double visibleH = 2.0 * distToSlab * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;
        double nx = screenRight / visibleW;
        double ny = screenDown / visibleH;
        int px = (int) Math.round((nx + 0.5) * this.width);
        int py = (int) Math.round((ny + 0.5) * this.height);
        return new int[]{ px, py };
    }

    // EMPIRICAL: at yaw=0/pitch=90 both axes invert; 90/270 mirror the naive CW guess -> do not "correct" them.
    protected double[] screenToWorldOffset(double screenRightMag, double screenDownMag) {
        switch (snappedYawQuadrant()) {
            case 0:   return new double[]{ -screenRightMag, -screenDownMag };
            case 90:  return new double[]{ +screenDownMag,  -screenRightMag };
            case 180: return new double[]{ +screenRightMag, +screenDownMag };
            case 270: return new double[]{ -screenDownMag,  +screenRightMag };
            default:  return new double[]{ -screenRightMag, -screenDownMag };
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // GLFW key codes: A=65 rotates view +90 (right), D=68 rotates -90 (left).
        if (phase == Phase.OPEN) {
            if (keyCode == 65) { startRotation(+90); return true; }
            if (keyCode == 68) { startRotation(-90); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean clickRequiresResources() {
        int govOrd = com.bannerbound.core.client.ClientPopulationState.getGovernmentOrdinal();
        if (govOrd == com.bannerbound.core.api.settlement.Settlement.Government.COUNCIL.ordinal()) {
            return false;
        }
        if (govOrd == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM.ordinal()) {
            return com.bannerbound.core.client.ClientPopulationState.isPlayerChief();
        }
        return true;
    }

    protected boolean allowChunkClaiming() {
        return true;
    }

    protected boolean drawsBaseHud() {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (phase != Phase.OPEN) return false;
        if (button == 0 && allowChunkClaiming()) {
            Long packed = pickChunk((int) mouseX, (int) mouseY);
            if (packed != null
                    && purchasable.contains(packed)
                    && data.expansionsInEra() < data.maxExpansions()
                    && (clickRequiresResources() ? data.canAfford() : true)) {
                PacketDistributor.sendToServer(new ExpandTerritoryClaimPayload(packed));
                feedback.spawn((int) mouseX, (int) mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public void refreshData(OpenExpandTerritoryScreenPayload newData) {
        this.data = newData;
        this.claimed.clear();
        this.claimed.addAll(newData.claimedChunks());
        this.foreign.clear();
        this.foreign.addAll(newData.foreignChunks());
        loadBeauty(newData);
        this.purchasable.clear();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                long packed = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz).toLong();
                if (claimed.contains(packed) || foreign.contains(packed)) continue;
                if (isAdjacentToOwned(centerChunk.x + dx, centerChunk.z + dz)) {
                    purchasable.add(packed);
                }
            }
        }
        ClientBirdseyeState.enter(ghostCamera, originalCamera, claimed, foreign, purchasable,
            newData.colorOrdinal(), newData.townHallChunkPacked(), newData.canAfford(),
            slabY, cameraY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragDx, double dragDy) {
        if (phase != Phase.OPEN || button != 0 || yawAnimStartMs >= 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragDx, dragDy);
        }
        Minecraft mc = this.minecraft;
        if (mc == null || mc.options == null || mc.getWindow() == null) return false;
        double fovDeg = mc.options.fov().get();
        double fovRad = Math.toRadians(fovDeg);
        double distToSlab = cameraY - slabY;
        double visibleH = 2.0 * distToSlab * Math.tan(fovRad / 2.0);
        double aspect = (double) mc.getWindow().getGuiScaledWidth()
                      / (double) mc.getWindow().getGuiScaledHeight();
        double visibleW = visibleH * aspect;
        double screenRightMag = dragDx * (visibleW / this.width);
        double screenDownMag = dragDy * (visibleH / this.height);
        double[] offset = screenToWorldOffset(screenRightMag, screenDownMag);
        applyPanWithChunkClamp(-offset[0], -offset[1]);
        return true;
    }

    private void applyPanWithChunkClamp(double dx, double dz) {
        double tryX = dragCamX + dx;
        double tryZ = dragCamZ + dz;
        if (isClaimedChunkAt(tryX, tryZ)) {
            dragCamX = tryX;
            dragCamZ = tryZ;
            return;
        }
        if (isClaimedChunkAt(tryX, dragCamZ)) {
            dragCamX = tryX;
        }
        if (isClaimedChunkAt(dragCamX, tryZ)) {
            dragCamZ = tryZ;
        }
    }

    private boolean isClaimedChunkAt(double worldX, double worldZ) {
        long packed = new ChunkPos((int) Math.floor(worldX / 16.0),
                                    (int) Math.floor(worldZ / 16.0)).toLong();
        return claimed.contains(packed);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int countInInventory(String idStr) {
        if (this.minecraft == null || this.minecraft.player == null) return 0;
        Item item = lookupItem(idStr);
        if (item == Items.AIR) return 0;
        int total = 0;
        for (ItemStack s : this.minecraft.player.getInventory().items) {
            if (s.is(item)) total += s.getCount();
        }
        for (ItemStack s : this.minecraft.player.getInventory().offhand) {
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    private static Item lookupItem(String idStr) {
        ResourceLocation rl = ResourceLocation.tryParse(idStr);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(rl);
    }

    private static String idShortName(String idStr) {
        int colon = idStr.indexOf(':');
        return colon >= 0 ? idStr.substring(colon + 1) : idStr;
    }
}
