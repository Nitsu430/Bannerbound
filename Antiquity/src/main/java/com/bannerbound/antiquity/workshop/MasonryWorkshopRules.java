package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.settlement.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Structure rules for Antiquity masonry workshops - the stone analogue of
 * {@link CarpentryWorkshopRules}. Validation requires a stone_chisel somewhere in the workshop's
 * reachable storage (the chisel lives on the workshop, like the carpenter's saw, so the Crafter
 * who staffs it stays tool-free); otherwise the workshop reports MISSING_TOOL.
 */
@ApiStatus.Internal
public final class MasonryWorkshopRules {
    private MasonryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateMasonry(ServerLevel sl, Workshop workshop,
                                                  Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork,
                                                  List<BlockPos> reachableStorage) {
        return hasChiselInStorage(sl, reachableStorage) ? null : Workshop.Status.MISSING_TOOL;
    }

    private static boolean hasChiselInStorage(ServerLevel sl, List<BlockPos> reachableStorage) {
        for (BlockPos p : reachableStorage) {
            IItemHandler h = sl.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            if (h == null) continue;
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack s = h.getStackInSlot(slot);
                if (s.is(BannerboundAntiquity.STONE_CHISEL.get())) return true;
            }
        }
        return false;
    }
}
