package com.bannerbound.core.api.world;

import com.bannerbound.core.api.settlement.SettlementColor;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * A rectangular block region claimed by some rod, persisted in {@link BlockSelectionRegistry} on the
 * server and mirrored to clients for rendering. Three {@link Kind}s: WORKSTATION (Foreman's Rod;
 * carries {@link #workstationType()}; overlap forbidden), HOME (Home Marker Rod; carries
 * {@link #homeId()}; same-{@code homeId} overlap is allowed - the rod's "Super Glue" union-of-boxes),
 * and WORKSHOP (Workshop Orders rod). WORKSHOP reuses the HOME slots: the workshop id rides in
 * {@link #homeId()} and {@code homePos} stays at the sentinel because a workshop has no anchor block.
 *
 * <p>{@code rodId} is a legacy name - it is the registry map key, not an actual rod id, kept to avoid
 * an NBT migration. Sentinels {@link #NO_CREATOR}/{@link #NO_CITIZEN}/{@link #NO_HOME} are the zero
 * UUID and {@link #NO_HOME_POS} is {@link BlockPos#ZERO}. {@code assignedCitizenId} binds a
 * workstation job to one citizen, or {@link #NO_CITIZEN} for any worker of its type. In NBT, optional
 * fields are written only when non-sentinel and {@code Kind} is absent for WORKSTATION, so pre-v2
 * saves load unchanged (missing Kind -> WORKSTATION).
 */
public record BlockSelection(UUID rodId, UUID settlementId, int colorIndex,
                              BlockPos a, BlockPos b,
                              Kind kind,
                              String workstationType,
                              UUID homeId,
                              BlockPos homePos,
                              boolean completed,
                              UUID creatorId, String seedItemId,
                              UUID assignedCitizenId) {

    public static final UUID NO_CREATOR = new UUID(0L, 0L);
    public static final UUID NO_CITIZEN = new UUID(0L, 0L);
    public static final UUID NO_HOME = new UUID(0L, 0L);
    public static final BlockPos NO_HOME_POS = BlockPos.ZERO;

    public enum Kind {
        WORKSTATION,
        HOME,
        WORKSHOP;

        public static Kind fromOrdinalOrDefault(int ord) {
            Kind[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : WORKSTATION;
        }
    }

    public static BlockSelection workstation(UUID rodId, UUID settlementId, int colorIndex,
                                              BlockPos a, BlockPos b, String workstationType,
                                              UUID creatorId, String seedItemId) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            Kind.WORKSTATION, workstationType, NO_HOME, NO_HOME_POS, false, creatorId, seedItemId,
            NO_CITIZEN);
    }

    public static BlockSelection home(UUID selectionId, UUID settlementId, int colorIndex,
                                       BlockPos a, BlockPos b, UUID homeId, BlockPos homePos,
                                       UUID creatorId) {
        return new BlockSelection(selectionId, settlementId, colorIndex, a, b,
            Kind.HOME, "", homeId, homePos, false, creatorId, "", NO_CITIZEN);
    }

    public static BlockSelection workshop(UUID selectionId, UUID settlementId, int colorIndex,
                                           BlockPos a, BlockPos b, UUID workshopId,
                                           UUID creatorId) {
        return new BlockSelection(selectionId, settlementId, colorIndex, a, b,
            Kind.WORKSHOP, "", workshopId, NO_HOME_POS, false, creatorId, "", NO_CITIZEN);
    }

    public int sizeX() { return Math.abs(b.getX() - a.getX()) + 1; }
    public int sizeY() { return Math.abs(b.getY() - a.getY()) + 1; }
    public int sizeZ() { return Math.abs(b.getZ() - a.getZ()) + 1; }
    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    public static long volumeOf(BlockPos a, BlockPos b) {
        long sx = Math.abs(b.getX() - a.getX()) + 1L;
        long sy = Math.abs(b.getY() - a.getY()) + 1L;
        long sz = Math.abs(b.getZ() - a.getZ()) + 1L;
        return sx * sy * sz;
    }

    public int minX() { return Math.min(a.getX(), b.getX()); }
    public int minY() { return Math.min(a.getY(), b.getY()); }
    public int minZ() { return Math.min(a.getZ(), b.getZ()); }
    public int maxX() { return Math.max(a.getX(), b.getX()); }
    public int maxY() { return Math.max(a.getY(), b.getY()); }
    public int maxZ() { return Math.max(a.getZ(), b.getZ()); }

    public boolean contains(BlockPos p) {
        return p.getX() >= minX() && p.getX() <= maxX()
            && p.getY() >= minY() && p.getY() <= maxY()
            && p.getZ() >= minZ() && p.getZ() <= maxZ();
    }

    public boolean intersects(BlockSelection other) {
        return this.minX() <= other.maxX() && this.maxX() >= other.minX()
            && this.minY() <= other.maxY() && this.maxY() >= other.minY()
            && this.minZ() <= other.maxZ() && this.maxZ() >= other.minZ();
    }

    public boolean sameHomeAs(BlockSelection other) {
        return this.kind == Kind.HOME && other.kind == Kind.HOME
            && !NO_HOME.equals(this.homeId) && this.homeId.equals(other.homeId);
    }

    public boolean sameWorkshopAs(BlockSelection other) {
        return this.kind == Kind.WORKSHOP && other.kind == Kind.WORKSHOP
            && !NO_HOME.equals(this.homeId) && this.homeId.equals(other.homeId);
    }

    public BlockSelection withCompleted(boolean newCompleted) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            kind, workstationType, homeId, homePos, newCompleted, creatorId, seedItemId,
            assignedCitizenId);
    }

    public BlockSelection withSeed(String newSeedItemId) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            kind, workstationType, homeId, homePos, completed, creatorId,
            newSeedItemId == null ? "" : newSeedItemId, assignedCitizenId);
    }

    public BlockSelection withBounds(BlockPos newA, BlockPos newB) {
        return new BlockSelection(rodId, settlementId, colorIndex, newA, newB,
            kind, workstationType, homeId, homePos, completed, creatorId, seedItemId,
            assignedCitizenId);
    }

    public BlockSelection withAssignedCitizen(UUID citizenId) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            kind, workstationType, homeId, homePos, completed, creatorId, seedItemId,
            citizenId == null ? NO_CITIZEN : citizenId);
    }

    public boolean targetsAllWorkers() { return NO_CITIZEN.equals(assignedCitizenId); }

    public boolean targetsCitizen(UUID citizenId) {
        return targetsAllWorkers() || assignedCitizenId.equals(citizenId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("RodId", rodId);
        tag.putUUID("SettlementId", settlementId);
        tag.putInt("Color", colorIndex);
        tag.putInt("Ax", a.getX()); tag.putInt("Ay", a.getY()); tag.putInt("Az", a.getZ());
        tag.putInt("Bx", b.getX()); tag.putInt("By", b.getY()); tag.putInt("Bz", b.getZ());
        tag.putString("Type", workstationType);
        tag.putBoolean("Done", completed);
        if (!NO_CREATOR.equals(creatorId)) tag.putUUID("Creator", creatorId);
        if (!seedItemId.isEmpty()) tag.putString("Seed", seedItemId);
        if (!NO_CITIZEN.equals(assignedCitizenId)) tag.putUUID("Citizen", assignedCitizenId);
        // Optional v2 fields: omit at sentinel so pre-v2 saves round-trip (missing Kind -> WORKSTATION).
        if (kind != Kind.WORKSTATION) tag.putInt("Kind", kind.ordinal());
        if (!NO_HOME.equals(homeId)) tag.putUUID("Home", homeId);
        if (!NO_HOME_POS.equals(homePos)) {
            tag.putInt("HpX", homePos.getX());
            tag.putInt("HpY", homePos.getY());
            tag.putInt("HpZ", homePos.getZ());
        }
        return tag;
    }

    public static BlockSelection load(CompoundTag tag) {
        UUID rodId = tag.getUUID("RodId");
        UUID settlementId = tag.getUUID("SettlementId");
        int color = tag.getInt("Color");
        BlockPos a = new BlockPos(tag.getInt("Ax"), tag.getInt("Ay"), tag.getInt("Az"));
        BlockPos b = new BlockPos(tag.getInt("Bx"), tag.getInt("By"), tag.getInt("Bz"));
        String type = tag.getString("Type");
        boolean done = tag.getBoolean("Done");
        UUID creator = tag.contains("Creator") ? tag.getUUID("Creator") : NO_CREATOR;
        String seed = tag.contains("Seed") ? tag.getString("Seed") : "";
        Kind kind = tag.contains("Kind") ? Kind.fromOrdinalOrDefault(tag.getInt("Kind")) : Kind.WORKSTATION;
        UUID home = tag.contains("Home") ? tag.getUUID("Home") : NO_HOME;
        BlockPos homePos = tag.contains("HpX")
            ? new BlockPos(tag.getInt("HpX"), tag.getInt("HpY"), tag.getInt("HpZ"))
            : NO_HOME_POS;
        UUID citizen = tag.contains("Citizen") ? tag.getUUID("Citizen") : NO_CITIZEN;
        return new BlockSelection(rodId, settlementId, color, a, b, kind, type, home, homePos, done,
            creator, seed, citizen);
    }
}
