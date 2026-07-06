package com.bannerbound.antiquity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.RopeFencePostBlock;
import com.bannerbound.antiquity.network.RopeFenceActionPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Rope-fence physics and interaction events. The fence's solid feel is faked ANALYTICALLY: block
 * collision is axis-aligned and cannot follow an angled rope, so each tick every nearby rope is treated
 * as a capsule (its true tie-point-to-tie-point segment plus the entity's half-width) and entities
 * inside are pushed out with pure 3D math off the real endpoints -- smooth at any angle and at bends.
 * The RopeCollisionBlock markers are only the cheap "a rope is near" gate and mob pathfinding
 * avoidance; the actual blocking comes from segments gathered off RopeTieHost block entities in the
 * entity's 3x3 chunk neighbourhood, passed around as double[]{ax,ay,az,bx,by,bz,postA,postB} where the
 * trailing flags mark which ends are fence POSTS. A POST end extends the wall one entity-radius past
 * the post (hidden inside the post's own collision) and gets a solid radial end-cap, so nothing slides
 * around the end of a diagonal rope; a GATE end instead pulls the wall back GATE_GAP and gets no cap,
 * leaving a real ~1-block opening an entity's CENTRE can cross -- THE fix for wide animals and the
 * herder being shoved out of an open gate (the gate block's own collision handles "closed"). Capsules
 * overlapping at rope junctions are re-resolved for up to RESOLVE_PASSES iterations, and penetration
 * under SLOP is ignored to kill sub-pixel jitter.
 *
 * <p>Sidedness: players are clamped with identical math on BOTH client and server (client-only clamping
 * let the server keep its unclamped position and tug the player back across the rope every tick); mobs
 * are clamped server-side only. Side-keeping uses the SIGNED perpendicular distance against the
 * entity's position at the end of LAST tick -- tracked ourselves in PREV_CLIENT/PREV_SERVER because
 * e.xo already equals getX() by the time EntityTickEvent.Post fires for the local player -- so even a
 * fast move ending well past the rope is shoved back. Entities carrying a fresh TELEPORT_AT_KEY
 * gametime tag (deliberate teleports, e.g. the herder penning animals) adopt the new position instead
 * of being bounced back across the rope. The wall is FENCE_HEIGHT tall and unjumpable, so a rising mob
 * within JUMP_BLOCK_MARGIN of a rope's vertical band has its upward velocity zeroed -- otherwise its
 * MoveControl jump fires on wall contact and it hops in place forever; this runs even when the mob has
 * not moved horizontally, since a bouncing mob barely moves between ticks. Flip DEBUG for an action-bar
 * readout of what the clamp sees.
 *
 * <p>Second half: left-click QOL for tie hosts (posts and gates alike). While a host has ropes, or is
 * the anchor you are mid-tie from, left-click breaks one rope / cancels the tie instead of mining; a
 * bare host mines normally, and survival block-breaking of a still-roped host is cancelled (peel its
 * ropes off first). The action is client-predicted (cancel the click, send RopeFenceActionPayload) and
 * performed authoritatively in serverHandle, which enforces MAX_REACH_SQR and a per-player
 * ACTION_COOLDOWN (stops creative hold-to-break re-fire). Creative is build mode and exempt -- posts
 * and gates delete freely there. A logout mid-tie clears the pending anchor so it is not left showing
 * the roped model.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class RopeFenceEvents {
    private static final double FENCE_HEIGHT = 2.0;
    private static final double GATE_GAP = 0.6;
    private static final double MOVE_EPSILON_SQR = 1.0e-6;
    // Mirrors CitizenEntity.TELEPORT_AT_KEY (Core not importable); every deliberate position jump in Core must tag it.
    private static final String TELEPORT_AT_KEY = "BannerboundTeleportAt";
    private static final int RESOLVE_PASSES = 6;
    private static final double SLOP = 1.0e-3;
    private static final double JUMP_BLOCK_MARGIN = 0.1;
    private static final boolean DEBUG = false;
    // Separate map per side: in singleplayer the client and integrated-server player share one UUID in one JVM.
    private static final Map<UUID, double[]> PREV_CLIENT = new ConcurrentHashMap<>();
    private static final Map<UUID, double[]> PREV_SERVER = new ConcurrentHashMap<>();

    private record Pair(RopeAnchor lo, RopeAnchor hi) {}

    private RopeFenceEvents() {}

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        if (!(e instanceof LivingEntity) || e.noPhysics || e.isSpectator() || e.isPassenger()) {
            return;
        }
        Level level = e.level();
        if (level.isClientSide && (!(e instanceof Player player) || !player.isLocalPlayer())) {
            return;
        }
        Map<UUID, double[]> prevMap = level.isClientSide ? PREV_CLIENT : PREV_SERVER;
        UUID id = e.getUUID();
        double curX = e.getX(), curZ = e.getZ();
        double[] prev = prevMap.get(id);
        double ox = prev == null ? curX : prev[0];
        double oz = prev == null ? curZ : prev[1];
        double mdx = curX - ox, mdz = curZ - oz;
        boolean moved = mdx * mdx + mdz * mdz >= MOVE_EPSILON_SQR;

        if (e.getPersistentData().contains(TELEPORT_AT_KEY)
                && level.getGameTime() - e.getPersistentData().getLong(TELEPORT_AT_KEY) <= 1L) {
            prevMap.put(id, new double[] { curX, curZ });
            return;
        }

        int ecx = Mth.floor(curX) >> 4;
        int ecz = Mth.floor(curZ) >> 4;
        boolean jumpingMob = !(e instanceof Player) && e.getDeltaMovement().y > 0.0;
        boolean wantSegments = moved || jumpingMob || (DEBUG && level.isClientSide && e instanceof Player);
        List<double[]> segments = wantSegments ? gatherSegments(level, ecx, ecz) : List.of();
        boolean pushed = false;
        if (moved && !segments.isEmpty()) {
            pushed = clamp(e, segments, ox, oz);
        }
        if (!(e instanceof Player) && !segments.isEmpty()) {
            suppressJump(e, segments);
        }
        prevMap.put(id, new double[] { e.getX(), e.getZ() });

        if (DEBUG && level.isClientSide && e instanceof Player p) {
            double pre = minSegDist(segments, curX, curZ);
            double post = minSegDist(segments, e.getX(), e.getZ());
            String msg = "ropes segs=" + segments.size()
                + " pushed=" + pushed
                + " pre=" + (segments.isEmpty() ? "-" : String.format("%.2f", pre))
                + " post=" + (segments.isEmpty() ? "-" : String.format("%.2f", post))
                + " spd=" + String.format("%.3f", e.getDeltaMovement().horizontalDistance());
            p.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true);
        }
    }

    private static void suppressJump(Entity e, List<double[]> segments) {
        Vec3 v = e.getDeltaMovement();
        if (v.y <= 0.0) {
            return;
        }
        AABB box = e.getBoundingBox();
        double r = e.getBbWidth() / 2.0;
        double reach = r + JUMP_BLOCK_MARGIN;
        double cx = e.getX(), cz = e.getZ();
        for (double[] s : segments) {
            double ax = s[0], ay = s[1], az = s[2], bx = s[3], by = s[4], bz = s[5];
            double dx = bx - ax, dz = bz - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1.0e-9 ? 0.0
                : Mth.clamp(((cx - ax) * dx + (cz - az) * dz) / len2, 0.0, 1.0);
            double qx = ax + t * dx, qz = az + t * dz;
            double ropeY = ay + t * (by - ay);
            double barrierBottom = ropeY - RopeAnchor.TIE_Y;
            if (box.maxY <= barrierBottom || box.minY >= barrierBottom + FENCE_HEIGHT) {
                continue;
            }
            double pdx = cx - qx, pdz = cz - qz;
            if (pdx * pdx + pdz * pdz <= reach * reach) {
                e.setDeltaMovement(v.x, 0.0, v.z);
                return;
            }
        }
    }

    private static double minSegDist(List<double[]> segments, double x, double z) {
        double min = Double.MAX_VALUE;
        for (double[] s : segments) {
            double dx = s[3] - s[0], dz = s[5] - s[2];
            double l2 = dx * dx + dz * dz;
            double tt = l2 < 1e-9 ? 0 : Mth.clamp(((x - s[0]) * dx + (z - s[2]) * dz) / l2, 0, 1);
            double cxp = s[0] + tt * dx - x, czp = s[2] + tt * dz - z;
            min = Math.min(min, Math.sqrt(cxp * cxp + czp * czp));
        }
        return min;
    }

    private static boolean isPost(Level level, RopeAnchor a) {
        return level.getBlockState(a.pos()).getBlock() instanceof RopeFencePostBlock;
    }

    private static List<double[]> gatherSegments(Level level, int ecx, int ecz) {
        Set<Pair> seen = new HashSet<>();
        List<double[]> out = new ArrayList<>();
        for (int cdx = -1; cdx <= 1; cdx++) {
            for (int cdz = -1; cdz <= 1; cdz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(ecx + cdx, ecz + cdz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof RopeTieHost host)) {
                        continue;
                    }
                    BlockPos hp = entry.getKey();
                    RopeTies.refreshFillersOnce(level, hp, host);
                    for (int slot = 0; slot < host.slotCount(); slot++) {
                        RopeAnchor local = new RopeAnchor(hp, slot);
                        for (RopeAnchor other : host.connections(slot)) {
                            Pair key = local.compareTo(other) <= 0
                                ? new Pair(local, other) : new Pair(other, local);
                            if (!seen.add(key)) {
                                continue;
                            }
                            Vec3 va = RopeAnchor.worldTie(level, local);
                            Vec3 vb = RopeAnchor.worldTie(level, other);
                            if (va != null && vb != null) {
                                out.add(new double[] { va.x, va.y, va.z, vb.x, vb.y, vb.z,
                                    isPost(level, local) ? 1.0 : 0.0, isPost(level, other) ? 1.0 : 0.0 });
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean clamp(Entity e, List<double[]> segments, double ox, double oz) {
        AABB box = e.getBoundingBox();
        double r = e.getBbWidth() / 2.0;
        double nx = e.getX(), nz = e.getZ();
        boolean changed = false;

        for (int pass = 0; pass < RESOLVE_PASSES; pass++) {
            boolean pushed = false;
            for (double[] s : segments) {
                double ax = s[0], ay = s[1], az = s[2], bx = s[3], by = s[4], bz = s[5];
                double dx = bx - ax, dz = bz - az;
                double len2 = dx * dx + dz * dz;
                if (len2 >= 1.0e-9) {
                    double sl = Math.sqrt(len2);
                    double ux = dx / sl, uz = dz / sl;
                    if (s[6] != 0.0) { ax -= ux * r;        az -= uz * r; }
                    else             { ax += ux * GATE_GAP; az += uz * GATE_GAP; }
                    if (s[7] != 0.0) { bx += ux * r;        bz += uz * r; }
                    else             { bx -= ux * GATE_GAP; bz -= uz * GATE_GAP; }
                    dx = bx - ax; dz = bz - az;
                    len2 = dx * dx + dz * dz;
                }
                double t = len2 < 1.0e-9 ? 0.0 : Mth.clamp(((nx - ax) * dx + (nz - az) * dz) / len2, 0.0, 1.0);
                double cx = ax + t * dx, cz = az + t * dz;
                double ropeY = ay + t * (by - ay);
                double barrierBottom = ropeY - RopeAnchor.TIE_Y;
                if (box.maxY <= barrierBottom || box.minY >= barrierBottom + FENCE_HEIGHT) {
                    continue;
                }
                if (len2 >= 1.0e-9 && t > 0.0 && t < 1.0) {
                    double sl = Math.sqrt(len2);
                    double perpX = -dz / sl, perpZ = dx / sl;
                    double fOld = perpX * (ox - ax) + perpZ * (oz - az);
                    double fNew = perpX * (nx - ax) + perpZ * (nz - az);
                    double side = fOld >= 0.0 ? 1.0 : -1.0;
                    if (side * fNew >= r - SLOP) {
                        continue;
                    }
                    nx += perpX * (side * r - fNew);
                    nz += perpZ * (side * r - fNew);
                } else {
                    boolean endIsPost = (t >= 1.0) ? s[7] != 0.0 : s[6] != 0.0;
                    if (!endIsPost) {
                        continue;
                    }
                    double px = nx - cx, pz = nz - cz;
                    double dist = Math.sqrt(px * px + pz * pz);
                    if (dist >= r - SLOP) {
                        continue;
                    }
                    double dirX, dirZ;
                    double opx = ox - cx, opz = oz - cz, od = Math.sqrt(opx * opx + opz * opz);
                    if (od > 1.0e-4) {
                        dirX = opx / od;
                        dirZ = opz / od;
                    } else if (dist > 1.0e-4) {
                        dirX = px / dist;
                        dirZ = pz / dist;
                    } else {
                        double sl = Math.sqrt(Math.max(len2, 1.0e-9));
                        dirX = -dz / sl;
                        dirZ = dx / sl;
                    }
                    nx = cx + dirX * r;
                    nz = cz + dirZ * r;
                }
                pushed = true;
                changed = true;
            }
            if (!pushed) {
                break;
            }
        }

        if (changed) {
            e.setPos(nx, e.getY(), nz);
            // xo/zo must be LAST tick's clamped pos: pre-clamp -> camera jitter; this tick's -> no interpolation (judder).
            e.xo = ox; e.zo = oz;
            e.xOld = ox; e.zOld = oz;
            // Rewrite walkDist to NET movement (0.6 = vanilla Entity.move factor) or view-bob sways while pinned.
            double ddx = nx - ox, ddz = nz - oz;
            double net = Math.sqrt(ddx * ddx + ddz * ddz);
            e.walkDist = (float) (e.walkDistO + net * 0.6);
            // Never write a player's deltaMovement: the server echo stacks onto client input -> speed launch. Mobs only.
            if (!(e instanceof Player)) {
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(ddx, v.y, ddz);
            }
        }
        return changed;
    }

    private static final double MAX_REACH_SQR = 64.0;
    private static final long ACTION_COOLDOWN = 5L;
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        if (!level.isClientSide || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        Player player = event.getEntity();
        if (player.getAbilities().instabuild) {
            return;
        }
        BlockPos pos = event.getPos();
        RopeTieHost host = RopeTies.hostAt(level, pos);
        if (host == null) {
            return;
        }
        RopeAnchor pend = RopeTieState.get();
        boolean pendingHere = pend != null && pend.pos().equals(pos);
        if (!pendingHere && !RopeTies.isConnectedAnySlot(host)) {
            return;
        }
        if (pendingHere) {
            RopeTieState.clear();
        }
        event.setCanceled(true);
        PacketDistributor.sendToServer(new RopeFenceActionPayload(pos.immutable()));
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player breaker = event.getPlayer();
        if (breaker != null && !breaker.getAbilities().instabuild
                && breaker.level().getBlockEntity(event.getPos()) instanceof RopeTieHost host
                && RopeTies.isConnectedAnySlot(host)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RopeTies.clearPending(player);
        }
    }

    public static void serverHandle(ServerPlayer player, BlockPos pos) {
        Level level = player.level();
        if (player.blockPosition().distSqr(pos) > MAX_REACH_SQR) {
            return;
        }
        RopeTieHost host = RopeTies.hostAt(level, pos);
        if (host == null) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST_ACTION.get(player.getUUID());
        if (last != null && now - last < ACTION_COOLDOWN) {
            return;
        }
        LAST_ACTION.put(player.getUUID(), now);
        if (RopeTies.isPendingAnchorAt(player, pos)) {
            RopeTies.clearPending(player);
            player.displayClientMessage(Component.translatable("message.bannerboundantiquity.rope_fence.cancel"), true);
        } else if (RopeTies.isConnectedAnySlot(host)) {
            RopeTies.breakOne(level, pos, host);
        }
    }
}
