package com.bannerbound.antiquity.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.craft.Carves;
import com.bannerbound.antiquity.network.CarveCommitPayload;
import com.bannerbound.core.client.UnknownItemHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.bannerbound.antiquity.rope.RopeTies;

/**
 * Client-only ghost preview for block carving (see {@link Carves}): while the local player aims at a
 * carveable block with the right tool, the target cell(s) are hidden CLIENT-side by setting them to air
 * (the supported relight/remesh path, the same idiom as RopeTies' coil preview) and the carve result is
 * drawn in their place as a low-alpha silhouette with a pulsing green voxel-shape outline. The server is
 * never told the block changed; the authoritative carve still runs server-side.
 *
 * <p>Commit: the hidden block cannot be ray-picked, so the use-key press is intercepted here (mirroring
 * GhostRecipeClientEvents), the vanilla use is cancelled so nothing behind the hidden block reacts, and
 * the anchor is sent via {@link CarveCommitPayload}; the server replays and validates the carve, so a
 * rejected commit simply leaves the world unchanged (no stuck ghost).
 *
 * <p>Acquisition uses hysteresis: hiding the looked-at block makes the crosshair ray pass through it, so
 * re-resolving against that air every tick would drop and re-acquire the preview forever (flicker). Once
 * anchored, the preview is KEPT while the player still looks through the anchor cell (within KEEP_REACH)
 * with the same tool, and dropped when the aim leaves the cell, the tool changes, or a blanked cell stops
 * being air (carve committed / world changed). DEBOUNCE_TICKS of steady aim are required before blanking
 * so a crosshair sweep across many carveable blocks does not churn section re-meshes. Locked
 * (unknown-item) carves are never previewed to avoid spoilers, and cells under the player's feet are
 * never blanked.
 *
 * <p>Rendering routes renderSingleBlock through a buffer source that forces the translucent block sheet
 * and scales vertex alpha to GHOST_ALPHA (~39%, matching the workstation ghost silhouettes) -- the same
 * trick as Core's WallGhostRenderer. Block-entity-rendered blocks (e.g. the bloomery) show their base
 * model only, since renderSingleBlock never invokes their BlockEntityRenderer.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class CarveClientEvents {

    private CarveClientEvents() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        BlockPos anchor = CarveClientEvents.activeAnchor();
        if (anchor == null) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(new CarveCommitPayload(anchor));
        CarveClientEvents.clearForCommit();
    }

    private static final int GHOST_ALPHA = 100;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Map<BlockPos, BlockState> ghosts = CarveClientEvents.ghosts();
        if (ghosts.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        GhostBufferSource ghostBuffer = new GhostBufferSource(buffer);
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());

        float t = (float) mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float outlineAlpha = 0.55F + 0.30F * Mth.sin(t * 0.2F);

        for (Map.Entry<BlockPos, BlockState> e : ghosts.entrySet()) {
            BlockPos p = e.getKey();
            BlockState state = e.getValue();
            pose.pushPose();
            pose.translate(p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z);
            mc.getBlockRenderer().renderSingleBlock(state, pose, ghostBuffer,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();

            // Outline boxes take cam-relative coords against the base (un-translated) pose.
            double dx = p.getX() - cam.x;
            double dy = p.getY() - cam.y;
            double dz = p.getZ() - cam.z;
            outlineShape(mc.level, p, state).forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                LevelRenderer.renderLineBox(pose, lines,
                    dx + minX, dy + minY, dz + minZ, dx + maxX, dy + maxY, dz + maxZ,
                    0.32F, 0.96F, 0.42F, outlineAlpha));
        }
        buffer.endBatch(Sheets.translucentCullBlockSheet());
        buffer.endBatch(RenderType.lines());
    }

    private static VoxelShape outlineShape(Level level, BlockPos pos, BlockState state) {
        try {
            VoxelShape shape = state.getShape(level, pos);
            return shape.isEmpty() ? Shapes.block() : shape;
        } catch (RuntimeException ignored) {
            return Shapes.block();
        }
    }

    private record GhostBufferSource(MultiBufferSource delegate) implements MultiBufferSource {
        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return new GhostVertexConsumer(delegate.getBuffer(Sheets.translucentCullBlockSheet()));
        }
    }

    private record GhostVertexConsumer(VertexConsumer delegate) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha * GHOST_ALPHA / 255);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    private static final int DEBOUNCE_TICKS = 2;
    private static final double KEEP_REACH = 6.0;

    private static Map<BlockPos, BlockState> ghosts = Map.of();
    private static final Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
    private static @Nullable BlockPos anchor;
    private static @Nullable Item anchorTool;

    private static @Nullable String pendingKey;
    private static int pendingAge;

    public static Map<BlockPos, BlockState> ghosts() {
        return ghosts;
    }

    public static @Nullable BlockPos activeAnchor() {
        return anchor;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || mc.screen != null || player.isSecondaryUseActive()) {
            clear(level); // sneak means "place the held block" -> never preview then
            return;
        }

        if (anchor != null) {
            if (stillValid(player, level)) {
                return;
            }
            clear(level);
            return;
        }

        acquire(mc, level, player);
    }

    private static void acquire(Minecraft mc, Level level, LocalPlayer player) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            resetPending();
            return;
        }
        ItemStack held = player.getMainHandItem();
        Carves.Result candidate = Carves.resolve(level, hit.getBlockPos(), player, held);
        if (candidate == null
                || !UnknownItemHelper.isKnown(candidate.gateItem())
                || standingOn(player, candidate)) {
            resetPending();
            return;
        }
        String key = signature(candidate);
        if (!key.equals(pendingKey)) {
            pendingKey = key;
            pendingAge = 0;
            return;
        }
        if (++pendingAge < DEBOUNCE_TICKS) {
            return;
        }
        apply(level, hit.getBlockPos().immutable(), held.getItem(), candidate);
    }

    private static void apply(Level level, BlockPos anchorPos, Item tool, Carves.Result candidate) {
        Map<BlockPos, BlockState> drawn = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : candidate.blocks().entrySet()) {
            BlockPos p = e.getKey().immutable();
            if (!level.isLoaded(p)) {
                continue;
            }
            originals.put(p, level.getBlockState(p));
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            drawn.put(p, e.getValue());
        }
        if (drawn.isEmpty()) {
            originals.clear();
            return;
        }
        ghosts = drawn;
        anchor = anchorPos;
        anchorTool = tool;
        resetPending();
    }

    private static boolean stillValid(LocalPlayer player, Level level) {
        if (anchor == null || anchorTool == null || player.getMainHandItem().getItem() != anchorTool) {
            return false;
        }
        for (BlockPos p : originals.keySet()) {
            if (!level.isLoaded(p) || !level.getBlockState(p).isAir()) {
                return false;
            }
        }
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(KEEP_REACH));
        return new AABB(anchor).inflate(0.05).clip(eye, end).isPresent();
    }

    private static void restore(@Nullable Level level) {
        if (level != null) {
            for (Map.Entry<BlockPos, BlockState> e : originals.entrySet()) {
                BlockPos p = e.getKey();
                // isAir guard: never overwrite a cell the server already replaced (committed carve).
                if (level.isLoaded(p) && level.getBlockState(p).isAir()) {
                    level.setBlock(p, e.getValue(), Block.UPDATE_CLIENTS);
                }
            }
        }
        originals.clear();
    }

    private static void clear(@Nullable Level level) {
        if (!originals.isEmpty()) {
            restore(level);
        }
        ghosts = Map.of();
        anchor = null;
        anchorTool = null;
        resetPending();
    }

    public static void clearForCommit() {
        clear(Minecraft.getInstance().level);
    }

    private static boolean standingOn(LocalPlayer player, Carves.Result candidate) {
        BlockPos feet = player.blockPosition();
        for (BlockPos p : candidate.blocks().keySet()) {
            if (p.equals(feet) || p.equals(feet.below())) {
                return true;
            }
        }
        return false;
    }

    private static String signature(Carves.Result candidate) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<BlockPos, BlockState> e : candidate.blocks().entrySet()) {
            sb.append(e.getKey().asLong()).append('=').append(Block.getId(e.getValue())).append(';');
        }
        return sb.toString();
    }

    private static void resetPending() {
        pendingKey = null;
        pendingAge = 0;
    }
}
