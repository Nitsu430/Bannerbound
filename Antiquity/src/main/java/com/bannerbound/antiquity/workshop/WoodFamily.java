package com.bannerbound.antiquity.workshop;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Resolves a wood "family" from a log item and looks up that family's plank-derived variants by
 * suffix, so carpentry recipes are written ONCE per variant (stairs, slab, ...) and resolved per wood
 * at runtime instead of hand-authoring a recipe for every wood x variant. A family is keyed by
 * {@code <namespace>:<base>} (e.g. minecraft:oak, minecraft:crimson) -- the stable form used for NBT
 * and the budget map, round-tripped via key()/fromKey(). The base is the log path stripped of the
 * stripped_ prefix and the _log/_wood/_stem/_hyphae/_block suffix. A would-be family is only valid if
 * {@code <namespace>:<base>_planks} exists in the item registry; that single membership test (also
 * behind isBudgetLog, which additionally requires the minecraft:logs tag) means any modded wood
 * following the vanilla naming convention works for free. variant("planks") returns the planks item;
 * any other suffix resolves {@code <namespace>:<base>_<suffix>} or null. representativeLog() prefers
 * _log, then _stem/_block/_wood, falling back to planks, for drawing on the table during the saw
 * minigame.
 */
@ApiStatus.Internal
public final class WoodFamily {
    private final String namespace;
    private final String base;
    private final Item planks;

    private WoodFamily(String namespace, String base, Item planks) {
        this.namespace = namespace;
        this.base = base;
        this.planks = planks;
    }

    public static boolean isBudgetLog(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.LOGS) && fromLog(stack.getItem()) != null;
    }

    @Nullable
    public static WoodFamily fromLog(Item log) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(log);
        if (id == null) return null;
        String base = id.getPath();
        if (base.startsWith("stripped_")) base = base.substring("stripped_".length());
        for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae", "_block"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        return build(id.getNamespace(), base);
    }

    @Nullable
    public static WoodFamily fromKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return null;
        return build(key.substring(0, colon), key.substring(colon + 1));
    }

    @Nullable
    private static WoodFamily build(String namespace, String base) {
        ResourceLocation planksId = ResourceLocation.fromNamespaceAndPath(namespace, base + "_planks");
        if (!BuiltInRegistries.ITEM.containsKey(planksId)) return null;
        return new WoodFamily(namespace, base, BuiltInRegistries.ITEM.get(planksId));
    }

    public String key() {
        return namespace + ":" + base;
    }

    public Item planks() {
        return planks;
    }

    public Item representativeLog() {
        for (String suffix : new String[] {"_log", "_stem", "_block", "_wood"}) {
            Item it = lookup(base + suffix);
            if (it != null) return it;
        }
        return planks;
    }

    @Nullable
    public Item variant(String suffix) {
        if ("planks".equals(suffix)) return planks;
        return lookup(base + "_" + suffix);
    }

    @Nullable
    private Item lookup(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, path);
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : null;
    }
}
