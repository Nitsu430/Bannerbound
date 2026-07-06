package com.bannerbound.core.api.farmer;

import com.bannerbound.core.api.settlement.ImmigrationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-source food RATE estimate for a settlement's farmer selections: each qualifying tile inside
 * an active (non-completed) "farmer" {@link BlockSelection} contributes {@value #FOOD_PER_BLOCK}
 * food/sec. A tile qualifies only when a {@link CropBlock} sits on FARMLAND with moisture &gt; 0
 * (planted AND watered - bare farmland or merely tillable dirt does not count), and the scan runs
 * one Y above the selection AABB so the crops sitting on top of the player's selected farmland row
 * are included. Results are cached per settlement id: {@link #refresh} recomputes (bounded cost -
 * one pass over the settlement's farmer selections) and mirrors the value into
 * {@code Settlement.setPassiveFoodSourceRate("farming", ...)}; it was designed to run on
 * {@link ImmigrationManager}'s 1Hz broadcast tick, where a 1-second staleness is invisible at
 * gameplay scale. {@link #get} is the cheap per-tick cache lookup and {@link #forget} drops the
 * entry when a settlement disbands.
 * <p><b>Currently orphaned.</b> Since the COOKING_PLAN larder rewrite, real farmer food reaches the
 * settlement anonymously through the storage scan ({@code LarderService} -&gt;
 * {@code storedFoodPerSecond}), NOT through this number, and {@link #refresh} is no longer called
 * from the immigration tick. Per-source accounting now lives in
 * {@link Settlement#addFoodProduced}/{@link Settlement#foodProducedFrom} (a cumulative production
 * statistic credited at harvest), which is what crisis objectives read. Kept for reference /
 * possible reuse as an instantaneous-rate estimate; it was never part of
 * {@link Settlement#effectiveFoodPerSecond()}.
 */
public final class FarmerFoodBonus {
    public static final double FOOD_PER_BLOCK = 0.01;

    private static final Map<UUID, Double> CACHE = new HashMap<>();

    private FarmerFoodBonus() {
    }

    public static double get(UUID settlementId) {
        return CACHE.getOrDefault(settlementId, 0.0);
    }

    public static double refresh(ServerLevel level, Settlement s) {
        double bonus = compute(level, s);
        CACHE.put(s.id(), bonus);
        s.setPassiveFoodSourceRate("farming", bonus);
        return bonus;
    }

    public static void forget(UUID settlementId) {
        CACHE.remove(settlementId);
    }

    private static double compute(ServerLevel level, Settlement s) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(level);
        long count = 0;
        for (BlockSelection sel : registry.getForSettlement(s.id())) {
            if (sel.completed()) continue;
            if (!"farmer".equals(sel.workstationType())) continue;
            count += countQualifyingTiles(level, sel);
        }
        return count * FOOD_PER_BLOCK;
    }

    private static long countQualifyingTiles(ServerLevel level, BlockSelection sel) {
        long count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = sel.minY(); y <= sel.maxY() + 1; y++) {
            for (int x = sel.minX(); x <= sel.maxX(); x++) {
                for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                    cursor.set(x, y, z);
                    // Skip unloaded chunks -- getBlockState would force a chunk load here.
                    if (!level.isLoaded(cursor)) continue;
                    if (isQualifying(level, cursor)) count++;
                }
            }
        }
        return count;
    }

    private static boolean isQualifying(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CropBlock)) return false;
        BlockState below = level.getBlockState(pos.below());
        if (!below.is(Blocks.FARMLAND)) return false;
        return below.getValue(FarmBlock.MOISTURE) > 0;
    }
}
