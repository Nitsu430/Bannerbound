package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workstation;
import com.bannerbound.core.api.settlement.WorkstationInventory;
import com.bannerbound.core.api.territory.ChunkClaimCost;
import com.bannerbound.core.stockpile.StockpileService;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Resource sourcing for the Council expand-territory (and Chiefdom) flows: an atomic hasAll + consume
 * pair mirroring InventoryItemHelper's contract, drawing across three tiers in order -- the
 * settlement's shared stockpile (the common pool, a council's chest), then valid workstation
 * inventories, then the voter player inventories (in cast-order, first voter pays first). Callers MUST
 * confirm feasibility with hasAll before consume; consume assumes the items are present and returns
 * false (a panic) if it runs short mid-drain. singletonVoters bundles a lone chief into the same
 * machinery. Design: loot-or-pay decisions stay at the player level, bookkeeping at the settlement
 * level.
 */
@ApiStatus.Internal
public final class SettlementInventoryHelper {
    private SettlementInventoryHelper() {
    }

    public static boolean hasAll(ServerLevel level, Settlement settlement,
                                  List<ServerPlayer> voters,
                                  List<ChunkClaimCost.ItemCost> costs) {
        for (ChunkClaimCost.ItemCost cost : costs) {
            if (countAll(level, settlement, voters, cost.item()) < cost.count()) return false;
        }
        return true;
    }

    public static boolean consume(ServerLevel level, Settlement settlement,
                                   List<ServerPlayer> voters,
                                   List<ChunkClaimCost.ItemCost> costs) {
        for (ChunkClaimCost.ItemCost cost : costs) {
            int remaining = cost.count();
            remaining -= StockpileService.withdraw(level, settlement, cost.item(), remaining);
            if (remaining <= 0) continue;
            remaining = drainWorkstations(level, settlement, cost.item(), remaining);
            if (remaining <= 0) continue;
            for (ServerPlayer voter : voters) {
                if (remaining <= 0) break;
                int taken = drainPlayerInventory(voter, cost.item(), remaining);
                remaining -= taken;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private static int countAll(ServerLevel level, Settlement settlement,
                                 List<ServerPlayer> voters, Item item) {
        int total = StockpileService.count(level, settlement, item);
        for (Workstation ws : settlement.workstations().values()) {
            if (!ws.buildingValid()) continue;
            if (level.getBlockEntity(ws.pos()) instanceof WorkstationInventory wsi) {
                for (ItemStack s : wsi.items()) {
                    if (s.is(item)) total += s.getCount();
                }
            }
        }
        for (ServerPlayer voter : voters) {
            total += InventoryItemHelper.countItem(voter, item);
        }
        return total;
    }

    private static int drainWorkstations(ServerLevel level, Settlement settlement,
                                          Item item, int wanted) {
        for (Workstation ws : settlement.workstations().values()) {
            if (wanted <= 0) return 0;
            if (!ws.buildingValid()) continue;
            if (!(level.getBlockEntity(ws.pos()) instanceof WorkstationInventory wsi)) continue;
            NonNullList<ItemStack> items = wsi.items();
            boolean dirty = false;
            for (int i = 0; i < items.size() && wanted > 0; i++) {
                ItemStack s = items.get(i);
                if (!s.is(item)) continue;
                int take = Math.min(s.getCount(), wanted);
                s.shrink(take);
                wanted -= take;
                dirty = true;
            }
            if (dirty) wsi.setStockChanged();
        }
        return wanted;
    }

    private static int drainPlayerInventory(ServerPlayer player, Item item, int wanted) {
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int original = wanted;
        wanted = removeFrom(inv.items, item, wanted);
        if (wanted > 0) wanted = removeFrom(inv.armor, item, wanted);
        if (wanted > 0) wanted = removeFrom(inv.offhand, item, wanted);
        return original - wanted;
    }
    private static int removeFrom(List<ItemStack> stacks, Item item, int wanted) {
        for (int i = 0; i < stacks.size() && wanted > 0; i++) {
            ItemStack s = stacks.get(i);
            if (!s.is(item)) continue;
            int take = Math.min(s.getCount(), wanted);
            s.shrink(take);
            wanted -= take;
        }
        return wanted;
    }

    public static List<ServerPlayer> singletonVoters(ServerPlayer player) {
        List<ServerPlayer> out = new ArrayList<>(1);
        out.add(player);
        return out;
    }
}
