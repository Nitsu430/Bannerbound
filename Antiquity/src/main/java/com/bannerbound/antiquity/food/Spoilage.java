package com.bannerbound.antiquity.food;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.FoodSpoilage;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Server-side stack operations for the food-freshness layer: stamp a perishable stack as FRESH,
 * roll its once-a-second chance to degrade a level, and apply salt's keeps-longer bonus. Freshness
 * is a discrete level on the stack ({@link FoodSpoilage}) with no per-stack clock, so every fresh
 * stack of a food is component-identical and merges with every other fresh stack (likewise bland
 * with bland) - spoilage never fragments food beyond "a fresh pile, a bland pile, a spoiled pile".
 * Spoilage is probabilistic: each second a stack is observed (carried, dropped, or scanned in
 * claimed storage), {@link #tick} rolls a 20/meanTicks chance (ROLL_TICKS matches the sweeps'
 * once-a-second cadence) to drop one level, FRESH -> BLAND -> the terminal spoiled_food item;
 * the geometric mean equals the phase's data-driven duration in {@link FoodSpoilageData}, so a
 * stack's average shelf life matches the data while the exact moment it turns varies stack to
 * stack. Salt divides the per-roll chance by {@link FoodSpoilageData#saltLifeMultiplier()}; it
 * preserves what's left and never refreshes bland back to fresh. {@link #stamp} exists so food
 * entering the world carries the FRESH component before pickup and merges with already-stamped
 * inventory food. {@link #tick} may return a copy or a replacement stack - callers must write the
 * result back whenever it differs from what they passed. The compact passes re-merge food that
 * stamping or degrading fragmented, because vanilla never retroactively merges equal stacks;
 * {@link ItemStack#isSameItemSameComponents} guarantees only same-freshness food fuses.
 * {@link #compactStorage} is the storage-side counterpart used by Core's larder scan.
 */
public final class Spoilage {
    private Spoilage() {}

    private static final double ROLL_TICKS = 20.0;

    public static void ensureStamped(ItemStack stack) {
        if (stack.isEmpty() || stack.has(BannerboundAntiquity.FOOD_SPOILAGE.get())) return;
        if (!FoodSpoilageData.isPerishable(stack.getItem())) return;
        stack.set(BannerboundAntiquity.FOOD_SPOILAGE.get(), new FoodSpoilage(FoodSpoilage.FRESH, false));
    }

    public static ItemStack stamp(ItemStack stack) {
        if (stack.isEmpty() || stack.has(BannerboundAntiquity.FOOD_SPOILAGE.get())
            || !FoodSpoilageData.isPerishable(stack.getItem())) {
            return stack;
        }
        ItemStack copy = stack.copy();
        ensureStamped(copy);
        return copy;
    }

    public static ItemStack tick(ItemStack stack, Level level) {
        if (stack.isEmpty()) return stack;
        ItemStack current = stack;
        boolean copied = false;
        if (!current.has(BannerboundAntiquity.FOOD_SPOILAGE.get())
            && FoodSpoilageData.isPerishable(current.getItem())) {
            current = stack.copy();
            copied = true;
            ensureStamped(current);
        }
        FoodSpoilage fs = current.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        if (fs == null) return current;

        double saltMult = fs.salted() ? FoodSpoilageData.saltLifeMultiplier() : 1.0;
        if (fs.isFresh()) {
            int phase = Math.max(1, FoodSpoilageData.freshTicks(current.getItem()));
            if (roll(level, ROLL_TICKS / (phase * saltMult))) {
                if (!copied) current = stack.copy();
                current.set(BannerboundAntiquity.FOOD_SPOILAGE.get(),
                    new FoodSpoilage(FoodSpoilage.BLAND, fs.salted()));
            }
        } else {
            int phase = Math.max(1, FoodSpoilageData.blandTicks(current.getItem()));
            if (roll(level, ROLL_TICKS / (phase * saltMult))) {
                return new ItemStack(BannerboundAntiquity.SPOILED_FOOD.get(), current.getCount());
            }
        }
        return current;
    }

    private static boolean roll(Level level, double chance) {
        return chance > 0.0 && level.getRandom().nextDouble() < chance;
    }

    public static void sweep(Container container, Level level) {
        int n = container.getContainerSize();
        for (int i = 0; i < n; i++) {
            ItemStack s = container.getItem(i);
            ItemStack r = tick(s, level);
            if (r != s) container.setItem(i, r);
        }
        // Players: compact only the main 36 slots - never pull food out of armor (36-39) or off-hand (40).
        int compactLimit = container instanceof net.minecraft.world.entity.player.Inventory
            ? Math.min(36, n) : n;
        compactFood(container, compactLimit);
    }

    private static void compactFood(Container container, int limit) {
        for (int i = 0; i < limit; i++) {
            ItemStack a = container.getItem(i);
            if (a.isEmpty() || a.getCount() >= a.getMaxStackSize() || !isCompactableFood(a)) continue;
            for (int j = i + 1; j < limit; j++) {
                ItemStack b = container.getItem(j);
                if (b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) continue;
                int move = Math.min(b.getCount(), a.getMaxStackSize() - a.getCount());
                if (move <= 0) continue;
                a.grow(move);
                b.shrink(move);
                container.setItem(j, b);
                if (a.getCount() >= a.getMaxStackSize()) break;
            }
            container.setItem(i, a);
        }
    }

    public static void compactStorage(IItemHandlerModifiable handler) {
        int n = handler.getSlots();
        for (int i = 0; i < n; i++) {
            // getStackInSlot stacks must NOT be mutated - work on copies, write back via setStackInSlot.
            ItemStack a = handler.getStackInSlot(i);
            if (a.isEmpty() || a.getCount() >= a.getMaxStackSize() || !isCompactableFood(a)) continue;
            a = a.copy();
            boolean changed = false;
            for (int j = i + 1; j < n; j++) {
                ItemStack b = handler.getStackInSlot(j);
                if (b.isEmpty() || !ItemStack.isSameItemSameComponents(a, b)) continue;
                int move = Math.min(b.getCount(), a.getMaxStackSize() - a.getCount());
                if (move <= 0) continue;
                a.grow(move);
                ItemStack bShrunk = b.copy();
                bShrunk.shrink(move);
                handler.setStackInSlot(j, bShrunk);
                changed = true;
                if (a.getCount() >= a.getMaxStackSize()) break;
            }
            if (changed) handler.setStackInSlot(i, a);
        }
    }

    private static boolean isCompactableFood(ItemStack stack) {
        return stack.is(BannerboundAntiquity.SPOILED_FOOD.get())
            || FoodSpoilageData.isPerishable(stack.getItem());
    }

    public static boolean applySalt(ItemStack food, Level level) {
        if (food.isEmpty() || !FoodSpoilageData.isPerishable(food.getItem())) return false;
        ensureStamped(food);
        FoodSpoilage fs = food.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        if (fs == null || fs.salted()) return false;
        food.set(BannerboundAntiquity.FOOD_SPOILAGE.get(), new FoodSpoilage(fs.level(), true));
        return true;
    }

    public static boolean isSalted(ItemStack stack) {
        FoodSpoilage fs = stack.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        return fs != null && fs.salted();
    }

    public static boolean isSpoiled(ItemStack stack) {
        return stack.is(BannerboundAntiquity.SPOILED_FOOD.get());
    }
}
