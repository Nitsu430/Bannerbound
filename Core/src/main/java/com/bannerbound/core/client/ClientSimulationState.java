package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.network.SimulationStatePayload;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the running {@code /bannerbound simulate} crowd-LOD stress test. Holds the tiny
 * synced snapshot ({@link SimulationStatePayload}) and <em>deterministically regenerates</em> the
 * decorative crowd each frame from {@link #seed} -- no per-pedestrian data ever crosses the wire,
 * so the crowd is a pure CLIENT-only cost the server never knows about.
 *
 * <p>The crowd is <b>view-relative</b>: movers are produced in world-anchored cells around the
 * camera, their per-cell count scaled by the believed-population density over the settlement
 * footprint and hard-capped ({@code MAX_RENDER}), so a glance always shows plausible local activity
 * while the total rendered count stays bounded no matter how large the believed number is. Cells are
 * world-anchored and seed-stable, so walking around doesn't make movers pop in/out at the same spot.
 * Agents spawn within {@code RENDER_RADIUS} and are culled past {@code CULL_DISTANCE};
 * {@code cityRadius()} fills the real claimed village and only sprawls beyond it
 * ({@code CITY_R_K * sqrt(believedPop)}) once the population would overflow the footprint. The
 * NEAR/MID band edges are informational HUD bands only.
 *
 * <p>Frame counters ({@link #lastNear} etc.) are written by {@link CrowdRenderer} and read by
 * {@link SimulationHudLayer} for the believed-vs-rendered overlay.
 *
 * <p>Open: the tuning constants are hardcoded for now; promote to config once the feel settles.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientSimulationState {
    private ClientSimulationState() {
    }

    public static final double RENDER_RADIUS = 88.0;
    public static final double CULL_DISTANCE = 104.0;
    public static final double CITY_R_K = 1.7;
    public static final double MIN_CITY_R = 18.0;
    public static final double MAX_CITY_R = 150.0;
    public static final int MAX_RENDER = 160;
    public static final double NEAR_BAND = 24.0;
    public static final double MID_BAND = 56.0;

    private static volatile boolean active = false;
    private static volatile int townHallX, townHallY, townHallZ;
    private static volatile int radius = 48;
    private static volatile int believedPopulation = 0;
    private static volatile long seed = 0L;
    private static volatile int realCount = 0;
    private static volatile int remainingTicks = 0;
    private static volatile float serverMsPerTick = 0f;
    private static volatile int eraOrdinal = 0;
    private static volatile boolean debug = false;

    public static volatile int lastNear = 0;
    public static volatile int lastMid = 0;
    public static volatile int lastFar = 0;
    public static volatile int lastCulled = 0;

    public static void update(SimulationStatePayload p) {
        active = p.active();
        townHallX = p.townHallX();
        townHallY = p.townHallY();
        townHallZ = p.townHallZ();
        radius = Math.max(1, p.radius());
        believedPopulation = p.believedPopulation();
        seed = p.seed();
        realCount = p.realCount();
        remainingTicks = p.remainingTicks();
        serverMsPerTick = p.serverMsPerTick();
        eraOrdinal = p.eraOrdinal();
        debug = p.debug();
        if (!active) {
            lastNear = lastMid = lastFar = lastCulled = 0;
        }
    }

    public static void reset() {
        active = false;
        believedPopulation = 0;
        lastNear = lastMid = lastFar = lastCulled = 0;
        ClientCrowd.reset();
    }

    public static boolean isActive() { return active; }
    public static boolean isDebug() { return debug; }
    public static int believedPopulation() { return believedPopulation; }
    public static int realCount() { return realCount; }
    public static int remainingSeconds() { return remainingTicks / 20; }
    public static float serverMsPerTick() { return serverMsPerTick; }
    public static long seed() { return seed; }
    public static Era era() { return Era.fromOrdinalOrDefault(eraOrdinal); }

    public static double cityRadius() {
        double popScaled = CITY_R_K * Math.sqrt(believedPopulation);
        return Math.max(MIN_CITY_R, Math.min(MAX_CITY_R, Math.max(radius, popScaled)));
    }

    public static int renderCap() {
        return Math.max(24, Math.min(MAX_RENDER, believedPopulation));
    }

    public static BlockPos townHall() { return new BlockPos(townHallX, townHallY, townHallZ); }
}
