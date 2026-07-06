package com.bannerbound.core.api.settlement.food;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleBiFunction;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The seam between Core's stored-food scan (LarderService) and expansions that mutate, value, or
 * reject food stacks. Core itself counts any item with a positive food value; Antiquity registers
 * rules here to spoil old food and exclude poisoned food (COOKING_PLAN.md). With no expansion
 * loaded, every food counts and no store/value/multiplier hooks fire. All registration happens
 * once during mod setup. The nested FoodStore interface models a non-item food source (e.g. a
 * placed cooking pot holding stew) that drains like stored items via
 * availableFoodValue/drainFoodValue, letting blocks contribute to and be consumed from the reserve.
 *
 * <p>Each registration hook has a matching apply method, run in this order during a scan:
 * processors (processWith/process) may stamp, replace, or empty a stack, applied in sequence with
 * each output feeding the next; a normalizer (normalizeWith/normalize) then runs once over the
 * whole container to re-merge stacks a per-slot processor fragmented (Core does nothing here,
 * expansions own the policy); value contributors (contributeValueWith/extraValue -> max) supply a
 * per-item value for stacks the data tables miss, like component-carried grog nutrition, returning
 * 0 when they do not apply; value multipliers (multiplyValueWith/valueMultiplier -> product) scale
 * a stack's value, returning 1.0 when they do not apply; exclusion rules (excludeWhen/counts)
 * return true for stacks that must NOT feed the larder. Store providers (provideStoresWith/stores)
 * list the block-based food stores across a settlement's claimed chunks.
 */
public final class LarderHooks {
    private LarderHooks() {}

    private static final List<BiFunction<ItemStack, Level, ItemStack>> PROCESSORS = new ArrayList<>();
    private static final List<BiPredicate<ItemStack, Level>> EXCLUSIONS = new ArrayList<>();
    private static final List<ToDoubleBiFunction<ItemStack, Level>> VALUE_CONTRIBUTORS = new ArrayList<>();
    private static final List<ToDoubleBiFunction<ItemStack, Level>> VALUE_MULTIPLIERS = new ArrayList<>();
    private static final List<BiConsumer<IItemHandler, Level>> NORMALIZERS = new ArrayList<>();
    private static final List<BiFunction<ServerLevel, Settlement, List<FoodStore>>> STORE_PROVIDERS = new ArrayList<>();

    public interface FoodStore {
        double availableFoodValue();
        double drainFoodValue(double maxValue);
    }

    public static void provideStoresWith(BiFunction<ServerLevel, Settlement, List<FoodStore>> provider) {
        STORE_PROVIDERS.add(provider);
    }

    public static List<FoodStore> stores(ServerLevel level, Settlement s) {
        if (STORE_PROVIDERS.isEmpty()) return List.of();
        List<FoodStore> out = new ArrayList<>();
        for (BiFunction<ServerLevel, Settlement, List<FoodStore>> provider : STORE_PROVIDERS) {
            List<FoodStore> list = provider.apply(level, s);
            if (list != null) out.addAll(list);
        }
        return out;
    }

    public static void processWith(BiFunction<ItemStack, Level, ItemStack> processor) {
        PROCESSORS.add(processor);
    }

    public static void normalizeWith(BiConsumer<IItemHandler, Level> normalizer) {
        NORMALIZERS.add(normalizer);
    }

    public static void normalize(IItemHandler handler, Level level) {
        for (BiConsumer<IItemHandler, Level> normalizer : NORMALIZERS) {
            normalizer.accept(handler, level);
        }
    }

    public static void contributeValueWith(ToDoubleBiFunction<ItemStack, Level> contributor) {
        VALUE_CONTRIBUTORS.add(contributor);
    }

    public static double extraValue(ItemStack stack, Level level) {
        double best = 0.0;
        for (ToDoubleBiFunction<ItemStack, Level> contributor : VALUE_CONTRIBUTORS) {
            best = Math.max(best, contributor.applyAsDouble(stack, level));
        }
        return best;
    }

    public static void multiplyValueWith(ToDoubleBiFunction<ItemStack, Level> multiplier) {
        VALUE_MULTIPLIERS.add(multiplier);
    }

    public static double valueMultiplier(ItemStack stack, Level level) {
        double m = 1.0;
        for (ToDoubleBiFunction<ItemStack, Level> multiplier : VALUE_MULTIPLIERS) {
            m *= multiplier.applyAsDouble(stack, level);
        }
        return m;
    }

    public static void excludeWhen(BiPredicate<ItemStack, Level> rejectIf) {
        EXCLUSIONS.add(rejectIf);
    }

    public static ItemStack process(ItemStack stack, Level level) {
        ItemStack current = stack;
        for (BiFunction<ItemStack, Level, ItemStack> processor : PROCESSORS) {
            if (current.isEmpty()) break;
            ItemStack next = processor.apply(current, level);
            current = next == null ? ItemStack.EMPTY : next;
        }
        return current;
    }

    public static boolean counts(ItemStack stack, Level level) {
        for (BiPredicate<ItemStack, Level> reject : EXCLUSIONS) {
            if (reject.test(stack, level)) return false;
        }
        return true;
    }
}
