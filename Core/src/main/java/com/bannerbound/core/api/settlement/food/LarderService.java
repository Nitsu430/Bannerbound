package com.bannerbound.core.api.settlement.food;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.data.FoodValueLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import com.bannerbound.core.entity.DropOffContainers;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Passive settlement larder (COOKING_PLAN.md). Valid food sitting in the settlement's claimed,
 * currently-loaded storage contributes a passive food/sec bonus (storedFoodValue x
 * Config.STORED_FOOD_RATE_PER_VALUE). The food is NOT consumed by this scan; only the spoilage
 * system (LarderHooks) ever removes it, so "got enough cod for the day until it spoils" just works
 * without per-item management. Block-based food stores (cooking pots) count too. Gated on Storage
 * Logistics research (STORAGE_RESEARCH_FLAG): a settlement without it has no larder and stored
 * items stay plain player food. refresh() is called once/sec from ImmigrationManager; it rescans
 * claimed storage, runs each stack through LarderHooks (processors, then value contributors,
 * multipliers, exclusions), re-normalizes each container to re-merge fragmented spoiled stacks,
 * then adds block-based stores and writes the total + passive food/sec back onto the settlement.
 */
@ApiStatus.Internal
public final class LarderService {
    private LarderService() {}

    public static final String STORAGE_RESEARCH_FLAG = "bannerbound.unlock.stocker";

    public static void refresh(ServerLevel level, Settlement s) {
        if (!ResearchManager.hasFlag(s, STORAGE_RESEARCH_FLAG)) {
            s.setStoredFoodValue(0.0);
            s.setStoredFoodPerSecond(0.0);
            return;
        }
        double valueTotal = 0.0;
        for (IItemHandler handler : storageHandlers(level, s)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack raw = handler.getStackInSlot(slot);
                if (raw.isEmpty()) continue;
                double quick = Math.max(FoodValueLoader.effective(raw.getItem(), s),
                                        LarderHooks.extraValue(raw, level));
                if (quick <= 0.0) continue;
                ItemStack stack = processStorageStack(handler, slot, raw, level);
                if (stack.isEmpty() || !LarderHooks.counts(stack, level)) continue;
                double per = Math.max(FoodValueLoader.effective(stack.getItem(), s),
                                      LarderHooks.extraValue(stack, level));
                if (per <= 0.0) continue;
                valueTotal += per * LarderHooks.valueMultiplier(stack, level) * stack.getCount();
            }
            LarderHooks.normalize(handler, level);
        }
        for (LarderHooks.FoodStore store : LarderHooks.stores(level, s)) {
            valueTotal += Math.max(0.0, store.availableFoodValue());
        }
        s.setStoredFoodValue(valueTotal);
        s.setStoredFoodPerSecond(
            valueTotal * com.bannerbound.core.Config.STORED_FOOD_RATE_PER_VALUE.get());
    }

    private static ItemStack processStorageStack(IItemHandler handler, int slot, ItemStack stack, ServerLevel level) {
        boolean writable = handler instanceof IItemHandlerModifiable;
        ItemStack processed = LarderHooks.process(writable ? stack : stack.copy(), level);
        if (writable && processed != stack) {
            ((IItemHandlerModifiable) handler).setStackInSlot(slot, processed);
        }
        return processed;
    }

    private static List<IItemHandler> storageHandlers(ServerLevel level, Settlement s) {
        List<IItemHandler> out = new ArrayList<>();
        for (long packed : s.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                if (DropOffContainers.isSecondaryChestHalf(level, pos)) continue;
                IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
                // Prefer a writable view so the spoilage stamp persists; else wrap the Container.
                if (!(h instanceof IItemHandlerModifiable) && level.getBlockEntity(pos) instanceof Container c) {
                    h = new InvWrapper(c);
                }
                if (h != null) out.add(h);
            }
        }
        return out;
    }
}
