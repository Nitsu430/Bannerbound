package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * A registered community-storage building: a Stockpile Block plus the container blocks enclosed
 * with it inside a fence/wall + roof. Parallel to Home: same per-settlement Map<Long, Stockpile>
 * storage shape, same per-pos validity flag, same NBT round-trip. Auto-scanned, not rod-marked --
 * the enclosure is flooded outward from the block to the surrounding fence ring by
 * StockpileEnclosure.scan(...), which writes back valid, the enclosed containers (capped at
 * MAX_CONTAINERS, mirroring a home's bed cap; StockpileEnclosure.MAX_STORAGE reads this constant)
 * and a Status the right-click terminal surfaces (UNMARKED / NOT_ENCLOSED / NO_GATE / NO_ROOF /
 * TOO_LARGE / NO_CONTAINERS / VALID / TOO_MANY, where TOO_MANY still serves only the first
 * MAX_CONTAINERS). The enclosed inventories ARE the settlement's town storage: StockpileService
 * resolves these positions to live IItemHandlers on demand. Per-stockpile worker toggles
 * (allowWorkerDeposit / allowWorkerTake, default open) govern only autonomous workers -- the player
 * can always hand-move via the screen; showForTrading (default CLOSED, opt-in) exposes contents to
 * settlement-to-settlement trade. Status is persisted by ordinal in NBT (save/load), so append new
 * values only and never reorder.
 */
public final class Stockpile {
    public static final int MAX_CONTAINERS = 8;

    public enum Status {
        UNMARKED,
        NOT_ENCLOSED,
        NO_GATE,
        NO_ROOF,
        TOO_LARGE,
        NO_CONTAINERS,
        VALID,
        TOO_MANY;

        public static Status fromOrdinalOrDefault(int ord) {
            Status[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : UNMARKED;
        }
    }

    private final UUID id;
    private final BlockPos pos;
    private boolean valid;
    private Status status;
    private final List<BlockPos> containers;
    private boolean allowWorkerDeposit = true;
    private boolean allowWorkerTake = true;
    private boolean showForTrading = false;

    public Stockpile(UUID id, BlockPos pos) {
        this.id = id;
        this.pos = pos;
        this.valid = false;
        this.status = Status.UNMARKED;
        this.containers = new ArrayList<>();
    }

    public UUID id() { return id; }
    public BlockPos pos() { return pos; }
    public boolean valid() { return valid; }
    public Status status() { return status; }
    public List<BlockPos> containers() { return containers; }
    public int containerCount() { return containers.size(); }
    public boolean allowWorkerDeposit() { return allowWorkerDeposit; }
    public boolean allowWorkerTake() { return allowWorkerTake; }
    public boolean showForTrading() { return showForTrading; }

    public void setValid(boolean v) { this.valid = v; }
    public void setStatus(Status s) { this.status = s; }
    public void setAllowWorkerDeposit(boolean v) { this.allowWorkerDeposit = v; }
    public void setAllowWorkerTake(boolean v) { this.allowWorkerTake = v; }
    public void setShowForTrading(boolean v) { this.showForTrading = v; }

    public void setContainers(List<BlockPos> next) {
        containers.clear();
        for (BlockPos p : next) containers.add(p.immutable());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putBoolean("Valid", valid);
        tag.putInt("Status", status.ordinal());
        tag.putBoolean("AllowDeposit", allowWorkerDeposit);
        tag.putBoolean("AllowTake", allowWorkerTake);
        tag.putBoolean("ShowTrade", showForTrading);
        if (!containers.isEmpty()) {
            ListTag list = new ListTag();
            for (BlockPos p : containers) {
                CompoundTag c = new CompoundTag();
                c.putInt("X", p.getX());
                c.putInt("Y", p.getY());
                c.putInt("Z", p.getZ());
                list.add(c);
            }
            tag.put("Containers", list);
        }
        return tag;
    }

    public static Stockpile load(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        Stockpile s = new Stockpile(id, pos);
        if (tag.contains("Valid")) s.valid = tag.getBoolean("Valid");
        if (tag.contains("Status")) s.status = Status.fromOrdinalOrDefault(tag.getInt("Status"));
        // Default OPEN when absent so existing stockpiles keep serving workers after the upgrade.
        s.allowWorkerDeposit = !tag.contains("AllowDeposit") || tag.getBoolean("AllowDeposit");
        s.allowWorkerTake = !tag.contains("AllowTake") || tag.getBoolean("AllowTake");
        s.showForTrading = tag.getBoolean("ShowTrade"); // default CLOSED on old saves
        if (tag.contains("Containers")) {
            ListTag list = tag.getList("Containers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                s.containers.add(new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z")));
            }
        }
        return s;
    }
}
