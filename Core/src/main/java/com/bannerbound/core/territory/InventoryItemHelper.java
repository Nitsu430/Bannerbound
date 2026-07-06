package com.bannerbound.core.territory;

import com.bannerbound.core.api.territory.ChunkClaimCost;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory count/consume helpers for the chunk-claim expansion handler: "does the player hold N of
 * item X across main + armor + offhand?" (countItem / hasAll) and the matching bulk removal
 * (consume). consume assumes feasibility -- callers MUST gate it behind hasAll; it removes greedily
 * across slots and returns false only on an impossible mid-consume state (partial removal possible)
 * that a caller should treat as a fatal bug.
 */
@ApiStatus.Internal
public final class InventoryItemHelper {
    private InventoryItemHelper() {}

    public static int countItem(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        total += sumOf(inv.items, item);
        total += sumOf(inv.armor, item);
        total += sumOf(inv.offhand, item);
        return total;
    }

    public static boolean hasAll(ServerPlayer player, List<ChunkClaimCost.ItemCost> costs) {
        for (ChunkClaimCost.ItemCost c : costs) {
            if (countItem(player, c.item()) < c.count()) return false;
        }
        return true;
    }

    public static boolean consume(ServerPlayer player, List<ChunkClaimCost.ItemCost> costs) {
        Inventory inv = player.getInventory();
        for (ChunkClaimCost.ItemCost c : costs) {
            int remaining = c.count();
            remaining = removeFrom(inv.items, c.item(), remaining);
            if (remaining > 0) remaining = removeFrom(inv.armor, c.item(), remaining);
            if (remaining > 0) remaining = removeFrom(inv.offhand, c.item(), remaining);
            if (remaining > 0) return false;
        }
        return true;
    }

    private static int sumOf(List<ItemStack> stacks, Item item) {
        int n = 0;
        for (ItemStack s : stacks) {
            if (s.is(item)) n += s.getCount();
        }
        return n;
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
}
