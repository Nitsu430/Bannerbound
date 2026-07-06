package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.ClayTankBlock;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity.LiquidType;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Structure rules + lookup helpers for the Antiquity tannery, shared by workshop validation and
 * {@link TanneryExecutor}. A valid tannery must contain a clay tank; the tank "controller" is the
 * multiblock's bottom block (PART == 0) and is the only part counted or resolved to a block
 * entity. countTankBases exists because each tank base needs its own fired clay bucket kept in
 * workshop storage - the bucket is required for water fetching but never consumed. leatherInProgress
 * counts racks in DRYING or DRY phase: committed leather units the demand gate must subtract (see
 * Workshops.wantsAnother) so a single order does not lay a second hide to dry while one is already
 * drying. findWaterSource scans a WATER_SCOOP_RADIUS box around the tank pillar for the nearest
 * open-water source block, so water only has to be near the tank rather than glued to it; the scan
 * is cheap and only runs when a tank charge is actually due.
 */
@ApiStatus.Internal
public final class TanneryWorkshopRules {
    private TanneryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateTannery(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork, List<BlockPos> reachableStorage) {
        return findTankController(sl, marked) == null ? Workshop.Status.MISSING_CURING_LIQUID : null;
    }

    @Nullable
    public static BlockPos findTankController(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos pos : marked) {
            var state = sl.getBlockState(pos);
            if (state.getBlock() instanceof ClayTankBlock && state.getValue(ClayTankBlock.PART) == 0) {
                return pos.immutable();
            }
        }
        return null;
    }

    public static int countTankBases(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return 0;
        int count = 0;
        for (BlockPos pos : Homes.collectMarkedSolids(sl, boxes)) {
            var state = sl.getBlockState(pos);
            if (state.getBlock() instanceof ClayTankBlock && state.getValue(ClayTankBlock.PART) == 0) {
                count++;
            }
        }
        return count;
    }

    @Nullable
    public static ClayTankBlockEntity findTank(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return null;
        BlockPos controller = findTankController(sl, Homes.collectMarkedSolids(sl, boxes));
        return controller != null && sl.getBlockEntity(controller) instanceof ClayTankBlockEntity be ? be : null;
    }

    public static int leatherInProgress(ServerLevel sl, Workshop workshop) {
        int count = 0;
        for (BlockPos p : workshop.workBlocks()) {
            if (sl.getBlockEntity(p) instanceof com.bannerbound.antiquity.block.entity.TanningRackBlockEntity rack) {
                var phase = rack.getPhase();
                if (phase == com.bannerbound.antiquity.block.entity.TanningRackBlockEntity.Phase.DRYING
                    || phase == com.bannerbound.antiquity.block.entity.TanningRackBlockEntity.Phase.DRY) {
                    count++;
                }
            }
        }
        return count;
    }

    public static boolean hasCuring(ServerLevel sl, Workshop workshop) {
        ClayTankBlockEntity tank = findTank(sl, workshop);
        return tank != null && tank.getLiquid() == LiquidType.CURING && tank.getBuckets() > 0;
    }

    private static final int WATER_SCOOP_RADIUS = 10;

    @Nullable
    public static BlockPos findWaterSource(ServerLevel sl, ClayTankBlockEntity tank) {
        BlockPos base = tank.getBlockPos();
        int top = tank.pillarHeight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -1; dy <= top; dy++) {
            for (int dx = -WATER_SCOOP_RADIUS; dx <= WATER_SCOOP_RADIUS; dx++) {
                for (int dz = -WATER_SCOOP_RADIUS; dz <= WATER_SCOOP_RADIUS; dz++) {
                    p.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    net.minecraft.world.level.material.FluidState f = sl.getFluidState(p);
                    if (f.is(net.minecraft.tags.FluidTags.WATER) && f.isSource()) {
                        double d = base.distSqr(p);
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = p.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }
}
