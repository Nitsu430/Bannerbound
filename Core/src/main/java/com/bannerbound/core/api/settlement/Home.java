package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * A registered residence - a marked region plus the citizens who sleep there. Parallel to
 * {@link Workstation}: same per-settlement {@code Map<Long, Home>} storage shape, same per-pos
 * validity flag, same NBT round-trip. Kept a distinct type (not a {@code Workstation} typed as
 * "house") because residence and employment are orthogonal and should evolve independently.
 *
 * <p>The home has no anchor block: it is defined purely by the Housing Orders rod's HOME
 * selections, whose boxes live in {@link com.bannerbound.core.api.world.BlockSelectionRegistry}
 * (HOME-kind selections matching {@link #id()}), NOT on this record - the registry is the single
 * source of truth, avoiding the drift a duplicated list would invite. {@code pos} is a
 * representative point (a contained bed HEAD when one exists, else the region centroid),
 * recomputed each validation for nearest-home auto-assignment and the resident picker's distance
 * readout.
 *
 * <p>Bed count is the resident cap: recomputed on every successful validation from the
 * freshly flood-filled interior (count of {@code BedBlock} HEAD halves) and persisted only as a
 * snapshot so a save mid-day reloads with the same count. Validation runs from
 * {@link Homes#validate} on commit, on panel open, and from the background {@code HomeRevalidator}
 * sweep; {@code valid} false (also the "no beds" case) blocks auto-assignment and, on a
 * valid->invalid flip, evicts existing residents. Residents are insertion-ordered so
 * {@link #trimToBedCount} evicts least-recently-assigned first when beds drop below head count.
 *
 * <p>Many fields are transient caches refreshed every validation and kept current by the
 * revalidator/{@code ChunkBeautyManager} sweep: interior air volume (crowdedness input), home
 * happiness (drives resident mood thought and nightly reproduction chance), per-demand met/unmet
 * snapshot, and {@code lastScoredStyleHash} - the hash of the (styles, palettes) the cached score
 * was computed under, compared by {@code ChunkBeautyManager.tickAll} so a global culture
 * style/palette change refreshes home scores promptly (they used to stay stale until the next
 * block edit inside the home - the audited cache-invalidation bug). The score cache
 * (cachedScore/beauty/lastScoredTick) IS persisted so post-load happiness reads skip a full
 * recompute until the next scheduled rescan.
 *
 * <p>SAVE FORMAT: {@link Status} is persisted by ordinal (append, never reorder); residents are
 * stored as a TAG_INT_ARRAY list. Both {@code save}/{@code load} tolerate missing keys for
 * forward/backward compatibility.
 */
public final class Home {
    public enum Status {
        UNMARKED,
        BROKEN_DISCONNECTED,
        BROKEN_NOT_ENCLOSED,
        NO_BEDS,
        VALID,
        BROKEN_TOO_BIG;

        public static Status fromOrdinalOrDefault(int ord) {
            Status[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : UNMARKED;
        }
    }

    private final UUID id;
    private BlockPos pos;
    private final List<UUID> residents;
    private int bedCount;
    private boolean valid;
    private double cachedScore;
    private ChunkBeauty cachedBeauty;
    private long lastScoredTick;
    private Status status = Status.UNMARKED;
    private transient long lastValidatedTick = Long.MIN_VALUE;

    public Home(UUID id, BlockPos pos) {
        this.id = id;
        this.pos = pos;
        this.residents = new ArrayList<>();
        this.bedCount = 0;
        this.valid = false;
        this.cachedScore = 0.0;
        this.cachedBeauty = null;
        this.lastScoredTick = -1L;
    }

    public Home(UUID id) {
        this(id, BlockPos.ZERO);
    }

    public UUID id() { return id; }
    public BlockPos pos() { return pos; }
    public void setPos(BlockPos p) { if (p != null) this.pos = p.immutable(); }
    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public long lastValidatedTick() { return lastValidatedTick; }
    public void setLastValidatedTick(long tick) { this.lastValidatedTick = tick; }
    public List<UUID> residents() { return residents; }
    public int bedCount() { return bedCount; }
    public boolean valid() { return valid; }
    public double cachedScore() { return cachedScore; }
    public ChunkBeauty cachedBeauty() { return cachedBeauty; }
    public long lastScoredTick() { return lastScoredTick; }

    private transient int cachedInteriorVolume;
    public int cachedInteriorVolume() { return cachedInteriorVolume; }
    public void setCachedInteriorVolume(int v) { this.cachedInteriorVolume = Math.max(0, v); }

    private transient double cachedHomeHappiness = 50.0;
    public double cachedHomeHappiness() { return cachedHomeHappiness; }
    public void setCachedHomeHappiness(double v) { this.cachedHomeHappiness = Math.max(0.0, Math.min(100.0, v)); }

    private transient List<HomeDemand.DemandState> cachedDemands = List.of();
    public List<HomeDemand.DemandState> cachedDemands() { return cachedDemands; }
    public void setCachedDemands(List<HomeDemand.DemandState> demands) {
        this.cachedDemands = demands == null ? List.of() : demands;
    }

    private transient int lastScoredStyleHash;
    public int lastScoredStyleHash() { return lastScoredStyleHash; }
    public void setLastScoredStyleHash(int hash) { this.lastScoredStyleHash = hash; }

    public void setBedCount(int v) { this.bedCount = Math.max(0, v); }
    public void setValid(boolean v) { this.valid = v; }

    public void setCachedScore(double score, ChunkBeauty beauty, long now) {
        this.cachedScore = score;
        this.cachedBeauty = beauty;
        this.lastScoredTick = now;
    }

    public boolean hasVacancy() {
        return valid && bedCount > 0 && residents.size() < bedCount;
    }

    public boolean addResident(UUID citizenId) {
        if (citizenId == null) return false;
        if (residents.contains(citizenId)) return false;
        if (residents.size() >= bedCount) return false;
        residents.add(citizenId);
        return true;
    }

    public boolean removeResident(UUID citizenId) {
        return citizenId != null && residents.remove(citizenId);
    }

    public List<UUID> trimToBedCount() {
        List<UUID> evicted = new ArrayList<>();
        while (residents.size() > bedCount && !residents.isEmpty()) {
            evicted.add(residents.remove(residents.size() - 1));
        }
        return evicted;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putInt("BedCount", bedCount);
        tag.putBoolean("Valid", valid);
        tag.putInt("Status", status.ordinal());
        tag.putDouble("Score", cachedScore);
        if (cachedBeauty != null) tag.putInt("Beauty", cachedBeauty.ordinal());
        tag.putLong("LastScoredTick", lastScoredTick);
        if (!residents.isEmpty()) {
            ListTag list = new ListTag();
            for (UUID r : residents) list.add(NbtUtils.createUUID(r));
            tag.put("Residents", list);
        }
        return tag;
    }

    public static Home load(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        Home h = new Home(id, pos);
        if (tag.contains("BedCount")) h.bedCount = tag.getInt("BedCount");
        if (tag.contains("Valid")) h.valid = tag.getBoolean("Valid");
        if (tag.contains("Status")) h.status = Status.fromOrdinalOrDefault(tag.getInt("Status"));
        if (tag.contains("Score")) h.cachedScore = tag.getDouble("Score");
        if (tag.contains("Beauty")) {
            int ord = tag.getInt("Beauty");
            ChunkBeauty[] v = ChunkBeauty.values();
            if (ord >= 0 && ord < v.length) h.cachedBeauty = v[ord];
        }
        if (tag.contains("LastScoredTick")) h.lastScoredTick = tag.getLong("LastScoredTick");
        if (tag.contains("Residents")) {
            ListTag list = tag.getList("Residents", Tag.TAG_INT_ARRAY);
            for (int i = 0; i < list.size(); i++) {
                h.residents.add(NbtUtils.loadUUID(list.get(i)));
            }
        }
        return h;
    }
}
