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
 * Structure rules for Antiquity carpentry workshops. The carpenter's saw requirement used to live
 * on a dedicated "carpenter" citizen job (a tool the worker carried); with the crafter jobs unified
 * into one generic Crafter - whose specialty derives from the workshop - that requirement moved
 * here: a carpentry workshop needs a {@code bone_saw} kept in its storage to operate, mirroring how
 * {@link PotteryWorkshopRules} requires a kiln. The Crafter who staffs it stays tool-free. The saw
 * check scans the passed reachable-storage positions directly because the workshop's cached storage
 * list isn't guaranteed populated mid-validation.
 */
@ApiStatus.Internal
public final class CarpentryWorkshopRules {
    private CarpentryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateCarpentry(ServerLevel sl, Workshop workshop,
                                                    Set<BlockPos> marked,
                                                    List<BlockPos> reachableWork,
                                                    List<BlockPos> reachableStorage) {
        return hasSawInStorage(sl, reachableStorage) ? null : Workshop.Status.MISSING_TOOL;
    }

    private static boolean hasSawInStorage(ServerLevel sl, List<BlockPos> reachableStorage) {
        for (BlockPos p : reachableStorage) {
            IItemHandler h = sl.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            if (h == null) continue;
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack s = h.getStackInSlot(slot);
                if (s.is(BannerboundAntiquity.BONE_SAW.get())) return true;
            }
        }
        return false;
    }
}
