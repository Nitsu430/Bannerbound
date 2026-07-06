package com.bannerbound.core.api.walls;

import java.util.ArrayList;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.walls.DefaultWallDesigns.WallDesignSet;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The walls system's server-side verbs, shared by the {@code /bannerbound walls} commands and the
 * wall-preview screen's payload handlers - one code path for layout, construct, cancel and gate
 * toggling (WALLS_PLAN.md Phase 2/3). All methods assume the caller already resolved the player's
 * settlement.
 *
 * <p>{@link #resolver} is the design lookup to use everywhere a plan that may reference custom
 * designs gets expanded (NOT {@code DefaultWallDesigns::byId}): it prefers a settlement's saved
 * library design over the built-in default so frozen plans keep expanding after edits, and it is
 * variant-aware, so {@code <id>#steps} / {@code <id>#steps_r} resolve to auto-derived step
 * variants of the base design.
 *
 * <p>{@link #computeRefined} layers the player's per-slot refinements (saved design variant,
 * wall-top override, foundation suppression) over the auto layout - the auto chain is only the
 * first draft, and refinements whose footprint no longer matches are silently ignored. Refinements
 * key off a kind-aware per-piece anchor (y = kind.ordinal() + 1, so a corner and a segment sharing
 * a start column stay distinct); that encoding must match {@code PieceLite.refineAnchor()} on the
 * client.
 *
 * <p>Block memory across plan changes lives in {@link #carryObsolete}: the Phase 4 demolition
 * queue is (previous blueprint + previous obsolete + builtWall) minus the new blueprint (reused
 * positions are live wall again) minus world-air (nothing left to demolish).
 */
public final class WallService {

    public record ConstructResult(@Nullable String error, @Nullable WallLayoutEngine.LayoutResult layout) {
        public boolean ok() {
            return error == null;
        }
    }

    private WallService() {
    }

    public static WallDesignSet designs(ServerLevel level, Settlement settlement) {
        WallData walls = WallData.get(level);
        return new WallDesignSet(
            activeOrDefault(walls, settlement, WallDesign.Kind.SEGMENT, DefaultWallDesigns.set().wall()),
            activeOrDefault(walls, settlement, WallDesign.Kind.CORNER, DefaultWallDesigns.set().corner()),
            activeOrDefault(walls, settlement, WallDesign.Kind.GATE, DefaultWallDesigns.set().gate()));
    }

    private static WallDesign activeOrDefault(WallData walls, Settlement settlement,
                                              WallDesign.Kind kind, WallDesign fallback) {
        String id = walls.activeId(settlement.id(), kind);
        if (id == null) return fallback;
        WallDesign design = walls.libraryDesign(settlement.id(), id);
        return design == null ? fallback : design;
    }

    public static java.util.function.Function<String, WallDesign> resolver(ServerLevel level,
                                                                           Settlement settlement) {
        WallData walls = WallData.get(level);
        return id -> WallVariants.resolve(id, baseId -> {
            WallDesign design = walls.libraryDesign(settlement.id(), baseId);
            return design != null ? design : DefaultWallDesigns.byId(baseId);
        });
    }

    public static WallLayoutEngine.LayoutResult computeLayout(ServerLevel level, Settlement settlement) {
        WallDesignSet designs = designs(level, settlement);
        WallData walls = WallData.get(level);
        walls.reconcile(level, settlement.id(), resolver(level, settlement));
        return computeRefined(level, settlement, designs, walls);
    }

    private static WallLayoutEngine.LayoutResult computeRefined(ServerLevel level, Settlement settlement,
                                                                WallDesignSet designs, WallData walls) {
        WallLayoutEngine.LayoutResult result = WallLayoutEngine.compute(level, settlement, designs,
            committedWallPositions(level, settlement, designs),
            walls.gateAnchors(settlement.id()));
        java.util.Map<Long, Integer> tops = walls.topOverrides(settlement.id());
        java.util.Map<Long, String> variants = walls.variantOverrides(settlement.id());
        it.unimi.dsi.fastutil.longs.LongSet fndOff = walls.foundationOff(settlement.id());
        if (tops.isEmpty() && variants.isEmpty() && fndOff.isEmpty()) return result;
        java.util.function.Function<String, WallDesign> resolver = resolver(level, settlement);
        java.util.List<WallPiece> refined = new ArrayList<>(result.plan().pieces().size());
        for (WallPiece piece : result.plan().pieces()) {
            // KIND-AWARE key (y = kind + 1): a corner and a segment can share a start column.
            long key = BlockPos.asLong(piece.startX(), piece.kind().ordinal() + 1, piece.startZ());
            WallPiece out = piece;
            if (!piece.waterGap()) {
                String variantId = piece.kind() == WallDesign.Kind.GATE ? null : variants.get(key);
                if (variantId != null) {
                    WallDesign variant = resolver.apply(variantId);
                    WallDesign base = resolver.apply(piece.designId());
                    if (variant != null && base != null && variant.kind() == piece.kind()
                        && variant.length() == base.length() && variant.depth() == base.depth()) {
                        out = out.withDesignId(variantId);
                    }
                }
                Integer topY = tops.get(key);
                if (topY != null) {
                    WallDesign design = resolver.apply(out.designId());
                    if (design != null) {
                        out = out.withBaseY(topY - design.height() + 1);
                    }
                }
                if (fndOff.contains(key)) {
                    out = out.withNoFoundation();
                }
            }
            refined.add(out);
        }
        return new WallLayoutEngine.LayoutResult(
            new WallPlan(refined, result.plan().obsolete()), result.stats(), result.warnings());
    }

    private static long refineKey(WallPiece piece) {
        return BlockPos.asLong(piece.startX(), piece.kind().ordinal() + 1, piece.startZ());
    }

    public static String cycleVariant(ServerLevel level, Settlement settlement, long anchor) {
        WallData walls = WallData.get(level);
        WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
        for (WallPiece piece : result.plan().pieces()) {
            if (refineKey(piece) != anchor) continue;
            if (piece.waterGap()) return "Water gaps have no blocks to vary.";
            if (piece.kind() == WallDesign.Kind.GATE) return "Gates have no variants.";
            WallDesignSet designs = designs(level, settlement);
            WallDesign active = piece.kind() == WallDesign.Kind.CORNER
                ? designs.corner() : designs.wall();
            java.util.List<String> candidates = new ArrayList<>();
            candidates.add("");
            for (WallDesign d : walls.library(settlement.id())) {
                if (d.kind() == piece.kind() && !d.id().equals(active.id())
                    && d.length() == active.length() && d.depth() == active.depth()) {
                    candidates.add(d.id());
                }
            }
            if (candidates.size() == 1) {
                return "No variants saved for this size — save same-footprint designs ("
                    + active.length() + "×" + active.depth() + ") in the Designer first.";
            }
            String current = walls.variantOverrides(settlement.id()).getOrDefault(anchor, "");
            int index = candidates.indexOf(current);
            String next = candidates.get((Math.max(0, index) + 1) % candidates.size());
            walls.setVariantOverride(settlement.id(), anchor, next.isEmpty() ? null : next);
            if (next.isEmpty()) {
                return "ok:" + active.name() + " (default)";
            }
            WallDesign nextDesign = resolver(level, settlement).apply(next);
            return "ok:" + (nextDesign == null ? next : nextDesign.name());
        }
        return "That wall piece no longer exists — reopen the preview.";
    }

    public static String toggleFoundation(ServerLevel level, Settlement settlement, long anchor) {
        WallData walls = WallData.get(level);
        WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
        for (WallPiece piece : result.plan().pieces()) {
            if (refineKey(piece) != anchor) continue;
            if (piece.waterGap()) return "Water gaps have no foundation.";
            boolean nowOff = walls.toggleFoundationOff(settlement.id(), anchor);
            return nowOff ? "ok:off" : "ok:on";
        }
        return "That wall piece no longer exists — reopen the preview.";
    }

    @Nullable
    public static String refineTop(ServerLevel level, Settlement settlement, long anchor, int delta) {
        WallData walls = WallData.get(level);
        if (delta == 0) {
            walls.setTopOverride(settlement.id(), anchor, null);
            return null;
        }
        WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
        for (WallPiece piece : result.plan().pieces()) {
            if (piece.waterGap()) continue;
            if (BlockPos.asLong(piece.startX(), piece.kind().ordinal() + 1, piece.startZ()) != anchor) continue;
            WallDesign design = resolver(level, settlement).apply(piece.designId());
            if (design == null) return "Unknown design.";
            int currentTop = piece.baseY() + design.height() - 1;
            int newTop = currentTop + delta;
            if (newTop < piece.maxGround() + 1) return "The wall top can't sink below the terrain crest.";
            if (newTop > piece.minGround() + design.height() + 8) return "That needs more than 8 foundation courses.";
            walls.setTopOverride(settlement.id(), anchor, newTop);
            return null;
        }
        return "That wall piece no longer exists — reopen the preview.";
    }

    public static ConstructResult construct(ServerLevel level, Settlement settlement) {
        WallDesignSet designs = designs(level, settlement);
        WallData walls = WallData.get(level);
        walls.reconcile(level, settlement.id(), resolver(level, settlement));
        WallLayoutEngine.LayoutResult result = computeRefined(level, settlement, designs, walls);
        if (result.plan().pieces().isEmpty()) {
            return new ConstructResult("No wall layout — claim territory first.", null);
        }
        if (result.stats().gates() < 1) {
            return new ConstructResult(
                "Mark at least one gate on the wall preview first — a sealed wall traps your citizens.",
                null);
        }
        LongOpenHashSet newPositions = new LongOpenHashSet(
            result.plan().buildBlueprint(resolver(level, settlement)).keySet());
        LongSet carried = carryObsolete(level, walls.plan(settlement.id()),
            walls.builtWall(settlement.id()), resolver(level, settlement), newPositions);
        walls.setPlan(settlement.id(), new WallPlan(result.plan().pieces(), carried));
        WallSync.syncSettlement(level, settlement);
        return new ConstructResult(null, result);
    }

    public static int cancel(ServerLevel level, Settlement settlement) {
        WallData walls = WallData.get(level);
        WallPlan previous = walls.plan(settlement.id());
        if (previous == null) {
            return -1;
        }
        walls.reconcile(level, settlement.id(), resolver(level, settlement));
        LongSet leftovers = carryObsolete(level, previous,
            walls.builtWall(settlement.id()), resolver(level, settlement), LongSets.EMPTY_SET);
        walls.setPlan(settlement.id(), leftovers.isEmpty()
            ? null
            : new WallPlan(new ArrayList<>(), leftovers));
        WallSync.syncSettlement(level, settlement);
        return leftovers.size();
    }

    @Nullable
    public static String toggleGate(ServerLevel level, Settlement settlement, long packedAnchor) {
        WallData walls = WallData.get(level);
        boolean nowSet = walls.toggleGateAnchor(settlement.id(), packedAnchor);
        if (nowSet) {
            WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
            boolean matched = result.plan().pieces().stream().anyMatch(p ->
                p.kind() == WallDesign.Kind.GATE
                && BlockPos.asLong(p.startX(), 0, p.startZ()) == packedAnchor);
            if (!matched) {
                walls.toggleGateAnchor(settlement.id(), packedAnchor); // revert
                return "That spot can't hold a gate — click a wall segment on a straight run.";
            }
        }
        return null;
    }

    public static LongSet committedWallPositions(ServerLevel level, Settlement settlement,
                                                 WallDesignSet designs) {
        WallData walls = WallData.get(level);
        LongOpenHashSet positions = new LongOpenHashSet(walls.builtWall(settlement.id()));
        WallPlan committed = walls.plan(settlement.id());
        if (committed != null) {
            positions.addAll(walls.blueprint(level, settlement.id(), resolver(level, settlement)).keySet());
            positions.addAll(committed.obsolete());
        }
        return positions;
    }

    public static LongSet carryObsolete(ServerLevel level, @Nullable WallPlan previous,
                                        LongSet builtWall,
                                        java.util.function.Function<String, WallDesign> resolver,
                                        LongSet newBlueprintPositions) {
        LongOpenHashSet carried = new LongOpenHashSet(builtWall);
        if (previous != null) {
            carried.addAll(previous.obsolete());
            carried.addAll(previous.buildBlueprint(resolver).keySet());
        }
        carried.removeAll(newBlueprintPositions);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongIterator iterator = carried.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            if (level.getBlockState(cursor).isAir()) {
                iterator.remove();
            }
        }
        return carried;
    }
}
