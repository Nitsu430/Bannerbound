package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.MortarAndPestleBlock;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Structure rules for Antiquity brewery workshops. A valid brewery needs a Mortar and Pestle
 * inside its marked boxes - the brewer pestles raw fermentables (berries) there before charging
 * the troughs; findMortar resolves it from the workshop's marked boxes as the brewer's stand-spot
 * for PESTLE crafts (the validator guarantees one exists in a valid brewery). unchargedPools
 * counts trough pools holding plain water or nothing - not fermenting, not holding finished grog;
 * work blocks are pool anchors (one per connected run) so it is a straight count, and it sizes
 * the brewer's standing pestled-item demand in {@code BrewerExecutor}: one charge item per pool.
 */
@ApiStatus.Internal
public final class BreweryWorkshopRules {
    private BreweryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateBrewery(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork, List<BlockPos> reachableStorage) {
        return findMortarIn(sl, marked) == null ? Workshop.Status.MISSING_CRAFTING_SURFACE : null;
    }

    @Nullable
    private static BlockPos findMortarIn(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos pos : marked) {
            if (sl.getBlockState(pos).getBlock() instanceof MortarAndPestleBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    @Nullable
    public static BlockPos findMortar(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return null;
        return findMortarIn(sl, Homes.collectMarkedSolids(sl, boxes));
    }

    public static int unchargedPools(ServerLevel sl, Workshop workshop) {
        int count = 0;
        for (BlockPos p : workshop.workBlocks()) {
            if (sl.getBlockState(p).getBlock() instanceof FermentationTroughBlock
                    && !FermentationTroughBlock.isPoolCharged(sl, p)) {
                count++;
            }
        }
        return count;
    }
}
