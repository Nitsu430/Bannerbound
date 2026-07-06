package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.bannerbound.antiquity.block.entity.GhostRecipeWorkstation;
import com.bannerbound.antiquity.network.GhostActionPayload;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Single source of truth for the ghost-preview UI's click/hover geometry: the floating ghost
 * result (FILL) plus, when a workstation has 2+ candidate recipes, two browse arrows flanking it
 * (CYCLE_LEFT/RIGHT; a {@link Target}'s action is the {@link GhostActionPayload} action it sends).
 * Both the renderer (billboard positions + hover highlight) and the click handler in
 * GhostRecipeClientEvents ray-test through here, so what you see is exactly what you can click.
 * Arrows sit horizontally perpendicular to the camera (screen-left/right of the result,
 * pitch-flattened; the degenerate straight-down-with-rolled-camera case falls back to +X).
 *
 * <p>{@link #findHovered} scans SCAN_XZ/SCAN_Y blocks around the player for
 * GhostRecipeWorkstations and is shared by the click handler and the green-crosshair affordance so
 * they always agree on what's clickable. Conflict with vanilla targeting is resolved purely by
 * distance: whatever the crosshair ray hits first wins, so a closer solid block (even the
 * workstation itself) takes the click, while a result floating above a short block stays clickable
 * when aimed down at from up close. {@link #targetsFor} is empty unless the ghost preview is
 * showing, with one deliberate exception: the crafting stone's SOLID craftable result (exact
 * match, no ghost) also gets a FILL target so players craft straight from the floating preview
 * instead of the click falling through to the block (which now needs shift to craft). Compact
 * stations (carpenter's table + mason's bench, which share the ghostPreviewY 1.62 picker band) use
 * tighter arrow/result hitboxes.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class GhostClickTargets {
    private static final double ARROW_OFFSET = 0.55;
    private static final double ARROW_BOX = 0.32;
    private static final double RESULT_BOX = 0.5;

    public record Target(int action, Vec3 center, AABB box) {}

    public record Picked(Target target, double distSqr) {}

    public record Hover(BlockPos pos, Picked picked) {}

    private static final int SCAN_XZ = 6;
    private static final int SCAN_Y = 4;

    private GhostClickTargets() {}

    @Nullable
    public static Hover findHovered(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null || player.isSpectator()) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F);
        double reach = player.blockInteractionRange();

        Picked best = null;
        BlockPos bestPos = null;
        for (BlockPos p : BlockPos.betweenClosed(
                player.blockPosition().offset(-SCAN_XZ, -SCAN_Y, -SCAN_XZ),
                player.blockPosition().offset(SCAN_XZ, SCAN_Y, SCAN_XZ))) {
            BlockEntity be = mc.level.getBlockEntity(p);
            if (!(be instanceof GhostRecipeWorkstation)) continue;
            Picked picked = pick(targetsFor(be, camera), eye, view, reach);
            if (picked != null && (best == null || picked.distSqr() < best.distSqr())) {
                best = picked;
                bestPos = p.immutable();
            }
        }
        if (best == null) return null;

        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getLocation().distanceToSqr(eye) < best.distSqr()) {
            return null;
        }
        return new Hover(bestPos, best);
    }

    public static List<Target> targetsFor(BlockEntity be, Camera camera) {
        if (!(be instanceof GhostRecipeWorkstation ws)) return List.of();
        boolean hasGhost = !ws.getGhostResult().isEmpty();
        boolean exactMatch = !hasGhost && be instanceof CraftingStoneBlockEntity cs
            && !cs.getResult().isEmpty();
        if (!hasGhost && !exactMatch) return List.of();
        boolean compact = be instanceof WoodworkingTableBlockEntity
            || be instanceof com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
        double arrowOffset = compact ? 0.38 : ARROW_OFFSET;
        double arrowBox = compact ? 0.24 : ARROW_BOX;
        double resultBox = compact ? 0.38 : RESULT_BOX;
        BlockPos pos = be.getBlockPos();
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + ws.ghostPreviewY(), pos.getZ() + 0.5);
        List<Target> out = new ArrayList<>(3);
        out.add(new Target(GhostActionPayload.FILL, center,
            AABB.ofSize(center, resultBox, resultBox, resultBox)));
        if (ws.getGhostCandidateCount() >= 2) {
            var leftVec = camera.getLeftVector();
            Vec3 left = new Vec3(leftVec.x(), 0.0, leftVec.z());
            left = left.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : left.normalize();
            Vec3 l = center.add(left.scale(arrowOffset));
            Vec3 r = center.subtract(left.scale(arrowOffset));
            out.add(new Target(GhostActionPayload.CYCLE_LEFT, l,
                AABB.ofSize(l, arrowBox, arrowBox, arrowBox)));
            out.add(new Target(GhostActionPayload.CYCLE_RIGHT, r,
                AABB.ofSize(r, arrowBox, arrowBox, arrowBox)));
        }
        return out;
    }

    @Nullable
    public static Picked pick(List<Target> targets, Vec3 from, Vec3 dir, double reach) {
        Vec3 to = from.add(dir.normalize().scale(reach));
        Target best = null;
        double bestDist = Double.MAX_VALUE;
        for (Target t : targets) {
            Optional<Vec3> hit = t.box().clip(from, to);
            if (hit.isPresent()) {
                double d = hit.get().distanceToSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = t;
                }
            }
        }
        return best == null ? null : new Picked(best, bestDist);
    }
}
