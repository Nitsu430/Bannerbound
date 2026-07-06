package com.bannerbound.core.api.workshop;

import java.util.List;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * The per-work-block craft driver an expansion registers alongside its work block (see
 * {@code WorkBlockRegistry.WorkBlockDef}). The crafter goal owns navigation, input withdrawal, arm
 * swings, pacing and output deposit; the executor owns everything recipe- and station-specific:
 * choosing what to craft from the available storage ({@link #chooseCraft}, which MUST respect the
 * order queue via {@code Workshops.orderedItems} and the min-stock governor via
 * {@code Workshops.wantedByMinStock} - no orders and no positive min-stock row means no craft), the
 * station's visual/audio beats, and finishing the result (e.g. rolling craftsmanship quality). The
 * default hooks (onStart / onWorkTick / onBeat / finish / onAbort) let a station only override the
 * beats it cares about.
 *
 * <p>Three surfaces feed the Stocker/logistics tier. {@link #missingInputs} is the SUPPLY surface:
 * ingredient deficits, BUFFERED - it may report more than one craft needs so raws pre-haul from a
 * stockpile in fewer trips. {@link #trueInputDemand} is the un-buffered per-input demand used to
 * SIZE CHAIN PRODUCTION (defaults to missingInputs; an executor whose inputs can be chain-crafted
 * elsewhere overrides it so the intermediate is not over-produced - e.g. a bow pulling 4x string).
 * {@link #retainedItems} is the KEEP surface: items the stocker must NOT haul out of workshop
 * storage; anything else in there is surplus, free to return to the stockpile.
 *
 * <p><b>FLAG - waiting-stage crafters (drying / baking / smelting / proving / fermenting).</b> If a
 * station has a step where a committed unit finishes on its own clock while the worker walks away,
 * a single order will OVERPRODUCE unless {@link #chooseCraft} (a) collects finished waiting units
 * UNGATED and (b) gates the START of a new unit on {@link Workshops#wantsAnother} passing the count
 * of units already in flight. Read the full contract on {@code Workshops.wantsAnother}. Crafters
 * that BLOCK the worker for the whole step (kiln tended via {@link #externallyComplete}, or any
 * instant station) are automatically safe. The Tannery (drying rack) is the worked example; future
 * smith/baker/fermenter stations MUST follow it.
 */
public interface WorkExecutor {

    record Craft(List<ItemStack> inputs, ItemStack result, int workTicks, int beats) {
    }

    @Nullable
    Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock);

    default BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                                BlockPos workBlock, Craft craft) {
        return workBlock;
    }

    default List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        return List.of();
    }

    default List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                          BlockPos workBlock) {
        return List.of();
    }

    default List<ItemStack> trueInputDemand(ServerLevel sl, Settlement settlement, Workshop workshop,
                                            BlockPos workBlock) {
        return missingInputs(sl, settlement, workshop, workBlock);
    }

    default java.util.Set<net.minecraft.world.item.Item> retainedItems(
            ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        return java.util.Set.of();
    }

    default void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
    }

    default void onWorkTick(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                            int ticksLeft) {
    }

    default boolean externallyComplete(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock,
                                       Craft craft, int ticksLeft) {
        return false;
    }

    default void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, int beatIndex) {
    }

    default void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                        int beatIndex) {
        onBeat(sl, citizen, workBlock, beatIndex);
    }

    default boolean consumesInput(Craft craft, ItemStack input) {
        return true;
    }

    default int fulfilledOrderUnits(ServerLevel sl, BlockPos workBlock, Craft craft, ItemStack output) {
        return 1;
    }

    default ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        return craft.result();
    }

    default void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
    }
}
