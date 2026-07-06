package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.stockpile.StockpileService;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Shared sourcing helpers for the forester roles -- sapling-species resolution and consume-one pulls
 * from the citizen's job depot (checked first) then the settlement stockpiles. Factored out of
 * {@link ForesterWorkGoal#tryReplant} so both the gatherer's replant and {@link ForesterPlantationGoal}
 * draw from the same supply chain (the Stocker drains the drop-off, so saplings/bone meal usually end
 * up in storage).
 *
 * <p>Species are resolved by the vanilla "*_log -> *_sapling" name substitution; the plantation-v1
 * filter accepts any *_sapling EXCEPT dark oak (needs a 2x2 grid). Mangrove propagules and nether
 * fungi don't end in "_sapling" so they fall out for free; modded saplings are assumed single-growable
 * (best effort). Stems, non-"*_log" blocks, and unknown ids all resolve to {@link Items#AIR}.
 */
@ApiStatus.Internal
public final class ForesterSupplies {
    private ForesterSupplies() {}

    public static Item saplingForLog(@Nullable Block logBlock) {
        if (logBlock == null) return Items.AIR;
        ResourceLocation logId = BuiltInRegistries.BLOCK.getKey(logBlock);
        if (logId == null) return Items.AIR;
        String saplingPath = logId.getPath().replace("_log", "_sapling");
        if (saplingPath.equals(logId.getPath())) return Items.AIR;
        ResourceLocation saplingId = ResourceLocation.fromNamespaceAndPath(logId.getNamespace(), saplingPath);
        if (!BuiltInRegistries.BLOCK.containsKey(saplingId)) return Items.AIR;
        Item item = BuiltInRegistries.BLOCK.get(saplingId).asItem();
        return isSupportedSapling(item) ? item : Items.AIR;
    }

    public static Block saplingBlock(Item saplingItem) {
        return Block.byItem(saplingItem);
    }

    public static boolean isSupportedSapling(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return false;
        String path = id.getPath();
        if (!path.endsWith("_sapling")) return false;
        return !path.equals("dark_oak_sapling");
    }

    public static boolean hasOne(ServerLevel level, @Nullable Settlement settlement,
                                 @Nullable Container depot, Item item) {
        if (item == Items.AIR) return false;
        if (depot != null) {
            for (int i = 0; i < depot.getContainerSize(); i++) {
                ItemStack s = depot.getItem(i);
                if (!s.isEmpty() && s.is(item)) return true;
            }
        }
        return settlement != null && StockpileService.count(level, settlement, item) > 0;
    }

    public static boolean takeOne(ServerLevel level, @Nullable Settlement settlement,
                                  @Nullable Container depot, Item item) {
        if (item == Items.AIR) return false;
        if (depot != null) {
            for (int i = 0; i < depot.getContainerSize(); i++) {
                ItemStack s = depot.getItem(i);
                if (!s.isEmpty() && s.is(item)) {
                    s.shrink(1);
                    depot.setChanged();
                    return true;
                }
            }
        }
        if (settlement != null && StockpileService.count(level, settlement, item) > 0) {
            StockpileService.withdraw(level, settlement, item, 1);
            return true;
        }
        return false;
    }

    public static Item pickSpecies(CitizenEntity citizen, ServerLevel level,
                                   @Nullable Settlement settlement, @Nullable Container depot) {
        Item preferred = saplingForLog(citizen.getPreferredLog());
        if (preferred != Items.AIR && hasOne(level, settlement, depot, preferred)) return preferred;
        if (depot != null) {
            for (int i = 0; i < depot.getContainerSize(); i++) {
                ItemStack s = depot.getItem(i);
                if (!s.isEmpty() && isSupportedSapling(s.getItem())) return s.getItem();
            }
        }
        return Items.AIR;
    }
}
