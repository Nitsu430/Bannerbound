package com.bannerbound.core.sim;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Single-trader long-distance traversal driver: powers /bannerbound trader_simulate (a throwaway
 * debug run) and any number of adopted trade couriers (real roster citizens, typically trading
 * stockers) driven point-to-point. Wire tickAll into ResearchEvents.onServerTick beside
 * SimulationManager.tickAll. Sessions are transient (keyed by session id in SESSIONS); owners
 * rebuild them after a restart, and completion fires JourneyListener exactly once on the main
 * thread (fail reason in route_failed/muster_timeout/timeout/returned/lost/stopped).
 *
 * Two liveness states, switched on player observation:
 *   REAL  - a real CitizenEntity carrying its own moving chunk-load ticket (an entity-ticking
 *           bubble) so its AI runs off-screen. It walks/boats, lays road live, is robbable.
 *   GHOST - no entity, no ticket, NO chunk-load. Its position advances by a clock at the cruise
 *           speed measured while it was real; road is recorded as data (PENDING_ROAD) and
 *           materialized when a chunk later loads. The instant a player nears it re-materializes as
 *           REAL at its dead-reckoned position (boat rebuilt if over deep water). So the count of
 *           simultaneously-REAL traders is bounded by player count, not trader count. The
 *           realize/ghost band tracks the entity's real client-tracking range (real exactly when a
 *           client would start receiving it -> no pop-in; ghost a few chunks past that -> no
 *           pop-out; the gap is hysteresis). EXCEPTION: an adopted courier is a real roster citizen
 *           and is NEVER ghosted or discarded - always REAL under the ticket for the whole trip.
 *
 * Routing runs OFF-THREAD (CompletableFuture) over PREDICTED terrain: noise-sampled getBaseHeight +
 * sea level, so nothing is loaded or generated, as a coarse ROUTE_GRID A*. Real loaded-chunk
 * surfaces near both endpoints are snapshotted on the main thread first and override the prediction,
 * so routes follow player-built bridges rather than detour around them. Heights use OCEAN_FLOOR (the
 * solid top under fluids) NOT WORLD_SURFACE, so sea - height reads true water depth instead of 0 at
 * the surface. Deep water (> WADE_DEPTH) needs a boat when sailing / is impassable without; shallow
 * water is waded. Every route stamps into a persistent ROAD_NETWORK the router discounts, so later
 * journeys converge onto existing roads instead of laying parallel lines.
 *
 * Ghost-laid road outlives its session: drainReadyRoad polls on the MAIN thread (deliberately no
 * chunk-load event -> no off-thread races) and materializes pending columns whenever their chunk
 * is loaded, including long after the journey ended. paveColumn
 * is public so stocker outpost runs lay the same road style (see StockerWorkGoal); adopted couriers
 * pave WILDERNESS only (never a settlement claim), the debug trader paves everywhere.
 *
 * Spawned traders get their vanilla goal + target AI stripped so ONLY our navigation moves them
 * (kills the idle-stroll interludes and the night invisibility-potion goal); navigation and move
 * control still tick in Mob.serverAiStep independent of goals, so external moveTo drives them
 * cleanly. Riderless boats are steered by hand every tick (an AI passenger never paddles);
 * setPaddleState is synced so clients animate the oars. Water rules: waterDepth counts standing
 * water from the surface down to the first non-water block, and nearestDryToward steps toward the
 * target for a fully-dry shore column so disembarks never land in water.
 *
 * Open: replace the straight-line waypoint fallback (used when the sailing route plan returns null)
 * with proper water-aware A*.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class TraderSimManager {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("bannerbound-trader-sim");

    private static final TicketType<UUID> TRADER_TICKET =
        TicketType.create("bannerbound_trader_sim", UUID::compareTo);
    // radius 2 -> trader's chunk at ENTITY_TICKING (ticket level 31 = 33 - radius): AI ticks off-screen.
    private static final int TICKET_RADIUS = 2;

    private static final int WAYPOINT_SPACING = 12;
    private static final double ARRIVE_RADIUS = 2.5;
    private static final int DENSIFY_STEP = 4;
    private static final int DEEP_WATER_GRACE = 40;
    private static final double MOVE_SPEED = 0.8;
    private static final int STUCK_TICKS = 80;
    private static final int MAX_NUDGES = 4;
    private static final double NUDGE_DIST = 2.0;
    private static final double STEP_HEIGHT = 1.0;
    private static final double BOAT_SPEED = 0.35;
    private static final double WATER_LOOKAHEAD = 2.0;
    private static final int WADE_DEPTH = 2;
    private static final int BOARD_COOLDOWN = 30;

    private static final double REALIZE_FLOOR = 64.0;
    private static final double GHOST_MARGIN = 48.0;
    private static final double GHOST_MIN_SPEED = 0.08;
    private static final double GHOST_MAX_SPEED = 0.5;

    private static final int ROUTE_GRID = 16;
    private static final int ROUTE_MAX_EXPANSIONS = 60000;
    private static final double SLOPE_PENALTY = 0.6;
    private static final double WATER_PENALTY = 8.0;
    private static final double ROAD_DISCOUNT = 0.2;
    private static final int OVERRIDE_RADIUS = 12;
    private static final int DRAIN_CHUNK_BUDGET = 8;

    private static final Map<UUID, TraderSimSession> SESSIONS = new java.util.LinkedHashMap<>();
    private static UUID debugSessionId;

    private static final Map<Long, List<int[]>> PENDING_ROAD = new HashMap<>();

    // Concurrent: route planning READS this off-thread while the main thread stamps new routes in.
    private static final java.util.Set<Long> ROAD_NETWORK = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public interface JourneyListener {
        void onArrived(UUID journeyId);

        void onFailed(UUID journeyId, String reason);
    }

    private TraderSimManager() {
    }

    public static boolean isActive() {
        return debugSessionId != null && SESSIONS.containsKey(debugSessionId);
    }

    public static boolean hasSession(UUID id) {
        return id != null && SESSIONS.containsKey(id);
    }

    public static String start(MinecraftServer server, ServerPlayer initiator, BlockPos start, BlockPos end,
                               boolean sailing) {
        if (server == null) return "No server.";
        ServerLevel level = server.overworld();
        stop(server);

        long now = level.getGameTime();
        double totalDist = Math.hypot((double) end.getX() - start.getX(), (double) end.getZ() - start.getZ());
        TraderSimSession s = new TraderSimSession(UUID.randomUUID(), null,
            initiator == null ? null : initiator.getUUID(), start, end,
            null, null, now, Long.MAX_VALUE, totalDist, sailing);
        s.debug = true;
        s.gx = start.getX() + 0.5;
        s.gz = start.getZ() + 0.5;
        Map<Long, int[]> override = snapshotLoadedFloors(level, start, end);
        s.planFuture = CompletableFuture.supplyAsync(() -> planRoute(level, start, end, sailing, override));
        SESSIONS.put(s.id, s);
        debugSessionId = s.id;
        return null;
    }

    public static UUID startAdopted(MinecraftServer server, CitizenEntity courier, BlockPos from,
                                    BlockPos to, boolean sailing, JourneyListener listener) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        double totalDist = Math.hypot((double) to.getX() - from.getX(), (double) to.getZ() - from.getZ());
        TraderSimSession s = new TraderSimSession(UUID.randomUUID(), courier.getUUID(), null,
            from, to, null, null, now, Long.MAX_VALUE, totalDist, sailing);
        s.adopted = true;
        s.listener = listener;
        s.musterDeadline = now + 1200L;
        s.gx = courier.getX();
        s.gy = courier.getY();
        s.gz = courier.getZ();

        AttributeInstance step = courier.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) {
            s.prevStepHeight = step.getBaseValue();
            step.setBaseValue(STEP_HEIGHT);
        }
        if (courier.getNavigation() instanceof GroundPathNavigation gpn) gpn.setCanFloat(sailing);

        ChunkPos center = new ChunkPos(courier.blockPosition());
        level.getChunkSource().addRegionTicket(TRADER_TICKET, center, TICKET_RADIUS, s.id);
        s.ticketCenter = center;

        Map<Long, int[]> override = snapshotLoadedFloors(level, from, to);
        s.planFuture = CompletableFuture.supplyAsync(() -> planRoute(level, from, to, sailing, override));
        SESSIONS.put(s.id, s);
        return s.id;
    }

    public static void stopSession(MinecraftServer server, UUID id, String reason) {
        TraderSimSession s = SESSIONS.get(id);
        if (s == null) return;
        if (s.planFuture != null) {
            s.planFuture.cancel(true);
            s.planFuture = null;
        }
        finishFailed(server, s, reason == null ? "stopped" : reason, null);
    }

    private static void onPlanReady(MinecraftServer server, TraderSimSession s, List<int[]> planned) {
        ServerLevel level = server.overworld();
        List<int[]> waypoints = planned;
        if (waypoints == null) {
            if (!s.sailing) {
                finishFailed(server, s, "route_failed",
                    "Couldn't reach — no land route without crossing water (try sailing).");
                return;
            }
            waypoints = buildWaypoints(s.start, s.end);
        }
        waypoints = densify(waypoints, s.start);
        addRouteToNetwork(s.start, waypoints);

        long now = level.getGameTime();
        s.waypoints = waypoints;
        s.maxGameTick = now + Math.min(216000L, 2400L + (long) (routePathLength(s.start, waypoints) * 30.0));

        if (s.debug) {
            ChunkPos center = new ChunkPos(s.start);
            level.getChunk(center.x, center.z);
            int gy = groundY(level, s.start.getX(), s.start.getZ());
            s.gy = gy;
            CitizenEntity trader = makeTrader(level, s, s.gx, gy, s.gz);
            if (trader == null) {
                finishFailed(server, s, "route_failed", "Couldn't spawn the trader.");
                return;
            }
            s.traderId = trader.getUUID();
            level.getChunkSource().addRegionTicket(TRADER_TICKET, center, TICKET_RADIUS, s.id);
            s.ticketCenter = center;
            message(server, s, String.format("Trader dispatched (%d, %d, %d) → (%d, %d, %d), sailing %s.",
                s.start.getX(), s.start.getY(), s.start.getZ(),
                s.end.getX(), s.end.getY(), s.end.getZ(), s.sailing ? "ON" : "OFF"));
        }
    }

    private static double routePathLength(BlockPos start, List<int[]> waypoints) {
        double len = 0.0;
        double px = start.getX() + 0.5, pz = start.getZ() + 0.5;
        for (int[] wp : waypoints) {
            len += horiz(px, pz, wp[0] + 0.5, wp[1] + 0.5);
            px = wp[0] + 0.5;
            pz = wp[1] + 0.5;
        }
        return len;
    }

    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel level = server.overworld();
        drainReadyRoad(level);
        if (SESSIONS.isEmpty()) return;
        for (TraderSimSession s : new ArrayList<>(SESSIONS.values())) {
            tickSession(server, level, s);
        }
    }

    private static void tickSession(MinecraftServer server, ServerLevel level, TraderSimSession s) {
        long now = level.getGameTime();

        if (s.planFuture != null && s.planFuture.isDone()) {
            List<int[]> planned;
            try {
                planned = s.planFuture.get();
            } catch (Exception e) {
                planned = null;
                LOGGER.warn("[TraderSim] route planning failed", e);
            }
            s.planFuture = null;
            onPlanReady(server, s, planned);
            if (!SESSIONS.containsKey(s.id)) return;
        }

        if (s.adopted && !s.mustered) {
            if (now >= s.musterDeadline) {
                finishFailed(server, s, "muster_timeout", null);
                return;
            }
            CitizenEntity courier = resolveAdopted(server, level, s);
            if (courier == null) return;
            followWithTicket(level, s, courier);
            if (horiz(courier.getX(), courier.getZ(), s.start.getX() + 0.5, s.start.getZ() + 0.5)
                    <= ARRIVE_RADIUS + 1.5) {
                s.mustered = true;
                courier.getNavigation().stop();
                resetLeg(s);
            } else if (courier.getNavigation().isDone()) {
                courier.getNavigation().moveTo(s.start.getX() + 0.5,
                    groundY(level, s.start.getX(), s.start.getZ()), s.start.getZ() + 0.5, MOVE_SPEED);
            }
            return;
        }
        if (s.waypoints == null) return;

        if (now >= s.maxGameTick) {
            finishFailed(server, s, "timeout", "Trader simulation hit its time budget — ended.");
            return;
        }

        CitizenEntity trader;
        if (s.adopted) {
            trader = resolveAdopted(server, level, s);
            if (trader == null) return;
        } else {
            trader = null;
            if (!s.ghost) {
                Entity e = s.traderId == null ? null : level.getEntity(s.traderId);
                if (e instanceof CitizenEntity t && !t.isRemoved()) {
                    trader = t;
                } else {
                    ghostFromLastKnown(level, s);
                }
            }
            double px = trader != null ? trader.getX() : s.gx;
            double pz = trader != null ? trader.getZ() : s.gz;

            double nearest = nearestPlayerDist(server, px, pz);
            int trackChunks = Math.min(EntityType.WANDERING_TRADER.clientTrackingRange(),
                server.getPlayerList().getViewDistance());
            double realizeDist = Math.max(REALIZE_FLOOR, trackChunks * 16.0);
            double ghostDist = realizeDist + GHOST_MARGIN;
            if (s.ghost && nearest <= realizeDist) {
                trader = realize(level, s);
            } else if (!s.ghost && trader != null && nearest >= ghostDist) {
                ghostify(level, s, trader);
                trader = null;
            }
        }

        if (s.ghost || trader == null) {
            tickGhost(server, level, s);
        } else {
            tickReal(server, level, s, now, trader);
        }
        if (!SESSIONS.containsKey(s.id)) return;

        if (s.debug && now - s.lastProgressTick >= 40L) {
            s.lastProgressTick = now;
            broadcastProgress(server, s);
        }
    }

    private static CitizenEntity resolveAdopted(MinecraftServer server, ServerLevel level, TraderSimSession s) {
        Entity e = s.traderId == null ? null : level.getEntity(s.traderId);
        if (e instanceof CitizenEntity t && !t.isRemoved() && t.isAlive()) {
            s.lostTicks = 0;
            return t;
        }
        if (++s.lostTicks > 200) {
            finishFailed(server, s, "lost", null);
        }
        return null;
    }

    private static void followWithTicket(ServerLevel level, TraderSimSession s, CitizenEntity walker) {
        ChunkPos tc = new ChunkPos(walker.blockPosition());
        if (!tc.equals(s.ticketCenter)) {
            if (s.ticketCenter != null) {
                level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
            }
            level.getChunkSource().addRegionTicket(TRADER_TICKET, tc, TICKET_RADIUS, s.id);
            s.ticketCenter = tc;
        }
    }

    public static void stop(MinecraftServer server) {
        TraderSimSession s = debugSessionId == null ? null : SESSIONS.get(debugSessionId);
        debugSessionId = null;
        if (s == null) return;
        if (s.planFuture != null) {
            s.planFuture.cancel(true);
            s.planFuture = null;
        }
        finish(server, s, "Trader simulation stopped.", false);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (TraderSimSession s : new ArrayList<>(SESSIONS.values())) {
            if (s.planFuture != null) {
                s.planFuture.cancel(true);
                s.planFuture = null;
            }
            finish(event.getServer(), s, null, false);
        }
    }

    private static void finishArrived(MinecraftServer server, TraderSimSession s, String why) {
        finish(server, s, why, true);
    }

    private static void finishFailed(MinecraftServer server, TraderSimSession s, String reason, String why) {
        s.pendingFailReason = reason;
        finish(server, s, why, false);
    }

    private static void finish(MinecraftServer server, TraderSimSession s, String why, boolean arrived) {
        if (SESSIONS.remove(s.id) == null) return;
        if (s.id.equals(debugSessionId)) debugSessionId = null;
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (s.ticketCenter != null) {
            level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
        }
        if (s.boatId != null) {
            Entity b = level.getEntity(s.boatId);
            if (b != null) {
                Entity rider = s.traderId == null ? null : level.getEntity(s.traderId);
                if (rider != null && rider.getVehicle() == b) rider.stopRiding();
                b.discard();
            }
        }
        if (s.adopted) {
            Entity e = s.traderId == null ? null : level.getEntity(s.traderId);
            if (e instanceof CitizenEntity courier && !Double.isNaN(s.prevStepHeight)) {
                AttributeInstance step = courier.getAttribute(Attributes.STEP_HEIGHT);
                if (step != null) step.setBaseValue(s.prevStepHeight);
            }
        } else if (s.traderId != null) {
            Entity e = level.getEntity(s.traderId);
            if (e != null) e.discard();
        }
        if (why != null) message(server, s, why);
        if (s.listener != null) {
            if (arrived) {
                s.listener.onArrived(s.id);
            } else {
                s.listener.onFailed(s.id, s.pendingFailReason == null ? "stopped" : s.pendingFailReason);
            }
        }
    }

    private static void ghostify(ServerLevel level, TraderSimSession s, CitizenEntity trader) {
        s.gx = trader.getX();
        s.gy = trader.getY();
        s.gz = trader.getZ();
        s.ghost = true;
        if (s.boatId != null) {
            Entity b = level.getEntity(s.boatId);
            if (b != null) b.discard();
            s.boatId = null;
        }
        trader.discard();
        if (s.ticketCenter != null) {
            level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
            s.ticketCenter = null;
        }
    }

    private static CitizenEntity realize(ServerLevel level, TraderSimSession s) {
        int bx = (int) Math.floor(s.gx);
        int bz = (int) Math.floor(s.gz);
        level.getChunk(bx >> 4, bz >> 4);
        boolean overDeep = s.sailing && isDeepWaterColumn(level, bx, bz);
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        double y = overDeep ? surfaceY : groundY(level, bx, bz);

        CitizenEntity trader = makeTrader(level, s, s.gx, y, s.gz);
        if (trader == null) return null;
        s.traderId = trader.getUUID();
        s.ghost = false;
        s.lastX = Double.NaN; // skip the first speed sample after the spawn jump
        ChunkPos center = new ChunkPos(bx >> 4, bz >> 4);
        level.getChunkSource().addRegionTicket(TRADER_TICKET, center, TICKET_RADIUS, s.id);
        s.ticketCenter = center;

        if (overDeep) {
            Boat boat = spawnBoat(level, s.gx, surfaceY, s.gz, trader.getYRot());
            if (boat != null) {
                trader.startRiding(boat, true);
                s.boatId = boat.getUUID();
            }
        }
        resetLeg(s);
        return trader;
    }

    private static void ghostFromLastKnown(ServerLevel level, TraderSimSession s) {
        s.ghost = true;
        s.boatId = null;
        if (s.ticketCenter != null) {
            level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
            s.ticketCenter = null;
        }
    }

    private static CitizenEntity makeTrader(ServerLevel level, TraderSimSession s, double x, double y, double z) {
        CitizenEntity trader = BannerboundCore.CITIZEN.get().create(level);
        if (trader == null) return null;

        Settlement set = s.initiator != null ? SettlementData.get(level).getByPlayer(s.initiator) : null;
        Era era = set != null ? set.age() : SettlementData.get(level).getWorldAge();
        CitizenGender gender = level.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        ChatFormatting color = set != null ? set.identityFormatting() : ChatFormatting.GOLD;
        trader.initializeCitizen(set != null ? set.id() : null,
            CitizenNameLoader.randomName(level.random, era, gender), gender, era, color);
        trader.setCompliance(100);
        trader.markSimulated();

        trader.moveTo(x, y, z, 0.0F, 0.0F);
        // Don't set base movement speed: recomputeSpeedModifier hard-locks it to 0.4; pace comes from the MOVE_SPEED nav multiplier.
        AttributeInstance step = trader.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) step.setBaseValue(STEP_HEIGHT);
        stripGoals(trader);
        if (trader.getNavigation() instanceof GroundPathNavigation gpn) gpn.setCanFloat(s.sailing);
        if (!level.addFreshEntity(trader)) return null;
        return trader;
    }

    private static void tickGhost(MinecraftServer server, ServerLevel level, TraderSimSession s) {
        int[] wp = s.waypoints.get(s.index);
        double dx = (wp[0] + 0.5) - s.gx;
        double dz = (wp[1] + 0.5) - s.gz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 1.0e-3) {
            double step = Math.min(clampSpeed(s.observedSpeed), dist);
            s.gx += dx / dist * step;
            s.gz += dz / dist * step;
            recordGhostRoad(s, (int) Math.floor(s.gx), (int) Math.floor(s.gz));
        }
        if (horiz(s.gx, s.gz, wp[0] + 0.5, wp[1] + 0.5) <= ARRIVE_RADIUS) {
            s.index++;
            if (s.index >= s.waypoints.size()) {
                if (s.returning) {
                    finishFailed(server, s, "returned", s.pendingFail);
                } else {
                    finishArrived(server, s, s.debug ? "Trader arrived at its destination." : null);
                }
            }
        }
    }

    private static void recordGhostRoad(TraderSimSession s, int x, int z) {
        if (x == s.lastGhostRoadX && z == s.lastGhostRoadZ) return;
        s.lastGhostRoadX = x;
        s.lastGhostRoadZ = z;
        PENDING_ROAD.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), k -> new ArrayList<>())
            .add(new int[] { x, z });
    }

    private static void tickReal(MinecraftServer server, ServerLevel level, TraderSimSession s, long now,
                                 CitizenEntity trader) {
        trader.setAirSupply(trader.getMaxAirSupply());

        if (!Double.isNaN(s.lastX)) {
            double disp = horiz(trader.getX(), trader.getZ(), s.lastX, s.lastZ);
            if (disp > 0.05) s.observedSpeed = clampSpeed(s.observedSpeed * 0.8 + disp * 0.2);
        }
        s.lastX = trader.getX();
        s.lastZ = trader.getZ();
        s.gx = trader.getX();
        s.gy = trader.getY();
        s.gz = trader.getZ();

        followWithTicket(level, s, trader);

        boolean boating = trader.isPassenger() && trader.getVehicle() instanceof Boat;
        if (!boating) paveUnder(level, trader, s);

        int[] wp = s.waypoints.get(s.index);
        double d = horiz(trader.getX(), trader.getZ(), wp[0] + 0.5, wp[1] + 0.5);
        while (d <= ARRIVE_RADIUS) {
            s.index++;
            if (s.index >= s.waypoints.size()) {
                if (s.returning) {
                    finishFailed(server, s, "returned", s.pendingFail);
                } else {
                    finishArrived(server, s, s.debug ? "Trader arrived at its destination." : null);
                }
                return;
            }
            resetLeg(s);
            wp = s.waypoints.get(s.index);
            d = horiz(trader.getX(), trader.getZ(), wp[0] + 0.5, wp[1] + 0.5);
        }

        if (d < s.bestDist - 0.25) {
            s.bestDist = d;
            s.noProgressTicks = 0;
            s.nudges = 0;
        } else {
            s.noProgressTicks++;
        }

        int[] ahead = lookAhead(trader, wp, WATER_LOOKAHEAD);
        boolean deepAhead = isDeepWaterColumn(level, ahead[0], ahead[1]);
        boolean deepHere = isDeepWaterColumn(level, trader.getBlockX(), trader.getBlockZ());

        if (boating) {
            Boat boat = (Boat) trader.getVehicle();
            int[] dry = nearestDryToward(level, trader, wp, 4);
            if (dry != null || s.noProgressTicks > STUCK_TICKS * 2) {
                int[] target = dry != null ? dry : nearestDryToward(level, trader, wp, 16);
                if (target == null) target = ahead;
                int ly = groundY(level, target[0], target[1]);
                trader.stopRiding();
                trader.moveTo(target[0] + 0.5, ly, target[1] + 0.5, trader.getYRot(), 0.0F);
                trader.getNavigation().stop();
                boat.discard();
                s.boatId = null;
                s.noBoatUntil = now + BOARD_COOLDOWN;
                resetLeg(s);
            } else {
                driveBoat(boat, wp);
            }
        } else if (s.sailing && now >= s.noBoatUntil && (deepAhead || deepHere)) {
            int bx = deepAhead ? ahead[0] : trader.getBlockX();
            int bz = deepAhead ? ahead[1] : trader.getBlockZ();
            int by = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            Boat boat = spawnBoat(level, bx + 0.5, by, bz + 0.5, trader.getYRot());
            if (boat != null) {
                trader.getNavigation().stop();
                trader.startRiding(boat, true);
                s.boatId = boat.getUUID();
                resetLeg(s);
            }
        } else {
            if (!s.sailing && deepHere) {
                s.deepWaterTicks++;
                int[] dry = nearestDryToward(level, trader, wp, 6);
                if (dry != null && s.deepWaterTicks <= DEEP_WATER_GRACE) {
                    trader.moveTo(dry[0] + 0.5, groundY(level, dry[0], dry[1]), dry[1] + 0.5, trader.getYRot(), 0.0F);
                    trader.getNavigation().stop();
                    return;
                }
                int[] home = nearestDryToward(level, trader, new int[] { s.start.getX(), s.start.getZ() }, 16);
                if (home != null) {
                    trader.moveTo(home[0] + 0.5, groundY(level, home[0], home[1]), home[1] + 0.5, trader.getYRot(), 0.0F);
                    trader.getNavigation().stop();
                }
                if (!s.returning) beginReturn(server, s, trader);
                else finishFailed(server, s, "returned", s.pendingFail);
                return;
            }
            s.deepWaterTicks = 0;
            if (trader.getNavigation().isDone()) {
                int wy = groundY(level, wp[0], wp[1]);
                trader.getNavigation().moveTo(wp[0] + 0.5, wy, wp[1] + 0.5, MOVE_SPEED);
            }
            if (!s.sailing && deepAhead && s.noProgressTicks > STUCK_TICKS * 2) {
                s.noProgressTicks = 0;
                if (!s.returning) {
                    beginReturn(server, s, trader);
                } else {
                    finishFailed(server, s, "returned", s.pendingFail);
                }
            } else if (s.noProgressTicks > STUCK_TICKS && !(deepAhead && !s.sailing)) {
                s.noProgressTicks = 0;
                s.nudges++;
                if (s.nudges <= MAX_NUDGES) {
                    double dx = (wp[0] + 0.5) - trader.getX();
                    double dz = (wp[1] + 0.5) - trader.getZ();
                    double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
                    double hop = Math.min(NUDGE_DIST, len);
                    int nx = (int) Math.floor(trader.getX() + dx / len * hop);
                    int nz = (int) Math.floor(trader.getZ() + dz / len * hop);
                    int ny = groundY(level, nx, nz);
                    trader.moveTo(nx + 0.5, ny, nz + 0.5);
                    trader.getNavigation().stop();
                    s.bestDist = horiz(nx + 0.5, nz + 0.5, wp[0] + 0.5, wp[1] + 0.5);
                } else if (s.returning) {
                    finishFailed(server, s, "returned", s.pendingFail);
                } else {
                    int wy = groundY(level, wp[0], wp[1]);
                    trader.moveTo(wp[0] + 0.5, wy, wp[1] + 0.5);
                    trader.getNavigation().stop();
                    s.nudges = 0;
                    s.bestDist = Double.MAX_VALUE;
                }
            }
        }
    }

    private static void beginReturn(MinecraftServer server, TraderSimSession s, CitizenEntity trader) {
        s.returning = true;
        s.pendingFail = "Couldn't reach the destination — can't cross water.";
        s.waypoints = buildWaypoints(trader.blockPosition(), s.start);
        s.index = 0;
        resetLeg(s);
        message(server, s, "Trader hit impassable water — turning back.");
    }

    private static void drainReadyRoad(ServerLevel level) {
        if (PENDING_ROAD.isEmpty() || level.getGameTime() % 10L != 0L) return;
        int chunkBudget = DRAIN_CHUNK_BUDGET;
        Iterator<Map.Entry<Long, List<int[]>>> it = PENDING_ROAD.entrySet().iterator();
        while (it.hasNext() && chunkBudget > 0) {
            Map.Entry<Long, List<int[]>> e = it.next();
            ChunkPos cp = new ChunkPos(e.getKey());
            if (!level.hasChunk(cp.x, cp.z)) continue;
            for (int[] pt : e.getValue()) {
                pave3Wide(level, pt[0], pt[1]);
            }
            it.remove();
            chunkBudget--;
        }
    }

    private static double nearestPlayerDist(MinecraftServer server, double px, double pz) {
        ServerLevel overworld = server.overworld();
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != overworld) continue;
            double dd = horiz(p.getX(), p.getZ(), px, pz);
            if (dd < best) best = dd;
        }
        return best;
    }

    private static double clampSpeed(double v) {
        return Math.max(GHOST_MIN_SPEED, Math.min(GHOST_MAX_SPEED, v));
    }

    private static List<int[]> buildWaypoints(BlockPos start, BlockPos end) {
        List<int[]> out = new ArrayList<>();
        double dx = (double) end.getX() - start.getX();
        double dz = (double) end.getZ() - start.getZ();
        double dist = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.ceil(dist / WAYPOINT_SPACING));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            out.add(new int[] {
                (int) Math.round(start.getX() + dx * t),
                (int) Math.round(start.getZ() + dz * t)
            });
        }
        return out;
    }

    private static List<int[]> densify(List<int[]> wps, BlockPos start) {
        List<int[]> out = new ArrayList<>();
        double px = start.getX();
        double pz = start.getZ();
        for (int[] wp : wps) {
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(wp[0] - px, wp[1] - pz) / DENSIFY_STEP));
            for (int i = 1; i <= steps; i++) {
                double t = (double) i / steps;
                out.add(new int[] { (int) Math.round(px + (wp[0] - px) * t), (int) Math.round(pz + (wp[1] - pz) * t) });
            }
            px = wp[0];
            pz = wp[1];
        }
        return out;
    }

    private record RouteNode(long node, double f) {}

    private static List<int[]> planRoute(ServerLevel level, BlockPos start, BlockPos end, boolean sailing,
                                         Map<Long, int[]> override) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = level.getSeaLevel();

        int egx = Math.floorDiv(end.getX(), ROUTE_GRID);
        int egz = Math.floorDiv(end.getZ(), ROUTE_GRID);
        long startN = ChunkPos.asLong(Math.floorDiv(start.getX(), ROUTE_GRID), Math.floorDiv(start.getZ(), ROUTE_GRID));
        long endN = ChunkPos.asLong(egx, egz);

        Map<Long, Integer> height = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        HashSet<Long> closed = new HashSet<>();
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::f));
        gScore.put(startN, 0.0);
        open.add(new RouteNode(startN, 0.0));

        int expansions = 0;
        while (!open.isEmpty()) {
            if (++expansions > ROUTE_MAX_EXPANSIONS) return null;
            long cur = open.poll().node();
            if (cur == endN) return rebuildRoute(cameFrom, cur, end, override);
            if (!closed.add(cur)) continue;
            ChunkPos cp = new ChunkPos(cur);
            double curG = gScore.getOrDefault(cur, Double.MAX_VALUE);
            int curFloor = routeHeight(gen, rs, level, height, override, cp.x, cp.z);
            int curTravel = (sea - curFloor) > WADE_DEPTH ? sea : curFloor;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int ngx = cp.x + dx, ngz = cp.z + dz;
                    long nb = ChunkPos.asLong(ngx, ngz);
                    if (closed.contains(nb)) continue;
                    int nFloor = routeHeight(gen, rs, level, height, override, ngx, ngz);
                    boolean deep = (sea - nFloor) > WADE_DEPTH;
                    if (!sailing && deep) continue;
                    int nTravel = deep ? sea : nFloor;
                    double dist = (dx != 0 && dz != 0 ? 1.4142 : 1.0) * ROUTE_GRID;
                    double slope = Math.abs(nTravel - curTravel);
                    double cost = dist * (1.0 + SLOPE_PENALTY * slope / ROUTE_GRID) + (deep ? WATER_PENALTY * dist : 0.0);
                    if (ROAD_NETWORK.contains(nb)) cost *= ROAD_DISCOUNT;
                    double tentative = curG + cost;
                    if (tentative < gScore.getOrDefault(nb, Double.MAX_VALUE)) {
                        cameFrom.put(nb, cur);
                        gScore.put(nb, tentative);
                        open.add(new RouteNode(nb, tentative + routeHeuristic(ngx, ngz, egx, egz)));
                    }
                }
            }
        }
        return null;
    }

    private static double routeHeuristic(int ax, int az, int bx, int bz) {
        double dx = (double) (ax - bx) * ROUTE_GRID;
        double dz = (double) (az - bz) * ROUTE_GRID;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int routeHeight(ChunkGenerator gen, RandomState rs, ServerLevel level,
                                   Map<Long, Integer> cache, Map<Long, int[]> override, int gx, int gz) {
        long key = ChunkPos.asLong(gx, gz);
        int[] ov = override.get(key);
        if (ov != null) return ov[0];
        Integer h = cache.get(key);
        if (h != null) return h;
        int hv = gen.getBaseHeight(gx * ROUTE_GRID, gz * ROUTE_GRID, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
        cache.put(key, hv);
        return hv;
    }

    private static Map<Long, int[]> snapshotLoadedFloors(ServerLevel level, BlockPos start, BlockPos end) {
        Map<Long, int[]> out = new HashMap<>();
        snapshotAround(level, out, start);
        snapshotAround(level, out, end);
        return out;
    }

    private static void snapshotAround(ServerLevel level, Map<Long, int[]> out, BlockPos center) {
        int ccx = center.getX() >> 4;
        int ccz = center.getZ() >> 4;
        for (int cx = ccx - OVERRIDE_RADIUS; cx <= ccx + OVERRIDE_RADIUS; cx++) {
            for (int cz = ccz - OVERRIDE_RADIUS; cz <= ccz + OVERRIDE_RADIUS; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                int best = level.getMinBuildHeight();
                int bx = cx << 4;
                int bz = cz << 4;
                for (int ox = 0; ox < 16; ox += 2) {
                    for (int oz = 0; oz < 16; oz += 2) {
                        int wx = (cx << 4) + ox;
                        int wz = (cz << 4) + oz;
                        int h = level.getHeight(Heightmap.Types.OCEAN_FLOOR, wx, wz);
                        if (h > best) {
                            best = h;
                            bx = wx;
                            bz = wz;
                        }
                    }
                }
                out.put(ChunkPos.asLong(cx, cz), new int[] { best, bx, bz });
            }
        }
    }

    private static List<int[]> rebuildRoute(Map<Long, Long> cameFrom, long end, BlockPos endPos,
                                            Map<Long, int[]> override) {
        List<int[]> pts = new ArrayList<>();
        Long c = end;
        while (c != null) {
            int[] ov = override.get(c);
            if (ov != null) {
                pts.add(new int[] { ov[1], ov[2] });
            } else {
                ChunkPos cp = new ChunkPos(c);
                pts.add(new int[] { cp.x * ROUTE_GRID, cp.z * ROUTE_GRID });
            }
            c = cameFrom.get(c);
        }
        Collections.reverse(pts);
        pts.add(new int[] { endPos.getX(), endPos.getZ() });
        return pts;
    }

    private static void addRouteToNetwork(BlockPos start, List<int[]> waypoints) {
        double px = start.getX();
        double pz = start.getZ();
        for (int[] wp : waypoints) {
            double tx = wp[0];
            double tz = wp[1];
            int steps = Math.max(1, (int) (Math.hypot(tx - px, tz - pz) / ROUTE_GRID));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                int wx = (int) Math.round(px + (tx - px) * t);
                int wz = (int) Math.round(pz + (tz - pz) * t);
                ROAD_NETWORK.add(ChunkPos.asLong(Math.floorDiv(wx, ROUTE_GRID), Math.floorDiv(wz, ROUTE_GRID)));
            }
            px = tx;
            pz = tz;
        }
    }

    private static void paveUnder(ServerLevel level, CitizenEntity trader, TraderSimSession s) {
        BlockPos feet = trader.blockPosition();
        if (feet.equals(s.lastRoadPos)) return;
        s.lastRoadPos = feet;
        if (s.adopted && SettlementData.get(level)
                .getByChunk(new ChunkPos(feet).toLong()) != null) {
            return;
        }
        pave3Wide(level, feet.getX(), feet.getZ());
    }

    private static void pave3Wide(ServerLevel level, int cx, int cz) {
        paveColumn(level, cx, cz);
        paveColumn(level, cx + 1, cz);
        paveColumn(level, cx - 1, cz);
        paveColumn(level, cx, cz + 1);
        paveColumn(level, cx, cz - 1);
    }

    public static void paveColumn(ServerLevel level, int x, int z) {
        BlockPos ground = new BlockPos(x, groundY(level, x, z) - 1, z);
        BlockState surface = level.getBlockState(ground);
        if (!surface.blocksMotion()) return;
        if (surface.is(BlockTags.LEAVES) || surface.is(BlockTags.LOGS)) return;
        if (!level.getFluidState(ground.above()).isEmpty()) return;
        if (isRoad(surface)) return;
        level.setBlock(ground, roadMaterial(level.getRandom()), 2);
    }

    public static boolean isRoad(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT);
    }

    private static BlockState roadMaterial(RandomSource r) {
        int roll = r.nextInt(100);
        if (roll < 70) return Blocks.DIRT_PATH.defaultBlockState();
        if (roll < 85) return Blocks.GRAVEL.defaultBlockState();
        return Blocks.COARSE_DIRT.defaultBlockState();
    }

    private static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int min = level.getMinBuildHeight() + 1;
        for (int i = 0; i < 16 && y > min; i++) {
            BlockState below = level.getBlockState(m.set(x, y - 1, z));
            if (below.blocksMotion() && !below.is(BlockTags.LEAVES) && !below.is(BlockTags.LOGS)) break;
            y--;
        }
        return y;
    }

    private static void stripGoals(CitizenEntity trader) {
        // Reflection is stable: the NeoForge runtime uses Mojang field names; both selectors are protected in Mob.
        for (String field : new String[] { "goalSelector", "targetSelector" }) {
            try {
                Field f = Mob.class.getDeclaredField(field);
                f.setAccessible(true);
                ((GoalSelector) f.get(trader)).removeAllGoals(g -> true);
            } catch (ReflectiveOperationException | ClassCastException e) {
                LOGGER.warn("[TraderSim] couldn't clear {} — movement may be janky", field, e);
            }
        }
    }

    private static double horiz(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void resetLeg(TraderSimSession s) {
        s.bestDist = Double.MAX_VALUE;
        s.noProgressTicks = 0;
        s.nudges = 0;
    }

    private static int[] lookAhead(Entity trader, int[] wp, double dist) {
        double dx = (wp[0] + 0.5) - trader.getX();
        double dz = (wp[1] + 0.5) - trader.getZ();
        double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        double look = Math.min(dist, len);
        return new int[] {
            (int) Math.floor(trader.getX() + dx / len * look),
            (int) Math.floor(trader.getZ() + dz / len * look)
        };
    }

    private static int[] nearestDryToward(ServerLevel level, Entity from, int[] toward, int maxDist) {
        double dx = (toward[0] + 0.5) - from.getX();
        double dz = (toward[1] + 0.5) - from.getZ();
        double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        for (int dist = 1; dist <= maxDist; dist++) {
            int cx = (int) Math.floor(from.getX() + dx / len * dist);
            int cz = (int) Math.floor(from.getZ() + dz / len * dist);
            if (waterDepth(level, cx, cz) == 0) return new int[] { cx, cz };
        }
        return null;
    }

    private static int waterDepth(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int depth = 0;
        for (int y = top; y > min; y--) {
            if (!level.getFluidState(m.set(x, y, z)).is(FluidTags.WATER)) break;
            depth++;
        }
        return depth;
    }

    private static boolean isDeepWaterColumn(ServerLevel level, int x, int z) {
        return waterDepth(level, x, z) > WADE_DEPTH;
    }

    private static Boat spawnBoat(ServerLevel level, double x, double y, double z, float yaw) {
        Boat boat = EntityType.BOAT.create(level);
        if (boat == null) return null;
        boat.setVariant(Boat.Type.OAK);
        boat.moveTo(x, y, z, yaw, 0.0F);
        if (!level.addFreshEntity(boat)) return null;
        return boat;
    }

    private static void driveBoat(Boat boat, int[] wp) {
        double dx = (wp[0] + 0.5) - boat.getX();
        double dz = (wp[1] + 0.5) - boat.getZ();
        double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        boat.setYRot(yaw);
        boat.yRotO = yaw;
        Vec3 dm = boat.getDeltaMovement();
        boat.setDeltaMovement(dx / len * BOAT_SPEED, dm.y, dz / len * BOAT_SPEED);
        boat.hasImpulse = true;
        boat.setPaddleState(true, true);
    }

    private static void broadcastProgress(MinecraftServer server, TraderSimSession s) {
        if (s.initiator == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(s.initiator);
        if (p == null) return;
        double px = s.gx;
        double pz = s.gz;
        if (!s.ghost && s.traderId != null) {
            Entity e = server.overworld().getEntity(s.traderId);
            if (e != null) {
                px = e.getX();
                pz = e.getZ();
            }
        }
        double dEnd = horiz(px, pz, s.end.getX() + 0.5, s.end.getZ() + 0.5);
        int pct = (int) Math.round(100.0 * Math.max(0.0, Math.min(1.0, 1.0 - dEnd / Math.max(1.0, s.totalDist))));
        int roadPts = 0;
        for (List<int[]> v : PENDING_ROAD.values()) roadPts += v.size();
        p.displayClientMessage(Component.literal(String.format(
                "Trader %d%% — (%d, %d) — leg %d/%d — %s — road %d",
                pct, (int) Math.floor(px), (int) Math.floor(pz), s.index + 1, s.waypoints.size(),
                s.ghost ? "ghost" : "real", roadPts))
            .withStyle(ChatFormatting.YELLOW), true);
    }

    private static void message(MinecraftServer server, TraderSimSession s, String text) {
        Component c = Component.literal("[TraderSim] " + text).withStyle(ChatFormatting.GOLD);
        if (s.initiator != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.initiator);
            if (p != null) p.sendSystemMessage(c);
        }
        server.sendSystemMessage(c);
    }
}
