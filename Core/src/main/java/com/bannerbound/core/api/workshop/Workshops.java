package com.bannerbound.core.api.workshop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.HouseAppealData;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side service for crafter Workshops (see {@code CRAFTER_PLAN.md}): validation against the
 * box union (reusing the housing flood-fill/connectivity code in {@link Homes}), type derivation
 * from contained work blocks, and position -> workshop lookup. Validation runs on commit, on menu
 * open, and when a crafter looks for work - workshops have no anchor block entity to tick, so
 * {@link #validateCached} throttles the hot crafter-scan path (menu-open and rod commits still
 * validate eagerly).
 *
 * <p>Validation checks connectivity, {@code >=1} work block, {@code >=1} storage block, and
 * reachability: a citizen must be able to walk from OUTSIDE the marking to a work block and to
 * storage. The BFS seeds standable cells on the ring just outside the marked bbox (a floating
 * workshop has no such ring, so nothing is reachable) and steps to the 4 horizontal neighbours at
 * dy -1/0/+1. Doors and fence gates are recognized by TAG (never instanceof) so rope gates pass.
 * Only REACHABLE work/storage blocks are cached, so capacity counts stations a citizen can actually
 * stand at. Workshops deliberately do NOT require enclosure - open-air smithy-porch builds are
 * legitimate (unlike houses). NO_HEADROOM vs WORK_BLOCK_UNREACHABLE distinguishes a too-low roof
 * from a genuinely blocked-off station. Multiblock stations count once (only the anchor cell adds a
 * work-slot). Workplace appeal is scored over the box union with the same generic scorer homes use
 * and cached on the workshop for the crafter XP multiplier, menu and floating labels.
 *
 * <p>Demand model. {@link #wantedByMinStock}/{@link #minStockDeficit} implement the min-stock
 * governor: an output is wanted only when it has a positive min-stock row and the settlement census
 * falls short; the deficit COUNT sizes demand for crafted intermediates so one wanted final product
 * doesn't pull a whole input-buffer of sub-assemblies (the "1 sword ordered 4 blades" bug). Orders
 * ({@link #orderedItems}/{@link #orderedCraftCount}) outrank and ignore min-stock - player orders
 * first, then chain-derived auto orders; an order with missing ingredients is skipped, never
 * blocking the queue; ids whose item no longer exists drop out.
 *
 * <p>Waiting-stage contract (READ before adding a crafter with an unattended bake/dry/cure/smelt/
 * ferment step). A "waiting stage" is where a committed unit finishes on its own clock while the
 * worker walks away (hide drying on a rack, dough proving, ore in a non-tended furnace). An order is
 * decremented only when the FINISHED item appears, so during the wait it still reads unfulfilled and
 * a naive demand check will START a second unit for one order - the overproduction bug. Two rules
 * avoid it: (1) COLLECT ungated - the step that collects a finished waiting unit must run regardless
 * of demand (the unit is already made; stranding it also jams the station); (2) START gated by NET
 * demand - the start step calls {@link #wantsAnother} passing {@code inProgress} = units in waiting
 * stages, so orders and deficits both shrink by what is already in flight. Crafters whose final step
 * BLOCKS the worker for its whole duration (the Potter tending a kiln via {@code externallyComplete},
 * or any instant station) have no unattended wait and pass {@code inProgress = 0}. FUTURE walk-away
 * crafters (smith quench/temper, baker proving, fermenter, non-blocking kiln) MUST honour both rules.
 */
public final class Workshops {
    public static final TagKey<Block> STORAGE_TAG = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("bannerbound", "workshop_storage"));

    private Workshops() {
    }

    public static Workshop.Status validate(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) {
            workshop.applyValidation(Workshop.Status.UNMARKED, List.of(), List.of(),
                WorkBlockRegistry.TYPE_NONE);
            workshop.setCachedAppeal(0.0, null);
            return workshop.status();
        }

        Settlement owner = SettlementData.get(sl.getServer().overworld())
            .getById(boxes.get(0).settlementId());
        if (owner != null) {
            HouseAppealData.AppealSnapshot snap = HouseAppealData.scoreUnion(sl, owner, boxes);
            workshop.setCachedAppeal(snap.score(), snap.beauty());
        }

        Set<BlockPos> marked = Homes.collectMarkedSolids(sl, boxes);

        List<BlockPos> work = new ArrayList<>();
        List<BlockPos> storage = new ArrayList<>();
        Set<String> typeIds = new LinkedHashSet<>();
        for (BlockPos p : marked) {
            BlockState state = sl.getBlockState(p);
            WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(state);
            if (def != null) {
                if (def.countsAt(state)) {
                    work.add(p.immutable());
                    typeIds.add(def.workshopTypeId());
                }
            } else if (state.is(STORAGE_TAG)) {
                storage.add(p.immutable());
            }
        }
        String derived = typeIds.isEmpty() ? WorkBlockRegistry.TYPE_NONE
            : typeIds.size() == 1 ? typeIds.iterator().next()
            : WorkBlockRegistry.TYPE_MIXED;

        if (marked.isEmpty()) {
            workshop.applyValidation(Workshop.Status.UNMARKED, work, storage, derived);
            return workshop.status();
        }
        if (!Homes.isConnected(marked)) {
            workshop.applyValidation(Workshop.Status.DISCONNECTED, work, storage, derived);
            return workshop.status();
        }
        if (work.isEmpty()) {
            workshop.applyValidation(Workshop.Status.NO_WORK_BLOCK, work, storage, derived);
            return workshop.status();
        }
        if (storage.isEmpty()) {
            workshop.applyValidation(Workshop.Status.NO_STORAGE, work, storage, derived);
            return workshop.status();
        }

        Set<BlockPos> reached = reachableCells(sl, marked);
        List<BlockPos> reachableWork = filterAdjacent(work, reached);
        List<BlockPos> reachableStorage = filterAdjacent(storage, reached);
        if (reachableWork.isEmpty()) {
            Workshop.Status reason = lacksHeadroomOnly(sl, work)
                ? Workshop.Status.NO_HEADROOM
                : Workshop.Status.WORK_BLOCK_UNREACHABLE;
            workshop.applyValidation(reason, reachableWork, reachableStorage, derived);
            return workshop.status();
        }
        if (reachableStorage.isEmpty()) {
            workshop.applyValidation(Workshop.Status.STORAGE_UNREACHABLE, reachableWork,
                reachableStorage, derived);
            return workshop.status();
        }
        Workshop.Status extraRequirement = WorkBlockRegistry.validateRequirements(
            typeIds, sl, workshop, marked, reachableWork, reachableStorage);
        if (extraRequirement != null) {
            workshop.applyValidation(extraRequirement, reachableWork, reachableStorage, derived);
            return workshop.status();
        }
        workshop.applyValidation(Workshop.Status.VALID, reachableWork, reachableStorage, derived);
        return workshop.status();
    }

    private static Set<BlockPos> reachableCells(ServerLevel sl, Set<BlockPos> marked) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int rMinX = minX - 1, rMaxX = maxX + 1;
        int rMinY = minY - 1, rMaxY = maxY + 1;
        int rMinZ = minZ - 1, rMaxZ = maxZ + 1;

        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Set<BlockPos> reached = new java.util.HashSet<>();
        for (int y = rMinY; y <= rMaxY; y++) {
            for (int x = rMinX; x <= rMaxX; x++) {
                seed(sl, new BlockPos(x, y, rMinZ), queue, reached);
                seed(sl, new BlockPos(x, y, rMaxZ), queue, reached);
            }
            for (int z = rMinZ; z <= rMaxZ; z++) {
                seed(sl, new BlockPos(rMinX, y, z), queue, reached);
                seed(sl, new BlockPos(rMaxX, y, z), queue, reached);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = c.relative(dir).above(dy);
                    if (n.getX() < rMinX || n.getX() > rMaxX
                        || n.getY() < rMinY || n.getY() > rMaxY
                        || n.getZ() < rMinZ || n.getZ() > rMaxZ) continue;
                    if (reached.contains(n) || !standable(sl, n)) continue;
                    reached.add(n);
                    queue.add(n);
                }
            }
        }
        return reached;
    }

    private static void seed(ServerLevel sl, BlockPos p, java.util.ArrayDeque<BlockPos> queue,
                             Set<BlockPos> reached) {
        if (!reached.contains(p) && standable(sl, p)) {
            reached.add(p);
            queue.add(p);
        }
    }

    private static List<BlockPos> filterAdjacent(List<BlockPos> targets, Set<BlockPos> reached) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos t : targets) {
            boolean ok = false;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1 && !ok; dy++) {
                    if (reached.contains(t.relative(dir).above(dy))) ok = true;
                }
                if (ok) break;
            }
            if (ok) out.add(t);
        }
        return out;
    }

    private static boolean lacksHeadroomOnly(ServerLevel sl, List<BlockPos> work) {
        for (BlockPos t : work) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos feet = t.relative(dir).above(dy);
                    if (passable(sl, feet) && !passable(sl, feet.above()) && !passable(sl, feet.below())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean standable(ServerLevel sl, BlockPos feet) {
        return passable(sl, feet) && passable(sl, feet.above()) && !passable(sl, feet.below());
    }

    private static boolean passable(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        // Doors and fence gates pass by TAG (never instanceof) so rope gates count too.
        if (s.is(net.minecraft.tags.BlockTags.DOORS) || s.is(net.minecraft.tags.BlockTags.FENCE_GATES)) {
            return true;
        }
        return s.getCollisionShape(sl, p).isEmpty();
    }

    public static Workshop.Status validateCached(ServerLevel sl, Workshop workshop, long maxAgeTicks) {
        long now = sl.getGameTime();
        if (now - workshop.lastValidatedTick() < maxAgeTicks) {
            return workshop.status();
        }
        workshop.setLastValidatedTick(now);
        return validate(sl, workshop);
    }

    public static boolean wantedByMinStock(ServerLevel sl, Settlement settlement, Workshop w,
                                           net.minecraft.world.item.ItemStack result) {
        if (w.minStock().isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(result.getItem()).toString();
        Integer min = w.minStock().get(id);
        if (min == null || min <= 0) return false;
        return SettlementItemCensus.count(sl, settlement, result.getItem()) < min;
    }

    public static int minStockDeficit(ServerLevel sl, Settlement settlement, Workshop w,
                                      net.minecraft.world.item.ItemStack result) {
        if (w.minStock().isEmpty()) return 0;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(result.getItem()).toString();
        Integer min = w.minStock().get(id);
        if (min == null || min <= 0) return 0;
        return Math.max(0, min - SettlementItemCensus.count(sl, settlement, result.getItem()));
    }

    public static boolean wantsAnother(ServerLevel sl, Settlement settlement, Workshop w,
                                       net.minecraft.world.item.ItemStack result, int inProgress) {
        int slack = Math.max(0, inProgress);
        if (orderedCraftCount(w, result.getItem()) - slack > 0) return true;
        if (w.minStock().isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(result.getItem()).toString();
        Integer min = w.minStock().get(id);
        if (min == null || min <= 0) return false;
        int deficit = min - SettlementItemCensus.count(sl, settlement, result.getItem());
        return deficit - slack > 0;
    }

    public static List<net.minecraft.world.item.Item> orderedItems(Workshop w) {
        if (w.orders().isEmpty() && w.autoOrders().isEmpty()) return List.of();
        List<net.minecraft.world.item.Item> out =
            new ArrayList<>(w.orders().size() + w.autoOrders().size());
        appendOrderItems(w.orders().keySet(), out);
        appendOrderItems(w.autoOrders().keySet(), out);
        return out;
    }

    public static int orderedCraftCount(Workshop w, net.minecraft.world.item.Item item) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        return Math.max(0, w.orders().getOrDefault(id, 0))
            + Math.max(0, w.autoOrders().getOrDefault(id, 0));
    }

    private static void appendOrderItems(java.util.Collection<String> ids,
                                         List<net.minecraft.world.item.Item> out) {
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            net.minecraft.world.item.Item item =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (item != net.minecraft.world.item.Items.AIR && !out.contains(item)) out.add(item);
        }
    }

    public record Hit(Settlement settlement, Workshop workshop) {
    }

    @Nullable
    public static Hit findAt(ServerLevel sl, BlockPos pos) {
        for (BlockSelection s : BlockSelectionRegistry.get(sl).getAll()) {
            if (s.kind() != BlockSelection.Kind.WORKSHOP || !s.contains(pos)) continue;
            Settlement owner = SettlementData.get(sl.getServer().overworld()).getById(s.settlementId());
            if (owner == null) continue;
            Workshop w = owner.getWorkshop(s.homeId());
            if (w != null) return new Hit(owner, w);
        }
        return null;
    }

    @Nullable
    public static Hit findById(ServerLevel sl, UUID workshopId) {
        if (workshopId == null) return null;
        for (Settlement s : SettlementData.get(sl.getServer().overworld()).all()) {
            Workshop w = s.getWorkshop(workshopId);
            if (w != null) return new Hit(s, w);
        }
        return null;
    }
}
