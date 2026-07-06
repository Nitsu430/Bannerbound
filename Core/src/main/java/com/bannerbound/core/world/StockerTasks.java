package com.bannerbound.core.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.entity.DropOffContainers;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The Stocker's settlement task board: an enqueued, shared FIFO of haul orders that stockers
 * claim one at a time. Tasks are DERIVED STATE -- regenerated every REGEN_INTERVAL ticks from the
 * live inventory picture, never persisted -- so the board self-heals around anything the player
 * moves by hand, and queue order (not a stocker's whim) decides what hauls first. Claiming the
 * oldest open task from the shared queue balances load across however many stockers are employed;
 * a claimed task orphaned past CLAIM_TIMEOUT_TICKS (stocker died / changed job) is dropped and
 * regenerated, and MAX_OPEN_TASKS caps the open board so it never floods. Each Task sets exactly
 * one of source workshop/pos and exactly one of dest workshop/pos; the "lane" string is its dedup
 * key so a claimed task is recognised across regens regardless of shape. Board access
 * (claim/complete/release/snapshot/regen) is synchronized: goal threads and the server-tick regen
 * share one queue.
 *
 * <p>Task flows, generated per regen in this ORDER (earlier flows win the MAX_OPEN budget first):
 * <ul>
 *   <li>SUPPLY -- a staffed workshop's executors report missingInputs; the board finds those items
 *       in stockpiles / loose drop-off containers (never another workshop's storage) and queues
 *       container -> workshop hauls. An input NOBODY stocks asks the chain instead: a workshop that
 *       can PRODUCE it gets a derived autoOrder. Hauls use the BUFFERED count (missing, pre-stock
 *       raws in fewer trips); chain PRODUCTION orders use the TRUE un-buffered demand, so one wanted
 *       final doesn't pull a buffer's worth of intermediates (a bow doesn't order a buffer of
 *       string).</li>
 *   <li>CLEAR -- storage items no wanted craft retains (retainedItems) are surplus and queued
 *       workshop -> stockpile (or the item's role tool depot). An ingredient of a wanted craft is
 *       NEVER hauled out; once min-stock is met and no orders remain it becomes surplus and ships.</li>
 *   <li>TOOL NEEDS (government-only) -- a toolless worker self-equips from any pooled allowed tool
 *       already in stock, so only when NONE exists anywhere does the chain order one crafted.</li>
 *   <li>HOME SUPPLY (government-only) -- deliver demanded luxuries into home pantries. Home
 *       containers are excluded from sources/drain so a delivered luxury stays put and keeps the
 *       demand satisfied; only a small HOME_PANTRY_AMOUNT is delivered since mere presence satisfies
 *       and nothing consumes it yet.</li>
 *   <li>DRAIN -- empty every loose drop-off container (baskets, chests, outpost chests) into the
 *       stockpile completely, one task per container per regen; baskets are buffers, the stockpile
 *       IS settlement inventory (no fill threshold, user decision). Runs last so feeding workshops
 *       wins; never drains a stockpile into itself.</li>
 * </ul>
 *
 * <p>Chain autoOrders are reconciled onto each producer every regen: counts follow the live
 * deficit and an order whose need vanished is revoked (completed crafts stay made). Anarchy has no
 * stockers, hence the tool/home passes are government-only.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class StockerTasks {
    private static final int REGEN_INTERVAL = 200;
    private static final long CLAIM_TIMEOUT_TICKS = 1_200;
    private static final int MAX_OPEN_TASKS = 32;
    private static final int MAX_HAUL = 64;
    private static final int HOME_PANTRY_AMOUNT = 8;

    public static final class Task {
        public final UUID id = UUID.randomUUID();
        public final String lane;
        @Nullable public final UUID sourceWorkshopId;
        @Nullable public final BlockPos sourcePos;
        @Nullable public final UUID destWorkshopId;
        @Nullable public final BlockPos destPos;
        public final Item item;
        public final int count;
        @Nullable UUID claimedBy;
        long claimTick;

        Task(String lane, @Nullable UUID sourceWorkshopId, @Nullable BlockPos sourcePos,
             @Nullable UUID destWorkshopId, @Nullable BlockPos destPos, Item item, int count) {
            this.lane = lane;
            this.sourceWorkshopId = sourceWorkshopId;
            this.sourcePos = sourcePos;
            this.destWorkshopId = destWorkshopId;
            this.destPos = destPos;
            this.item = item;
            this.count = count;
        }
    }

    private static final Map<UUID, Deque<Task>> BOARDS = new HashMap<>();
    private static int tickCounter;

    private StockerTasks() {
    }

    @Nullable
    public static Task claim(ServerLevel sl, Settlement settlement, UUID citizenId) {
        return claim(sl, settlement, citizenId, t -> true);
    }

    @Nullable
    public static synchronized Task claim(ServerLevel sl, Settlement settlement, UUID citizenId,
                                          java.util.function.Predicate<Task> acceptable) {
        Deque<Task> queue = BOARDS.get(settlement.id());
        if (queue == null) return null;
        for (Task t : queue) {
            if (t.claimedBy == null && acceptable.test(t)) {
                t.claimedBy = citizenId;
                t.claimTick = sl.getGameTime();
                return t;
            }
        }
        return null;
    }

    public static synchronized void complete(Settlement settlement, Task task) {
        Deque<Task> queue = BOARDS.get(settlement.id());
        if (queue != null) queue.remove(task);
    }

    public static synchronized void release(Settlement settlement, Task task) {
        complete(settlement, task);
    }

    @Nullable
    public static UUID claimedBy(Task task) {
        return task.claimedBy;
    }

    public static synchronized List<Task> snapshot(UUID settlementId) {
        Deque<Task> queue = BOARDS.get(settlementId);
        return queue == null ? List.of() : new ArrayList<>(queue);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % REGEN_INTERVAL != 0) return;
        ServerLevel sl = event.getServer().overworld();
        for (Settlement s : SettlementData.get(sl).all()) {
            regen(sl, s);
        }
    }

    private static synchronized void regen(ServerLevel sl, Settlement s) {
        Deque<Task> queue = BOARDS.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
        long now = sl.getGameTime();
        queue.removeIf(t -> t.claimedBy == null || now - t.claimTick > CLAIM_TIMEOUT_TICKS);

        Set<String> inFlight = new HashSet<>();
        for (Task t : queue) inFlight.add(laneKey(t));

        BlockPos stockpileRack = firstUsableStockpile(s);
        Map<com.bannerbound.core.api.settlement.Home, List<BlockPos>> homePantries = collectHomePantries(sl, s);
        Set<BlockPos> homeContainers = new HashSet<>();
        for (List<BlockPos> v : homePantries.values()) homeContainers.addAll(v);
        List<SourceContainer> sources = collectSources(sl, s, stockpileRack, homeContainers);

        Map<UUID, Map<String, Integer>> chainNeeds = new HashMap<>();
        Map<UUID, Map<String, String>> chainSources = new HashMap<>();

        int open = 0;
        for (Workshop w : s.workshops().values()) {
            if (open >= MAX_OPEN_TASKS) break;
            if (w.status() != Workshop.Status.VALID || w.workers().isEmpty()) continue;

            Map<Item, Integer> missing = new LinkedHashMap<>();
            Map<Item, Integer> trueDemand = new LinkedHashMap<>();
            Set<Item> retained = new HashSet<>();
            for (BlockPos p : w.workBlocks()) {
                WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
                if (def == null || def.executor() == null) continue;
                retained.addAll(def.executor().retainedItems(sl, s, w, p));
                for (ItemStack m : def.executor().missingInputs(sl, s, w, p)) {
                    missing.merge(m.getItem(), m.getCount(), Math::max);
                }
                for (ItemStack m : def.executor().trueInputDemand(sl, s, w, p)) {
                    trueDemand.merge(m.getItem(), m.getCount(), Math::max);
                }
            }

            Set<Item> needs = new java.util.LinkedHashSet<>(missing.keySet());
            needs.addAll(trueDemand.keySet());
            for (Item item : needs) {
                if (open >= MAX_OPEN_TASKS) break;
                int haul = missing.getOrDefault(item, 0);
                int produce = trueDemand.getOrDefault(item, haul);
                String lane = "supply:" + w.id() + ":" + key(item);
                if (inFlight.contains(lane)) continue;
                SourceContainer src = haul > 0 ? findSourceWith(sl, sources, item) : null;
                if (src == null) {
                    int count = produce > 0 ? produce : haul;
                    if (count <= 0) continue;
                    Workshop producer = findProducer(sl, s, w, item);
                    if (producer != null) {
                        String itemId = key(item);
                        chainNeeds.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                            .merge(itemId, Math.min(count, MAX_HAUL), Integer::sum);
                        chainSources.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                            .putIfAbsent(itemId, w.id().toString());
                    }
                    continue;
                }
                queue.add(new Task(lane, null, src.pos, w.id(), null,
                    item, Math.min(haul, MAX_HAUL)));
                inFlight.add(lane);
                open++;
            }

            for (Map.Entry<Item, Integer> e : storageCounts(sl, w).entrySet()) {
                if (open >= MAX_OPEN_TASKS) break;
                if (retained.contains(e.getKey())) continue;
                BlockPos dest = stockpileRack;
                if (dest == null) continue;
                String lane = "clear:" + w.id() + ":" + key(e.getKey());
                if (inFlight.contains(lane)) continue;
                queue.add(new Task(lane, w.id(), null, null, dest,
                    e.getKey(), Math.min(e.getValue(), MAX_HAUL)));
                inFlight.add(lane);
                open++;
            }
        }

        if (s.governmentType() != Settlement.Government.NONE) {
            Map<String, Integer> roleNeeds = new LinkedHashMap<>();
            Map<String, UUID> roleNeedSource = new LinkedHashMap<>();
            for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                if (!(sl.getEntity(c.entityId())
                        instanceof com.bannerbound.core.entity.CitizenEntity ce)) continue;
                if (ce.getJobType() == null || ce.hasJobTool()) continue;
                String role = com.bannerbound.core.social.JobIcons.roleForJob(ce.getJobType());
                if (role == null) continue;
                if (com.bannerbound.core.entity.JobTools.allowedToolsFor(s, role).isEmpty()) continue;
                roleNeeds.merge(role, 1, Integer::sum);
                roleNeedSource.putIfAbsent(role, ce.getUUID());
            }
            for (Map.Entry<String, Integer> need : roleNeeds.entrySet()) {
                if (open >= MAX_OPEN_TASKS) break;
                String role = need.getKey();
                List<Item> allowed = com.bannerbound.core.entity.JobTools.allowedToolsFor(s, role);
                boolean exists = false;
                for (Item t : allowed) {
                    if (findSourceWith(sl, sources, t) != null) { exists = true; break; }
                }
                if (exists) continue;
                for (Item t : allowed) {
                    Workshop producer = findProducer(sl, s, null, t);
                    if (producer == null) continue;
                    String itemId = key(t);
                    chainNeeds.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                        .merge(itemId, need.getValue(), Integer::sum);
                    chainSources.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                        .putIfAbsent(itemId, roleNeedSource.get(role).toString());
                    break;
                }
            }
        }

        if (s.governmentType() != Settlement.Government.NONE) {
            for (Map.Entry<com.bannerbound.core.api.settlement.Home, List<BlockPos>> e : homePantries.entrySet()) {
                if (open >= MAX_OPEN_TASKS) break;
                com.bannerbound.core.api.settlement.Home home = e.getKey();
                if (home.status() != com.bannerbound.core.api.settlement.Home.Status.VALID) continue;
                BlockPos dest = e.getValue().get(0);
                for (com.bannerbound.core.api.settlement.HomeDemand.DemandState d : home.cachedDemands()) {
                    if (open >= MAX_OPEN_TASKS) break;
                    if (!d.demand().isLuxury() || d.met()) continue;
                    String lane = "home:" + home.id() + ":" + d.demand().suffix();
                    if (inFlight.contains(lane)) continue;
                    TaggedSource ts = findSourceWithTag(sl, sources, d.demand().luxuryTag());
                    if (ts == null) continue;
                    queue.add(new Task(lane, null, ts.src().pos(), null, dest, ts.item(), HOME_PANTRY_AMOUNT));
                    inFlight.add(lane);
                    open++;
                }
            }
        }

        if (stockpileRack != null) {
            for (SourceContainer src : sources) {
                if (open >= MAX_OPEN_TASKS) break;
                if (src.stockpile()) continue;
                net.minecraft.world.Container c =
                    com.bannerbound.core.entity.DropOffContainers.resolveDropOff(sl, src.pos());
                if (c == null) continue;
                Item biggest = null;
                int biggestCount = 0;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack stack = c.getItem(i);
                    if (!stack.isEmpty() && stack.getCount() > biggestCount) {
                        biggest = stack.getItem();
                        biggestCount = stack.getCount();
                    }
                }
                if (biggest == null) continue;
                BlockPos dest = stockpileRack;
                if (dest.equals(src.pos())) continue;
                String lane = "drain:" + src.pos().asLong() + ":" + key(biggest);
                if (inFlight.contains(lane)) continue;
                queue.add(new Task(lane, null, src.pos(), null, dest,
                    biggest, Math.min(biggestCount, MAX_HAUL)));
                inFlight.add(lane);
                open++;
            }
        }

        boolean dirty = false;
        for (Workshop p : s.workshops().values()) {
            Map<String, Integer> needs = chainNeeds.getOrDefault(p.id(), Map.of());
            Map<String, String> needSources = chainSources.getOrDefault(p.id(), Map.of());
            if (p.autoOrders().keySet().retainAll(needs.keySet())) dirty = true;
            p.autoOrderSources().keySet().retainAll(needs.keySet());
            for (Map.Entry<String, Integer> e : needs.entrySet()) {
                Integer cur = p.autoOrders().get(e.getKey());
                int want = Math.min(e.getValue(), MAX_HAUL);
                if (cur == null || cur != want) {
                    p.autoOrders().put(e.getKey(), want);
                    dirty = true;
                }
                p.autoOrderSources().put(e.getKey(), needSources.get(e.getKey()));
            }
        }
        if (dirty) SettlementData.get(sl).setDirty();
    }

    @Nullable
    private static Workshop findProducer(ServerLevel sl, Settlement s, @Nullable Workshop requester,
                                         Item item) {
        if (requester != null && producesItem(sl, requester, item)) return requester;
        for (Workshop p : s.workshops().values()) {
            if (p == requester) continue;
            if (p.status() != Workshop.Status.VALID || p.workers().isEmpty()) continue;
            if (producesItem(sl, p, item)) return p;
        }
        return null;
    }

    private static boolean producesItem(ServerLevel sl, Workshop w, Item item) {
        for (BlockPos p : w.workBlocks()) {
            WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
            if (def == null || def.executor() == null) continue;
            for (ItemStack out : def.executor().possibleOutputs(sl, p)) {
                if (out.is(item)) return true;
            }
        }
        return false;
    }

    private static String laneKey(Task t) {
        return t.lane;
    }

    private static String key(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Map<Item, Integer> storageCounts(ServerLevel sl, Workshop w) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (ItemStack s : WorkshopStorage.contents(sl, w)) {
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return out;
    }

    @Nullable
    private static BlockPos firstUsableStockpile(Settlement s) {
        for (Stockpile sp : s.stockpiles().values()) {
            if (sp.valid() && !sp.containers().isEmpty()) return sp.pos();
        }
        return null;
    }

    private record SourceContainer(BlockPos pos, boolean stockpile) {
    }

    private static List<SourceContainer> collectSources(ServerLevel sl, Settlement s,
                                                        @Nullable BlockPos stockpileRack,
                                                        Set<BlockPos> homeContainers) {
        Set<BlockPos> excluded = new HashSet<>();
        for (Workshop w : s.workshops().values()) excluded.addAll(w.storageBlocks());
        excluded.addAll(homeContainers);
        List<SourceContainer> out = new ArrayList<>();
        for (Stockpile sp : s.stockpiles().values()) {
            if (sp.valid() && !sp.containers().isEmpty()) {
                out.add(new SourceContainer(sp.pos(), true));
                excluded.addAll(sp.containers());
            }
        }
        List<Long> sourceChunks = new ArrayList<>(s.claimedChunks());
        sourceChunks.addAll(s.workingClaims());
        for (long packed : sourceChunks) {
            ChunkPos cp = new ChunkPos(packed);
            if (!sl.hasChunk(cp.x, cp.z)) continue;
            LevelChunk chunk = sl.getChunk(cp.x, cp.z);
            for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = e.getKey();
                if (excluded.contains(pos)) continue;
                if (!(e.getValue() instanceof Container)) continue;
                if (!DropOffContainers.isDropOffBlock(sl, pos)) continue;
                if (DropOffContainers.isWildStorage(sl, pos)) continue; // never loot structure/loot chests under the claim
                out.add(new SourceContainer(pos, false));
            }
        }
        return out;
    }

    @Nullable
    private static SourceContainer findSourceWith(ServerLevel sl, List<SourceContainer> sources,
                                                  Item item) {
        for (SourceContainer src : sources) {
            Container c = DropOffContainers.resolveDropOff(sl, src.pos());
            if (c == null) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(item)) return src;
            }
        }
        return null;
    }

    private record TaggedSource(SourceContainer src, Item item) {
    }

    @Nullable
    private static TaggedSource findSourceWithTag(ServerLevel sl, List<SourceContainer> sources,
                                                  net.minecraft.tags.TagKey<Item> tag) {
        for (SourceContainer src : sources) {
            Container c = DropOffContainers.resolveDropOff(sl, src.pos());
            if (c == null) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.isEmpty() && st.is(tag)) return new TaggedSource(src, st.getItem());
            }
        }
        return null;
    }

    private static Map<com.bannerbound.core.api.settlement.Home, List<BlockPos>> collectHomePantries(
            ServerLevel sl, Settlement s) {
        Map<com.bannerbound.core.api.settlement.Home, List<BlockPos>> out = new HashMap<>();
        for (com.bannerbound.core.api.settlement.Home h : s.homes().values()) {
            List<BlockPos> containers = com.bannerbound.core.api.settlement.Homes.deliverableContainers(sl, h);
            if (!containers.isEmpty()) out.put(h, containers);
        }
        return out;
    }
}
