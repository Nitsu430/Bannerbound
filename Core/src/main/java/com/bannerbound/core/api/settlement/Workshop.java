package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.api.workshop.WorkBlockRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * A Workshop: a player-selected, enclosed crafting building worked by crafter citizens (see
 * CRAFTER_PLAN.md). The box geometry lives in BlockSelectionRegistry (kind WORKSHOP, keyed by id()),
 * exactly like homes; this record holds everything else: identity, custom name (empty = show the
 * derived type; a rename sticks even when the type changes), the type derived from the contained
 * work blocks on every validation, validation Status + cached work/storage block lists, assigned
 * workers, and the min-stock map. Stored in Settlement.workshops and persisted with the settlement
 * NBT. There is NO anchor block -- the Workshop Orders rod binds by id and validation runs on commit
 * / menu open / when a crafter looks for work, never from a block entity.
 *
 * <p>Status is the validation outcome surfaced verbatim in the menu. Workshops deliberately do NOT
 * require enclosure (open-air smithy-porch builds are legitimate); they require REACHABILITY --
 * citizens must be able to path to a work block AND to storage (doors/gates/non-colliding blocks
 * pass; floating or walled-off fails). NOT_ENCLOSED is legacy, kept only so saved ordinals stay
 * stable; Status is persisted by ordinal, so append new values and never reorder.
 *
 * <p>Three order layers: the player's orders queue OUTRANKS the min-stock governor (a queued item
 * crafts even when not configured / not in deficit, and a queued item whose ingredients are missing
 * is skipped rather than blocking the queue). autoOrders are chain-derived (a fletchery needing
 * plant string auto-orders it from general crafts), kept separate so the chain can revoke its own
 * orders without ever touching the player's, and crafted AFTER player orders; autoOrderSources
 * records the requesting workshop. fulfillOrder matches ANY craft of the item -- a min-stock
 * fallback craft of the same output fulfils the order just as well.
 *
 * <p>positions locks each worker to one station-family type id so experience accumulates in a single
 * profession bucket instead of smearing across whichever station is free. itemsProduced is a
 * persisted lifetime stat; outputRate is a transient EMA (factor OUTPUT_RATE_ALPHA, ~10s at 1Hz,
 * matching Settlement) rebuilt by tickStats. Appeal caches (workplace appeal -> happier, faster
 * learners) and lastValidatedTick refresh on the blocks-walk validation cadence, which is throttled
 * because the reachability BFS is too heavy to run every crafter think-tick.
 */
public final class Workshop {

    public enum Status {
        VALID,
        UNMARKED,
        DISCONNECTED,
        NOT_ENCLOSED,
        NO_WORK_BLOCK,
        NO_STORAGE,
        WORK_BLOCK_UNREACHABLE,
        STORAGE_UNREACHABLE,
        NO_HEADROOM,
        MISSING_HEAT_SOURCE,
        MISSING_CRAFTING_SURFACE,
        MISSING_TOOL,
        MISSING_CURING_LIQUID;

        public static Status fromOrdinalOrDefault(int ord) {
            Status[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : UNMARKED;
        }
    }

    private final UUID id;
    private String customName = "";
    private String derivedTypeId = WorkBlockRegistry.TYPE_NONE;
    private Status status = Status.UNMARKED;
    private final List<BlockPos> workBlocks = new ArrayList<>();
    private final List<BlockPos> storageBlocks = new ArrayList<>();
    private final List<UUID> workers = new ArrayList<>();
    private final Map<String, Integer> minStock = new LinkedHashMap<>();
    private final Map<String, Integer> orders = new LinkedHashMap<>();
    private final Map<String, Integer> autoOrders = new LinkedHashMap<>();
    private final Map<String, String> autoOrderSources = new LinkedHashMap<>();
    private final Map<UUID, String> positions = new LinkedHashMap<>();
    private transient long lastValidatedTick = Long.MIN_VALUE;
    private double cachedAppealScore;
    private ChunkBeauty cachedAppealBeauty;
    private long itemsProduced = 0L;
    private transient double outputRate = 0.0;
    private transient long lastProducedSnapshot = 0L;
    private static final double OUTPUT_RATE_ALPHA = 0.1;

    public long lastValidatedTick() {
        return lastValidatedTick;
    }

    public void setLastValidatedTick(long tick) {
        this.lastValidatedTick = tick;
    }

    public Workshop(UUID id) {
        this.id = id;
    }

    public UUID id() {
        return id;
    }

    public String customName() {
        return customName;
    }

    public void setCustomName(String name) {
        this.customName = name == null ? "" : name;
    }

    public String derivedTypeId() {
        return derivedTypeId;
    }

    public Status status() {
        return status;
    }

    public List<BlockPos> workBlocks() {
        return workBlocks;
    }

    public List<BlockPos> storageBlocks() {
        return storageBlocks;
    }

    public int capacity() {
        return workBlocks.size();
    }

    public List<UUID> workers() {
        return workers;
    }

    public Map<String, Integer> minStock() {
        return minStock;
    }

    public Map<String, Integer> orders() {
        return orders;
    }

    public Map<String, Integer> autoOrders() {
        return autoOrders;
    }

    public Map<String, String> autoOrderSources() {
        return autoOrderSources;
    }

    public boolean fulfillOrder(String itemId) {
        return fulfillOrder(itemId, 1);
    }

    public boolean fulfillOrder(String itemId, int count) {
        int remainingCount = Math.max(1, count);
        boolean fulfilled = false;
        Integer remaining = orders.get(itemId);
        if (remaining != null) {
            if (remaining <= remainingCount) {
                orders.remove(itemId);
                remainingCount -= remaining;
            } else {
                orders.put(itemId, remaining - remainingCount);
                remainingCount = 0;
            }
            fulfilled = true;
        }
        if (remainingCount <= 0) return fulfilled;
        Integer auto = autoOrders.get(itemId);
        if (auto != null) {
            if (auto <= remainingCount) {
                autoOrders.remove(itemId);
                autoOrderSources.remove(itemId);
            } else {
                autoOrders.put(itemId, auto - remainingCount);
            }
            fulfilled = true;
        }
        return fulfilled;
    }

    public void recordCraftOutput(int count) {
        if (count > 0) itemsProduced += count;
    }

    public long itemsProduced() { return itemsProduced; }

    public double outputRatePerSecond() { return outputRate; }

    public int pendingOrders() {
        int total = 0;
        for (int n : orders.values()) total += n;
        for (int n : autoOrders.values()) total += n;
        return total;
    }

    public void tickStats() {
        double inst = Math.max(0.0, itemsProduced - lastProducedSnapshot);
        outputRate += OUTPUT_RATE_ALPHA * (inst - outputRate);
        lastProducedSnapshot = itemsProduced;
    }

    public String positionOf(UUID citizenId) {
        return positions.get(citizenId);
    }

    public void setPosition(UUID citizenId, String workshopTypeId) {
        positions.put(citizenId, workshopTypeId);
    }

    public void clearPosition(UUID citizenId) {
        positions.remove(citizenId);
    }

    public void prunePositions() {
        positions.keySet().retainAll(workers);
    }

    public double cachedAppealScore() {
        return cachedAppealScore;
    }

    public ChunkBeauty cachedAppealBeauty() {
        return cachedAppealBeauty;
    }

    public void setCachedAppeal(double score, ChunkBeauty beauty) {
        this.cachedAppealScore = score;
        this.cachedAppealBeauty = beauty;
    }

    public void applyValidation(Status newStatus, List<BlockPos> newWorkBlocks,
                                List<BlockPos> newStorageBlocks, String newDerivedTypeId) {
        this.status = newStatus;
        this.workBlocks.clear();
        this.workBlocks.addAll(newWorkBlocks);
        this.storageBlocks.clear();
        this.storageBlocks.addAll(newStorageBlocks);
        this.derivedTypeId = newDerivedTypeId;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        if (!customName.isEmpty()) tag.putString("Name", customName);
        tag.putString("DerivedType", derivedTypeId);
        tag.putInt("Status", status.ordinal());
        tag.put("WorkBlocks", savePosList(workBlocks));
        tag.put("StorageBlocks", savePosList(storageBlocks));
        if (!workers.isEmpty()) {
            ListTag list = new ListTag();
            for (UUID w : workers) list.add(NbtUtils.createUUID(w));
            tag.put("Workers", list);
        }
        if (!minStock.isEmpty()) {
            CompoundTag ms = new CompoundTag();
            for (Map.Entry<String, Integer> e : minStock.entrySet()) ms.putInt(e.getKey(), e.getValue());
            tag.put("MinStock", ms);
        }
        tag.putDouble("Appeal", cachedAppealScore);
        if (cachedAppealBeauty != null) tag.putInt("AppealTier", cachedAppealBeauty.ordinal());
        if (itemsProduced > 0L) tag.putLong("ItemsProduced", itemsProduced);
        if (!orders.isEmpty()) {
            // ListTag of compounds preserves queue order; a CompoundTag (like MinStock) would alphabetise on reload.
            ListTag orderList = new ListTag();
            for (Map.Entry<String, Integer> e : orders.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putString("Item", e.getKey());
                row.putInt("Count", e.getValue());
                orderList.add(row);
            }
            tag.put("Orders", orderList);
        }
        if (!autoOrders.isEmpty()) {
            ListTag autoList = new ListTag();
            for (Map.Entry<String, Integer> e : autoOrders.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putString("Item", e.getKey());
                row.putInt("Count", e.getValue());
                String src = autoOrderSources.get(e.getKey());
                if (src != null) row.putString("Source", src);
                autoList.add(row);
            }
            tag.put("AutoOrders", autoList);
        }
        if (!positions.isEmpty()) {
            ListTag posList = new ListTag();
            for (Map.Entry<UUID, String> e : positions.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putUUID("Worker", e.getKey());
                row.putString("Type", e.getValue());
                posList.add(row);
            }
            tag.put("Positions", posList);
        }
        return tag;
    }

    public static Workshop load(CompoundTag tag) {
        Workshop w = new Workshop(tag.getUUID("Id"));
        w.customName = tag.getString("Name");
        w.derivedTypeId = tag.contains("DerivedType")
            ? tag.getString("DerivedType") : WorkBlockRegistry.TYPE_NONE;
        w.status = Status.fromOrdinalOrDefault(tag.getInt("Status"));
        w.itemsProduced = tag.getLong("ItemsProduced");
        w.lastProducedSnapshot = w.itemsProduced; // avoid a false burst on first tick after load
        loadPosList(tag, "WorkBlocks", w.workBlocks);
        loadPosList(tag, "StorageBlocks", w.storageBlocks);
        if (tag.contains("Workers")) {
            for (Tag t : tag.getList("Workers", Tag.TAG_INT_ARRAY)) {
                w.workers.add(NbtUtils.loadUUID(t));
            }
        }
        if (tag.contains("MinStock")) {
            CompoundTag ms = tag.getCompound("MinStock");
            for (String key : ms.getAllKeys()) w.minStock.put(key, ms.getInt(key));
        }
        if (tag.contains("Appeal")) w.cachedAppealScore = tag.getDouble("Appeal");
        if (tag.contains("AppealTier")) {
            int ord = tag.getInt("AppealTier");
            ChunkBeauty[] v = ChunkBeauty.values();
            if (ord >= 0 && ord < v.length) w.cachedAppealBeauty = v[ord];
        }
        if (tag.contains("Orders")) {
            ListTag orderList = tag.getList("Orders", Tag.TAG_COMPOUND);
            for (int i = 0; i < orderList.size(); i++) {
                CompoundTag row = orderList.getCompound(i);
                int count = row.getInt("Count");
                if (count > 0) w.orders.put(row.getString("Item"), count);
            }
        }
        if (tag.contains("AutoOrders")) {
            ListTag autoList = tag.getList("AutoOrders", Tag.TAG_COMPOUND);
            for (int i = 0; i < autoList.size(); i++) {
                CompoundTag row = autoList.getCompound(i);
                int count = row.getInt("Count");
                if (count <= 0) continue;
                w.autoOrders.put(row.getString("Item"), count);
                if (row.contains("Source")) {
                    w.autoOrderSources.put(row.getString("Item"), row.getString("Source"));
                }
            }
        }
        if (tag.contains("Positions")) {
            ListTag posList = tag.getList("Positions", Tag.TAG_COMPOUND);
            for (int i = 0; i < posList.size(); i++) {
                CompoundTag row = posList.getCompound(i);
                if (row.hasUUID("Worker")) {
                    w.positions.put(row.getUUID("Worker"), row.getString("Type"));
                }
            }
        }
        return w;
    }

    // Packed longs (BlockPos.asLong), not writeBlockPos tags: avoids the tag-format drift that loaded lists back EMPTY (the "capacity 1/0 after relog" bug).
    private static net.minecraft.nbt.LongArrayTag savePosList(List<BlockPos> list) {
        long[] packed = new long[list.size()];
        for (int i = 0; i < list.size(); i++) packed[i] = list.get(i).asLong();
        return new net.minecraft.nbt.LongArrayTag(packed);
    }

    private static void loadPosList(CompoundTag tag, String key, List<BlockPos> out) {
        for (long packed : tag.getLongArray(key)) {
            out.add(BlockPos.of(packed));
        }
    }
}
