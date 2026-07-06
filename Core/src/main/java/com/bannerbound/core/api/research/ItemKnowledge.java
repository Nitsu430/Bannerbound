package com.bannerbound.core.api.research;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.data.StartingItemsLoader;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Single source of truth for "does this civ recognize this item?". The effective known set is the
 * global starting items (every settlement knows these regardless of era) unioned with the
 * settlement's cached {@code knownItems()} set - a precomputed union of BOTH trees' unlocks.items
 * (science and culture are first-class), read directly instead of recomputing via
 * ResearchManager/CultureManager on every query. A null settlement means no civ context (player
 * without a settlement, wild mob dying with no killer, etc.), in which case only the global
 * starting items are known, mirroring {@link com.bannerbound.core.event.UnknownItemBlocker}'s
 * fallback.
 * <p>
 * Server-side counterpart to the client's {@link com.bannerbound.core.client.UnknownItemHelper};
 * both consult the same starting-items + research-unlock sets so the inventory question-mark and
 * the drop filter never disagree.
 * <p>
 * StackGates are an expansion hook for component-aware knowledge (e.g. a modular arrow whose parts
 * use an unresearched material). They only ever RESTRICT: the base item id must be known first,
 * then every registered gate must also accept the stack; a gate returns true to mean "no
 * objection". Gates are held in a CopyOnWriteArrayList and registered once at mod setup.
 */
public final class ItemKnowledge {
    private ItemKnowledge() {
    }

    @FunctionalInterface
    public interface StackGate {
        boolean isKnown(@Nullable Settlement settlement, ItemStack stack);
    }

    private static final List<StackGate> STACK_GATES = new CopyOnWriteArrayList<>();

    public static void registerStackGate(StackGate gate) {
        STACK_GATES.add(gate);
    }

    public static boolean isKnown(@Nullable Settlement settlement, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!isKnown(settlement, stack.getItem())) {
            return false;
        }
        for (StackGate gate : STACK_GATES) {
            if (!gate.isKnown(settlement, stack)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isKnown(@Nullable Settlement settlement, Item item) {
        if (item == null) {
            return false;
        }
        if (StartingItemsLoader.contains(item)) {
            return true;
        }
        if (settlement == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return false;
        }
        return settlement.knownItems().contains(id.toString());
    }
}
