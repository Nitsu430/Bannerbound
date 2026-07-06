package com.bannerbound.core.api.fisher;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Sailing for rod-fisher NPCs. Once a settlement researches {@link #FLAG_SAILING}, its fishers may
 * board a small vessel and paddle onto open water to reach the DEEP water the shore can't (deep
 * water bites ~2x faster - see {@code FisherBobber}), a little beyond the claim edge if need be.
 * Spear fishers never sail (spear fishing is a shallows technique). The vessel is a
 * non-interactable "ghost": it floats and animates like a real boat but takes no damage, drops
 * nothing, has no collision, and the player can't board, repair, or destroy it; it is spawned when
 * a trip launches and discarded when it ends.
 * <p>
 * <b>Cross-mod vessel:</b> Core has no boat art of its own, so the vessel is supplied by a
 * {@link VesselProvider} registered at mod setup (last registration wins). Antiquity registers a
 * ghost <i>raft</i>; with no provider, {@link #spawnGhostVessel} returns {@code null} and fishers
 * stay on shore - but sailing is gated behind an Antiquity research node, so a Core-only install
 * never reaches the no-provider path anyway.
 * <p>
 * {@link #drive} reuses the trader-sim technique: there is no rider paddle input, so the controller
 * pushes the hull toward a target each tick (steering at most {@link #MAX_TURN_PER_TICK} deg/tick
 * so it carves an arc rather than snapping yaw - snapping stutters the synced paddle animation)
 * and lets vanilla buoyancy hold the surface; it must be called every tick. {@link #anchor} damps
 * horizontal drift to hold the boat steady while fishing.
 */
public final class FishingVessels {
    public static final String FLAG_SAILING = "bannerbound.sailing";

    public static final double VESSEL_SPEED = 0.35;
    private static final float MAX_TURN_PER_TICK = 4.0F;

    @FunctionalInterface
    public interface VesselProvider {
        @Nullable
        Boat spawnGhostVessel(ServerLevel level, double x, double y, double z, float yaw);
    }

    @Nullable
    private static VesselProvider provider;

    private FishingVessels() {
    }

    public static void setProvider(VesselProvider p) {
        provider = p;
    }

    public static boolean hasProvider() {
        return provider != null;
    }

    @Nullable
    public static Boat spawnGhostVessel(ServerLevel level, double x, double y, double z, float yaw) {
        return provider == null ? null : provider.spawnGhostVessel(level, x, y, z, yaw);
    }

    public static boolean isSailingUnlocked(Settlement settlement) {
        return ResearchManager.hasFlag(settlement, FLAG_SAILING);
    }

    public static void drive(Boat boat, double targetX, double targetZ, double speed) {
        double dx = targetX - boat.getX();
        double dz = targetZ - boat.getZ();
        float desired = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float delta = net.minecraft.util.Mth.wrapDegrees(desired - boat.getYRot());
        float yaw = boat.getYRot() + net.minecraft.util.Mth.clamp(delta, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        boat.yRotO = boat.getYRot();
        boat.setYRot(yaw);
        double thrust = speed * (Math.abs(delta) > 60.0F ? 0.35 : 1.0);
        double heading = Math.toRadians(yaw + 90.0);
        Vec3 dm = boat.getDeltaMovement();
        boat.setDeltaMovement(Math.cos(heading) * thrust, dm.y, Math.sin(heading) * thrust);
        boat.hasImpulse = true;
        boat.setPaddleState(true, true);
    }

    public static void anchor(Boat boat) {
        Vec3 dm = boat.getDeltaMovement();
        boat.setDeltaMovement(dm.x * 0.6, dm.y, dm.z * 0.6);
        boat.setPaddleState(false, false);
    }
}
