package com.bannerbound.core.api.settlement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.api.world.HouseStructureDetector;
import com.bannerbound.core.entity.HousingEvictionHook;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Server-side home service - the residence twin of {@link com.bannerbound.core.api.workshop.Workshops}.
 * A home has no anchor block: it is defined purely by the Housing Orders rod's HOME
 * {@link BlockSelection}s (keyed by {@link Home#id()} in the registry) and validated on rod
 * commit, on panel open, and by the background HomeRevalidator sweep; {@link #validateCached}
 * throttles hot paths (auto-assignment poll / revalidator) to the cached status. Validation keeps
 * the rules the deleted HouseBlockEntity ran - connectivity (26-neighbour, so stepped/cornered
 * walls form one building), enclosure, a per-era size cap ({@link HousingLimits}), and a bed
 * count - but measures the size cap as a union span (no anchor to center a radius on) and checks
 * it BEFORE the enclosure flood so an oversized home never pays the full-bbox scan. Enclosure is
 * outside-in: flood air inward from the +1 ring around the marked bbox (guaranteed exterior),
 * blocked by solids and by {@link HouseStructureDetector#isWallOpening} holes; the interior is
 * any air the outside could not reach, and the home is enclosed iff that interior is non-empty.
 * Seeding air INSIDE the bbox (the old approach) false-failed every non-rectangular house because
 * a non-cuboid's bbox contains exterior dead-zone air for the flood to "escape" from; wall-opening
 * cells are also excluded from the interior count so a lone windowed wall never reads as a 1-cell
 * interior. After the status lands: surplus residents are evicted when the bed count drops,
 * appeal is always rescored (one cheap pass, so the panel never shows stale), and home demands
 * are evaluated - demands are SOFT (never change status), apply only to enclosed homes
 * (VALID / NO_BEDS), and feed happiness together with space-per-bed crowding.
 * {@link #containsBedHead} is the rod's creation gate (the first committed box must hold a bed
 * head); {@link #collectMarkedSolids} / {@link #isConnected} are shared with the workshop code;
 * and {@link #deliverableContainers} lists the home's private pantry chests for the stocker
 * home-supply pass (these are excluded from settlement drain so delivered luxuries stay put).
 */
public final class Homes {

    private Homes() {
    }

    public static Home.Status validate(ServerLevel sl, Home home) {
        MinecraftServer server = sl.getServer();
        if (server == null) return home.status();
        SettlementData data = SettlementData.get(server.overworld());
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByHome(home.id());

        Home.Status status;
        int bedCount = 0;
        int interiorAir = 0;
        BlockPos anchor = null;
        Settlement owner = null;
        Set<BlockPos> marked = null;

        if (boxes.isEmpty()) {
            status = Home.Status.UNMARKED;
        } else {
            owner = data.getById(boxes.get(0).settlementId());
            marked = collectMarkedSolids(sl, boxes);
            if (marked.isEmpty()) {
                status = Home.Status.UNMARKED;
            } else if (!isConnected(marked)) {
                status = Home.Status.BROKEN_DISCONNECTED;
            } else if (exceedsSizeLimit(marked, HousingLimits.maxRadius(owner))) {
                status = Home.Status.BROKEN_TOO_BIG;
            } else {
                int air = enclosedAirCount(sl, marked); // >=0 = sealed interior cells, -1 = not enclosed
                if (air < 0) {
                    status = Home.Status.BROKEN_NOT_ENCLOSED;
                } else {
                    interiorAir = air;
                    bedCount = countBedHeadsInBoxes(sl, boxes);
                    anchor = firstBedHead(sl, boxes);
                    status = bedCount > 0 ? Home.Status.VALID : Home.Status.NO_BEDS;
                }
            }
            if (anchor == null) anchor = centroid(marked);
        }

        home.setStatus(status);
        home.setValid(status == Home.Status.VALID);
        home.setBedCount(bedCount);
        home.setCachedInteriorVolume(interiorAir);
        if (anchor != null) home.setPos(anchor);
        java.util.List<UUID> evicted = home.trimToBedCount();
        if (!evicted.isEmpty()) {
            HousingEvictionHook.onEvict(sl, evicted);
        }
        if (owner != null) {
            HouseAppealData.scoreOf(sl, owner, home);
        }

        int spacePerBed = (bedCount > 0 && interiorAir > 0)
            ? interiorAir / bedCount : HomeDemand.NO_CROWDING;
        if (owner != null && marked != null
                && (status == Home.Status.VALID || status == Home.Status.NO_BEDS)) {
            List<HomeDemand.DemandState> demands = HomeDemand.evaluate(sl, owner, marked);
            int met = 0;
            for (HomeDemand.DemandState d : demands) if (d.met()) met++;
            home.setCachedDemands(demands);
            home.setCachedHomeHappiness(
                HomeDemand.computeHappiness(home.cachedBeauty(), met, demands.size() - met, spacePerBed));
        } else {
            home.setCachedDemands(List.of());
            home.setCachedHomeHappiness(
                HomeDemand.computeHappiness(home.cachedBeauty(), 0, 0, spacePerBed));
        }

        data.setDirty();
        return status;
    }

    public static Home.Status validateCached(ServerLevel sl, Home home, long maxAgeTicks) {
        long now = sl.getGameTime();
        if (now - home.lastValidatedTick() < maxAgeTicks) {
            return home.status();
        }
        home.setLastValidatedTick(now);
        return validate(sl, home);
    }

    public static String diagnose(ServerLevel sl, Home home) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByHome(home.id());
        if (boxes.isEmpty()) return "no boxes";
        Set<BlockPos> marked = collectMarkedSolids(sl, boxes);
        boolean connected = isConnected(marked);
        int air = enclosedAirCount(sl, marked);
        int beds = countBedHeadsInBoxes(sl, boxes);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int spanX = marked.isEmpty() ? 0 : maxX - minX + 1;
        int spanY = marked.isEmpty() ? 0 : maxY - minY + 1;
        int spanZ = marked.isEmpty() ? 0 : maxZ - minZ + 1;
        return String.format("status=%s boxes=%d marked=%d connected=%b air=%d beds=%d span=%dx%dx%d",
            home.status(), boxes.size(), marked.size(), connected, air, beds, spanX, spanY, spanZ);
    }

    public static List<BlockPos> deliverableContainers(ServerLevel sl, Home home) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : collectMarkedSolids(sl, BlockSelectionRegistry.get(sl).findByHome(home.id()))) {
            if (com.bannerbound.core.entity.DropOffContainers.isDropOffBlock(sl, p)) out.add(p);
        }
        return out;
    }

    public record Hit(Settlement settlement, Home home) {
    }

    @Nullable
    public static Hit findAt(ServerLevel sl, BlockPos pos) {
        MinecraftServer server = sl.getServer();
        if (server == null) return null;
        for (BlockSelection s : BlockSelectionRegistry.get(sl).getAll()) {
            if (s.kind() != BlockSelection.Kind.HOME || !s.contains(pos)) continue;
            Settlement owner = SettlementData.get(server.overworld()).getById(s.settlementId());
            if (owner == null) continue;
            Home h = owner.getHomeById(s.homeId());
            if (h != null) return new Hit(owner, h);
        }
        return null;
    }

    @Nullable
    public static Hit findById(ServerLevel sl, UUID homeId) {
        if (homeId == null) return null;
        MinecraftServer server = sl.getServer();
        if (server == null) return null;
        for (Settlement s : SettlementData.get(server.overworld()).all()) {
            Home h = s.getHomeById(homeId);
            if (h != null) return new Hit(s, h);
        }
        return null;
    }

    public static Set<BlockPos> collectMarkedSolids(ServerLevel sl, List<BlockSelection> boxes) {
        Set<BlockPos> marked = new HashSet<>();
        Set<BlockPos> seenInBox = new HashSet<>();
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!seenInBox.add(p)) continue;
                        if (!sl.getBlockState(p).isAir()) marked.add(p);
                    }
                }
            }
        }
        return marked;
    }

    private static final int[][] CONNECT_OFFSETS_26 = buildConnectOffsets();

    private static int[][] buildConnectOffsets() {
        java.util.List<int[]> offs = new java.util.ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) offs.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offs.toArray(new int[0][]);
    }

    public static boolean isConnected(Set<BlockPos> marked) {
        if (marked.size() <= 1) return true;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = marked.iterator().next();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (int[] o : CONNECT_OFFSETS_26) {
                BlockPos n = p.offset(o[0], o[1], o[2]);
                if (marked.contains(n) && visited.add(n)) queue.add(n);
            }
        }
        return visited.size() == marked.size();
    }

    public static boolean containsBedHead(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos p : marked) {
            BlockState s = sl.getBlockState(p);
            if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                return true;
            }
        }
        return false;
    }

    private static boolean exceedsSizeLimit(Set<BlockPos> marked, int maxRadius) {
        int maxSpan = 2 * maxRadius + 1;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        return (maxX - minX + 1) > maxSpan || (maxY - minY + 1) > maxSpan || (maxZ - minZ + 1) > maxSpan;
    }

    private static int enclosedAirCount(ServerLevel sl, Set<BlockPos> marked) {
        if (marked.isEmpty()) return -1;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int eMinX = minX - 1, eMinY = minY - 1, eMinZ = minZ - 1;
        int eMaxX = maxX + 1, eMaxY = maxY + 1, eMaxZ = maxZ + 1;

        Set<BlockPos> exterior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (int x = eMinX; x <= eMaxX; x++) {
            for (int y = eMinY; y <= eMaxY; y++) {
                for (int z = eMinZ; z <= eMaxZ; z++) {
                    if (x != eMinX && x != eMaxX && y != eMinY && y != eMaxY && z != eMinZ && z != eMaxZ) {
                        continue; // strictly inside the expanded box -> not a boundary seed cell
                    }
                    BlockPos p = new BlockPos(x, y, z);
                    if (sl.getBlockState(p).isAir() && exterior.add(p)) queue.add(p);
                }
            }
        }
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (n.getX() < eMinX || n.getX() > eMaxX
                    || n.getY() < eMinY || n.getY() > eMaxY
                    || n.getZ() < eMinZ || n.getZ() > eMaxZ) continue;
                if (exterior.contains(n)) continue;
                if (!sl.getBlockState(n).isAir()) continue;
                if (HouseStructureDetector.isWallOpening(sl, n)) continue; // openings seal the flood; drop this and windows leak
                exterior.add(n);
                queue.add(n);
            }
        }

        int interior = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!sl.getBlockState(p).isAir()) continue;
                    if (exterior.contains(p)) continue;
                    if (HouseStructureDetector.isWallOpening(sl, p)) continue;
                    interior++;
                }
            }
        }
        return interior > 0 ? interior : -1;
    }

    private static int countBedHeadsInBoxes(ServerLevel sl, List<BlockSelection> boxes) {
        Set<BlockPos> seen = new HashSet<>();
        int n = 0;
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!seen.add(p)) continue;
                        BlockState s = sl.getBlockState(p);
                        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                            n++;
                        }
                    }
                }
            }
        }
        return n;
    }

    @Nullable
    private static BlockPos firstBedHead(ServerLevel sl, List<BlockSelection> boxes) {
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = sl.getBlockState(p);
                        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                            return p.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos centroid(Set<BlockPos> marked) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        return new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }
}
