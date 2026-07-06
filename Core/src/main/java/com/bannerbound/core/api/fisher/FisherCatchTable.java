package com.bannerbound.core.api.fisher;

import java.util.List;

import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.research.ResearchManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Decides what stack the fisher pulls out of the water on each retract. Default path: a
 * weighted-random vanilla fish (cod 60%, salmon 25%, tropical_fish 12%, pufferfish 3%) - no
 * treasure, no junk. With {@code bannerbound.unlock_treasure_fishing} researched AND the bobber in
 * open water, it first runs vanilla's {@link BuiltInLootTables#FISHING} loot table - the full
 * fish/treasure/junk pool, same as a player fishing - with the citizen as the firing entity, the
 * bobber position as origin, and the citizen's mainhand tool (or a bare fishing rod) as TOOL;
 * the first non-empty drop wins, an all-empty roll is a miss that falls back to the weighted fish.
 * "Open water" is determined upstream by {@code FisherWorkGoal} and passed in; non-open-water
 * stays fish-only even with the research, matching vanilla's no-treasure rule. Both branches gate
 * through {@code SettlementDropFilter.shouldDrop} with a null source block (pure known-set check):
 * treasure the civ doesn't recognize yet falls through to the plain fish branch rather than
 * handing over an item the settlement can't use, and an unrecognized fish becomes
 * {@link ItemStack#EMPTY} (a miss).
 */
public final class FisherCatchTable {
    public static final String FLAG_TREASURE_FISHING = "bannerbound.unlock_treasure_fishing";

    // Cumulative weights out of 100: cod 60, salmon 25, tropical 12, puffer 3.
    private static final int[] FISH_WEIGHTS_CUMULATIVE = { 60, 85, 97, 100 };

    private FisherCatchTable() {
    }

    public static ItemStack roll(ServerLevel level, CitizenEntity citizen, Settlement settlement,
                                  BlockPos bobberPos, boolean openWater) {
        if (settlement != null && openWater
                && ResearchManager.hasFlag(settlement, FLAG_TREASURE_FISHING)) {
            ItemStack treasure = rollVanillaTable(level, citizen, bobberPos);
            if (!treasure.isEmpty()
                    && com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(settlement, null, treasure)) {
                return treasure;
            }
        }
        ItemStack fish = rollWeightedFish(level);
        return com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(settlement, null, fish)
            ? fish : ItemStack.EMPTY;
    }

    private static ItemStack rollWeightedFish(ServerLevel level) {
        int n = level.getRandom().nextInt(100);
        if (n < FISH_WEIGHTS_CUMULATIVE[0]) return new ItemStack(Items.COD);
        if (n < FISH_WEIGHTS_CUMULATIVE[1]) return new ItemStack(Items.SALMON);
        if (n < FISH_WEIGHTS_CUMULATIVE[2]) return new ItemStack(Items.TROPICAL_FISH);
        return new ItemStack(Items.PUFFERFISH);
    }

    private static ItemStack rollVanillaTable(ServerLevel level, CitizenEntity citizen, BlockPos bobberPos) {
        ResourceKey<LootTable> key = BuiltInLootTables.FISHING;
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        ItemStack tool = citizen.getItemBySlot(EquipmentSlot.MAINHAND);
        if (tool.isEmpty()) tool = new ItemStack(Items.FISHING_ROD);
        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(bobberPos))
            .withParameter(LootContextParams.TOOL, tool)
            .withOptionalParameter(LootContextParams.THIS_ENTITY, citizen)
            .create(LootContextParamSets.FISHING);
        List<ItemStack> drops = table.getRandomItems(params);
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) return drop;
        }
        return ItemStack.EMPTY;
    }
}
