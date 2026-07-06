package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side cache of the server's global starting-items set (item ids as strings), synced from the
 * server and used mainly as JEI's knowledge gate. Held in a volatile field with CopyOnWriteArrayList
 * listeners so any thread can read/replace safely; replace() no-ops on an unchanged set and otherwise
 * fires listeners (JEI rebuilds its hidden-item list).
 *
 * isLoaded() treats an empty set as "not synced yet" rather than "nothing unlocked": the global set is
 * never legitimately empty (Antiquity ships 100+ items). JEI relies on this to avoid removing items
 * before knowledge arrives -- removing a starting item then re-adding it once synced leaves its crafting
 * recipe stuck hidden in JEI.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientStartingItems {
    private static volatile Set<String> ITEMS = Set.of();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private ClientStartingItems() {
    }

    public static void replace(Set<String> ids) {
        Set<String> next = Set.copyOf(ids);
        if (ITEMS.equals(next)) {
            return;
        }
        ITEMS = next;
        notifyListeners();
    }

    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    public static boolean contains(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && ITEMS.contains(id.toString());
    }

    public static boolean isLoaded() {
        return !ITEMS.isEmpty();
    }
}
