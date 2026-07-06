package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.api.settlement.food.LarderService;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.entity.MinerWorkGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The outpost supply line as a ghost simulation -- the "believed, not counted" model the trader
 * proves (TraderSimManager), applied to the stocker so a remote outpost keeps producing AND being
 * hauled home even when nobody is near it. Registered on the server tick bus.
 *
 * <p>Two entity-free layers. ACCRUAL: while an outpost chunk is UNLOADED, ore dead-reckons into the
 * settlement's persisted per-outpost balance (Settlement.outpostAccrued), paced by the vein's
 * deterministic richness (no chunk read). It is SKIPPED while the chunk is loaded, where the real
 * miner produces -- that split is the double-count guard. GHOST HAUL: when the settlement is loaded
 * (a stocker is home to send) and an outpost has banked >= DISPATCH_THRESHOLD, a ghost stocker runs a
 * round trip whose duration is the real travel distance; its position is just a clock fraction along
 * the route. It COLLECTS (snapshots) the bank at the half-way point and DELIVERS into home storage at
 * the end. The bank is debited ONLY on delivery, so a haul interrupted by a restart re-dispatches --
 * never a dupe, never a loss.
 *
 * <p>Layer 2 (realize-on-observe): while a player is near the ghost's live position a real,
 * never-saved puppet stocker is materialized and slid along so the trip can be watched; it despawns
 * when the player leaves. Spawn and despawn ranges differ (PUPPET_SPAWN_RANGE < PUPPET_DESPAWN_RANGE)
 * as hysteresis so the puppet does not flicker at the boundary. Purely cosmetic -- the ghost clock is
 * authoritative and the haul completes whether or not anyone watches. syncPuppets slides the puppets
 * every tick (so following one looks like a walk, not a once-a-second teleport); the heavy accrual /
 * dispatch / collect / deliver work runs once per TICK_INTERVAL.
 *
 * <p>ACTIVE (in-flight hauls keyed by outpost chunk) is NOT persisted: the accrued bank is the source
 * of truth. Constants: STOCKER_SPEED 0.2 blocks/tick ~= citizen walk speed; YIELD_BY_RICHNESS
 * {poor,normal,rich} mirrors the loaded miner's output; PENDING_CAP bounds an endless absence;
 * MIN_TRIP_TICKS keeps a near outpost's trip a believable few seconds.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class GhostStockerManager {
    private static final int TICK_INTERVAL = 20;
    private static final int ACCRUE_INTERVAL = 200;
    private static final int[] YIELD_BY_RICHNESS = {1, 2, 3};
    private static final int PENDING_CAP = 2048;
    private static final int DISPATCH_THRESHOLD = 16;
    private static final double STOCKER_SPEED = 0.2;
    private static final long MIN_TRIP_TICKS = 200;
    private static final double PUPPET_SPAWN_RANGE = 56.0;
    private static final double PUPPET_DESPAWN_RANGE = 80.0;

    private static final Map<Long, Haul> ACTIVE = new HashMap<>();

    private GhostStockerManager() {
    }

    private static final class Haul {
        final UUID settlement;
        final long outpostChunk;
        final Vec3 outpostPos;
        final Vec3 stockpilePos;
        final Item item;
        final long startTick;
        final long tripTicks;
        int carried;
        boolean collected;
        UUID puppet;

        Haul(UUID settlement, long outpostChunk, Vec3 outpostPos, Vec3 stockpilePos,
             Item item, long startTick, long tripTicks) {
            this.settlement = settlement;
            this.outpostChunk = outpostChunk;
            this.outpostPos = outpostPos;
            this.stockpilePos = stockpilePos;
            this.item = item;
            this.startTick = startTick;
            this.tripTicks = tripTicks;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (ACTIVE.isEmpty() && server.getTickCount() % TICK_INTERVAL != 0) return;
        long now = overworld.getGameTime();
        SettlementData data = SettlementData.get(overworld);

        syncPuppets(overworld, data, now);

        if (server.getTickCount() % TICK_INTERVAL == 0) {
            boolean accrue = server.getTickCount() % ACCRUE_INTERVAL == 0;
            boolean dirty = false;
            for (Settlement s : data.all()) {
                if (accrue) dirty |= accrueUnloadedOutposts(overworld, s);
                dispatchHauls(overworld, s, now);
            }
            dirty |= advanceHauls(overworld, data, now);
            if (dirty) data.setDirty();
        }
    }

    private static void syncPuppets(ServerLevel sl, SettlementData data, long now) {
        if (ACTIVE.isEmpty()) return;
        for (Haul h : ACTIVE.values()) {
            Settlement s = data.getById(h.settlement);
            if (s == null) continue;
            double progress = Math.max(0.0, Math.min(1.0, (double) (now - h.startTick) / h.tripTicks));
            observePosition(sl, s, h, progress);
        }
    }

    private static boolean accrueUnloadedOutposts(ServerLevel sl, Settlement s) {
        if (s.workingClaims().isEmpty()) return false;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        boolean changed = false;
        for (long packed : s.workingClaims()) {
            ChunkPos cp = new ChunkPos(packed);
            if (sl.hasChunk(cp.x, cp.z)) continue;   // loaded -> real miner produces here; skip (no double count)
            ChunkResource type = ChunkResources.typeAt(sl, cp);
            if (!BoulderLayout.isOreChunk(type)) continue;
            if (BoulderLayout.dropFor(type).isEmpty()) continue;
            if (!hasAssignedMiner(registry, s, cp)) continue;
            int rich = BoulderLayout.richness(sl.getSeed(), cp);
            s.addOutpostAccrued(packed, YIELD_BY_RICHNESS[rich], PENDING_CAP);
            changed = true;
        }
        return changed;
    }

    private static boolean hasAssignedMiner(BlockSelectionRegistry registry, Settlement s, ChunkPos cp) {
        for (BlockSelection sel : registry.getForSettlement(s.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!MinerWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (sel.targetsAllWorkers()) continue;
            if (cp.equals(new ChunkPos(new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) return true;
        }
        return false;
    }

    private static void dispatchHauls(ServerLevel sl, Settlement s, long now) {
        if (s.workingClaims().isEmpty()) return;
        if (!ResearchManager.hasFlag(s, LarderService.STORAGE_RESEARCH_FLAG)) return;
        BlockPos stockpile = loadedStockpilePos(sl, s);
        if (stockpile == null) return;

        for (long packed : s.workingClaims()) {
            if (ACTIVE.containsKey(packed)) continue;
            if (s.outpostAccrued(packed) < DISPATCH_THRESHOLD) continue;
            ChunkPos cp = new ChunkPos(packed);
            ChunkResource type = ChunkResources.typeAt(sl, cp);
            Item drop = BoulderLayout.dropFor(type).orElse(null);
            if (drop == null) continue;
            Vec3 outpostPos = new Vec3(cp.getMinBlockX() + 8.5, stockpile.getY(), cp.getMinBlockZ() + 8.5);
            Vec3 stockVec = new Vec3(stockpile.getX() + 0.5, stockpile.getY(), stockpile.getZ() + 0.5);
            double oneWay = outpostPos.distanceTo(stockVec);
            long trip = Math.max(MIN_TRIP_TICKS, (long) (2.0 * oneWay / STOCKER_SPEED));
            ACTIVE.put(packed, new Haul(s.id(), packed, outpostPos, stockVec, drop, now, trip));
        }
    }

    private static boolean advanceHauls(ServerLevel sl, SettlementData data, long now) {
        boolean dirty = false;
        Iterator<Haul> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Haul h = it.next();
            Settlement s = data.getById(h.settlement);
            if (s == null) { discardPuppet(sl, h); it.remove(); continue; }
            double progress = (double) (now - h.startTick) / h.tripTicks;

            if (!h.collected && progress >= 0.5) {
                // snapshot bank as carried; do NOT debit here -- only delivery debits (lossless re-dispatch on restart)
                h.carried = Math.min(s.outpostAccrued(h.outpostChunk), 64 * 9);
                h.collected = true;
            }

            observePosition(sl, s, h, progress);

            if (progress >= 1.0) {
                if (h.collected && h.carried > 0) {
                    int delivered = deliver(sl, s, h.item, h.carried);
                    if (delivered > 0) {
                        s.takeOutpostAccrued(h.outpostChunk, delivered);   // debit ONLY what landed
                        dirty = true;
                    }
                    if (delivered < h.carried && loadedStockpilePos(sl, s) == null) {
                        continue;
                    }
                }
                discardPuppet(sl, h);
                it.remove();
            }
        }
        return dirty;
    }

    private static int deliver(ServerLevel sl, Settlement s, Item item, int amount) {
        List<Container> homes = loadedHomeContainers(sl, s);
        if (homes.isEmpty()) return 0;
        int remaining = amount;
        int maxStack = new ItemStack(item).getMaxStackSize();
        for (Container c : homes) {
            while (remaining > 0) {
                ItemStack stack = new ItemStack(item, Math.min(remaining, maxStack));
                ItemStack leftover = DropOffContainers.insert(c, stack);
                int inserted = stack.getCount() - leftover.getCount();
                if (inserted <= 0) break;
                remaining -= inserted;
            }
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    private static void observePosition(ServerLevel sl, Settlement s, Haul h, double progress) {
        boolean outbound = progress < 0.5;
        Vec3 pos = outbound
            ? h.stockpilePos.lerp(h.outpostPos, progress * 2.0)
            : h.outpostPos.lerp(h.stockpilePos, (progress - 0.5) * 2.0);

        CitizenEntity puppet = (h.puppet != null && sl.getEntity(h.puppet) instanceof CitizenEntity c
            && !c.isRemoved()) ? c : null;
        if (puppet == null) h.puppet = null;

        double range = puppet != null ? PUPPET_DESPAWN_RANGE : PUPPET_SPAWN_RANGE;
        boolean chunkLoaded = sl.hasChunk(((int) Math.floor(pos.x)) >> 4, ((int) Math.floor(pos.z)) >> 4);
        boolean observed = chunkLoaded
            && sl.getNearestPlayer(pos.x, pos.y, pos.z, range, false) != null;

        if (!observed) { discardPuppet(sl, h); return; }

        if (puppet == null) {
            puppet = ImmigrationManager.spawnSimCitizen(sl, s);
            if (puppet == null) return;
            puppet.setNoAi(true);
            h.puppet = puppet.getUUID();
        }
        int gx = (int) Math.floor(pos.x);
        int gz = (int) Math.floor(pos.z);
        int gy = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
        Vec3 target = outbound ? h.outpostPos : h.stockpilePos;
        float yaw = (float) (Math.toDegrees(Math.atan2(target.z - pos.z, target.x - pos.x)) - 90.0);
        puppet.moveTo(gx + 0.5, gy, gz + 0.5, yaw, 0.0f);
        puppet.setYBodyRot(yaw);
        puppet.setYHeadRot(yaw);
        puppet.setItemSlot(EquipmentSlot.MAINHAND,
            h.collected && h.carried > 0 ? new ItemStack(h.item) : ItemStack.EMPTY);
    }

    private static void discardPuppet(ServerLevel sl, Haul h) {
        if (h.puppet == null) return;
        if (sl.getEntity(h.puppet) instanceof CitizenEntity c) c.discard();
        h.puppet = null;
    }

    private static BlockPos loadedStockpilePos(ServerLevel sl, Settlement s) {
        for (Stockpile sp : s.stockpiles().values()) {
            if (!sp.valid()) continue;
            ChunkPos cp = new ChunkPos(sp.pos());
            if (sl.hasChunk(cp.x, cp.z)) return sp.pos();
        }
        BlockPos pref = s.preferredStoragePos();
        if (pref != null && sl.hasChunk(pref.getX() >> 4, pref.getZ() >> 4)) return pref;
        return null;
    }

    private static List<Container> loadedHomeContainers(ServerLevel sl, Settlement s) {
        List<Container> out = new ArrayList<>();
        for (Stockpile sp : s.stockpiles().values()) {
            if (!sp.valid()) continue;
            ChunkPos cp = new ChunkPos(sp.pos());
            if (!sl.hasChunk(cp.x, cp.z)) continue;
            Container c = DropOffContainers.resolveDropOff(sl, sp.pos());
            if (c != null) out.add(c);
        }
        BlockPos pref = s.preferredStoragePos();
        if (pref != null && sl.hasChunk(pref.getX() >> 4, pref.getZ() >> 4)) {
            Container c = DropOffContainers.resolveDropOff(sl, pref);
            if (c != null && !out.contains(c)) out.add(c);
        }
        return out;
    }
}
