package com.bannerbound.core.api.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-side registry of every active {@link BlockSelection} across all settlements, persisted as
 * {@link SavedData} on the overworld so jobs survive restarts. Lookup is by rod UUID; overlap checks
 * scan all selections (fine for typical settlement counts). Every mutator calls {@link #setDirty()}
 * so Minecraft persists on the next save tick, and bumps a monotonic {@code version} counter so
 * workers can detect "did anything change?" by comparing their last-seen version instead of diffing
 * the map - this forces a rescan when a fresh selection is committed mid-cooldown (otherwise a digger
 * might walk back to the campfire before noticing the new job). Overlap rules: the strict variant
 * ({@link #anyOverlapExcluding}) treats every intersection as a conflict for the Foreman's Rod, while
 * {@link #firstConflictingOverlap} permits same-home and same-workshop overlap (the union-of-boxes
 * behaviour). Completed selections are invisible to overlap so a finished job never blocks a fresh
 * selection in the same spot.
 */
public class BlockSelectionRegistry extends SavedData {
    private static final String DATA_NAME = "bannerbound_block_selections";

    // LinkedHashMap preserves insertion order: diggers process selections oldest-commit-first.
    private final Map<UUID, BlockSelection> selections = new LinkedHashMap<>();
    private long version = 0L;

    public long version() { return version; }

    public BlockSelectionRegistry() {
    }

    public static BlockSelectionRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<BlockSelectionRegistry> factory() {
        return new Factory<>(BlockSelectionRegistry::new, BlockSelectionRegistry::load);
    }

    public void register(BlockSelection selection) {
        selections.put(selection.rodId(), selection);
        version++;
        setDirty();
    }

    public BlockSelection unregister(UUID rodId) {
        BlockSelection removed = selections.remove(rodId);
        if (removed != null) {
            version++;
            setDirty();
        }
        return removed;
    }

    public void markCompleted(UUID rodId) {
        BlockSelection s = selections.get(rodId);
        if (s == null || s.completed()) return;
        selections.put(rodId, s.withCompleted(true));
        version++;
        setDirty();
    }

    public BlockSelection get(UUID rodId) {
        return selections.get(rodId);
    }

    public Collection<BlockSelection> getAll() {
        return Collections.unmodifiableCollection(selections.values());
    }

    public List<BlockSelection> findContaining(net.minecraft.core.BlockPos pos, UUID settlementId) {
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (!s.settlementId().equals(settlementId)) continue;
            if (s.contains(pos)) out.add(s);
        }
        return out;
    }

    public List<BlockSelection> getForSettlement(UUID settlementId) {
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (s.settlementId().equals(settlementId)) out.add(s);
        }
        return out;
    }

    public boolean anyOverlapExcluding(BlockSelection candidate, UUID excludeRodId) {
        return anyOverlapExcludingAll(candidate, java.util.Set.of(excludeRodId));
    }

    public boolean anyOverlapExcludingAll(BlockSelection candidate, java.util.Set<UUID> excludeRodIds) {
        for (BlockSelection s : selections.values()) {
            if (excludeRodIds.contains(s.rodId())) continue;
            // Skip completed selections: invisible and finished, must not block a fresh selection here.
            if (s.completed()) continue;
            if (s.intersects(candidate)) return true;
        }
        return false;
    }

    public BlockSelection firstConflictingOverlap(BlockSelection candidate, UUID excludeRodId) {
        for (BlockSelection s : selections.values()) {
            if (s.rodId().equals(excludeRodId)) continue;
            if (!s.intersects(candidate)) continue;
            if (candidate.sameHomeAs(s)) continue;
            if (candidate.sameWorkshopAs(s)) continue;
            return s;
        }
        return null;
    }

    public List<BlockSelection> findByWorkshop(UUID workshopId) {
        if (workshopId == null || BlockSelection.NO_HOME.equals(workshopId)) return Collections.emptyList();
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (s.kind() == BlockSelection.Kind.WORKSHOP && workshopId.equals(s.homeId())) {
                out.add(s);
            }
        }
        return out;
    }

    public int removeAllByWorkshop(UUID workshopId) {
        if (workshopId == null || BlockSelection.NO_HOME.equals(workshopId)) return 0;
        int removed = 0;
        var it = selections.entrySet().iterator();
        while (it.hasNext()) {
            BlockSelection s = it.next().getValue();
            if (s.kind() == BlockSelection.Kind.WORKSHOP && workshopId.equals(s.homeId())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            version++;
            setDirty();
        }
        return removed;
    }

    public List<BlockSelection> findByHome(UUID homeId) {
        if (homeId == null || BlockSelection.NO_HOME.equals(homeId)) return Collections.emptyList();
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (s.kind() == BlockSelection.Kind.HOME && homeId.equals(s.homeId())) {
                out.add(s);
            }
        }
        return out;
    }

    public int removeAllByHome(UUID homeId) {
        if (homeId == null || BlockSelection.NO_HOME.equals(homeId)) return 0;
        int removed = 0;
        var it = selections.entrySet().iterator();
        while (it.hasNext()) {
            BlockSelection s = it.next().getValue();
            if (s.kind() == BlockSelection.Kind.HOME && homeId.equals(s.homeId())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            version++;
            setDirty();
        }
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (BlockSelection s : selections.values()) {
            list.add(s.save());
        }
        tag.put("Selections", list);
        return tag;
    }

    public static BlockSelectionRegistry load(CompoundTag tag, HolderLookup.Provider provider) {
        BlockSelectionRegistry reg = new BlockSelectionRegistry();
        ListTag list = tag.getList("Selections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            BlockSelection s = BlockSelection.load(list.getCompound(i));
            reg.selections.put(s.rodId(), s);
        }
        return reg;
    }
}
