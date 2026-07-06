package com.bannerbound.antiquity.workshop;

import java.util.Map;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import com.bannerbound.antiquity.event.HuntingEvents;

/**
 * Single source of truth mapping an animal species to its raw-hide item. Used by BOTH acquisition
 * paths - the hunting drop injection ({@code HuntingEvents}) and the herder cull hook
 * ({@code AntiquityHerderHooks}). A species absent from the map yields no hide ({@code hideFor}
 * returns null). isRawHide identifies the tanning rack's valid scrape inputs.
 */
public final class Hides {
    private Hides() {
    }

    private static final Map<EntityType<?>, java.util.function.Supplier<Item>> BY_SPECIES = Map.of(
        EntityType.COW, BannerboundAntiquity.COW_HIDE,
        EntityType.SHEEP, BannerboundAntiquity.SHEEP_HIDE,
        EntityType.PIG, BannerboundAntiquity.PIG_HIDE,
        EntityType.GOAT, BannerboundAntiquity.GOAT_HIDE,
        EntityType.HORSE, BannerboundAntiquity.HORSE_HIDE);

    @Nullable
    public static Item hideFor(EntityType<?> type) {
        java.util.function.Supplier<Item> s = BY_SPECIES.get(type);
        return s == null ? null : s.get();
    }

    public static boolean isRawHide(Item item) {
        for (java.util.function.Supplier<Item> s : BY_SPECIES.values()) {
            if (s.get() == item) return true;
        }
        return false;
    }
}
