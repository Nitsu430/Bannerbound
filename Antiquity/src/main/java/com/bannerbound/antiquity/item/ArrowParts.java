package com.bannerbound.antiquity.item;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.recipe.ArrowPart;
import com.bannerbound.antiquity.recipe.ArrowPartRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The accessor everything goes through for the modular {@link CompositeArrowItem arrow}'s three
 * parts - tip, shaft, back. Each part is a short material id stored on the stack as the
 * ARROW_TIP/SHAFT/BACK data components; the part's stats, ingredient item, and textures all come
 * from the DATA-DRIVEN {@link ArrowPartRegistry} (datapack arrow_parts/*.json), so a modpack adds
 * an arrow material with a JSON + two textures and it flows through crafting, stats, the NPC
 * fletcher, the in-flight projectile, and the inventory icon with no code. Defaults (a bare
 * {@code new ItemStack(ARROW)} or an undefined material): flint tip / wood shaft / feather back -
 * the basic arrow - with neutral stats if the registry has no matching part. {@link #sorted} lists
 * a slot's parts best-first (an NPC fletcher consumes the highest-priority stocked part). Derived
 * stats: damage = the tip's base factor bumped 4% per shaft weight point, inaccuracy multiplies
 * from the back/fletching (lower = tighter grouping), and gravity scales up from vanilla's 0.05
 * with combined tip+shaft weight. {@link #partsKnown} is the knowledge gate shared by the server
 * ({@code ItemKnowledge}) and client ({@code UnknownItemHelper}) via a side-appropriate predicate:
 * a foreign arrow is unusable ("???", can't be fired) until ALL its part ingredients are known,
 * an unregistered material counts as unknown, and non-arrow stacks are never restricted.
 */
@ApiStatus.Internal
public final class ArrowParts {
    private ArrowParts() {}

    public static final String DEFAULT_TIP = "flint";
    public static final String DEFAULT_SHAFT = "wood";
    public static final String DEFAULT_BACK = "feather";

    public static String tip(ItemStack stack) {
        String v = stack.get(BannerboundAntiquity.ARROW_TIP.get());
        return v == null ? DEFAULT_TIP : v;
    }

    public static String shaft(ItemStack stack) {
        String v = stack.get(BannerboundAntiquity.ARROW_SHAFT.get());
        return v == null ? DEFAULT_SHAFT : v;
    }

    public static String back(ItemStack stack) {
        String v = stack.get(BannerboundAntiquity.ARROW_BACK.get());
        return v == null ? DEFAULT_BACK : v;
    }

    public static ItemStack makeArrow(String tip, String shaft, String back, int count) {
        ItemStack stack = new ItemStack(BannerboundAntiquity.ARROW.get(), count);
        stack.set(BannerboundAntiquity.ARROW_TIP.get(), tip);
        stack.set(BannerboundAntiquity.ARROW_SHAFT.get(), shaft);
        stack.set(BannerboundAntiquity.ARROW_BACK.get(), back);
        return stack;
    }

    @Nullable public static ArrowPart tipPart(ItemStack stack)   { return ArrowPartRegistry.get(ArrowPart.SLOT_TIP, tip(stack)); }
    @Nullable public static ArrowPart shaftPart(ItemStack stack) { return ArrowPartRegistry.get(ArrowPart.SLOT_SHAFT, shaft(stack)); }
    @Nullable public static ArrowPart backPart(ItemStack stack)  { return ArrowPartRegistry.get(ArrowPart.SLOT_BACK, back(stack)); }

    @Nullable
    public static String materialOf(String slot, Item item) {
        for (ArrowPart p : ArrowPartRegistry.sorted(slot)) {
            if (p.ingredient() == item) return p.material();
        }
        return null;
    }

    @Nullable public static String tipMaterial(Item item)   { return materialOf(ArrowPart.SLOT_TIP, item); }
    @Nullable public static String shaftMaterial(Item item) { return materialOf(ArrowPart.SLOT_SHAFT, item); }
    @Nullable public static String backMaterial(Item item)  { return materialOf(ArrowPart.SLOT_BACK, item); }

    @Nullable
    public static Item ingredient(String slot, String material) {
        ArrowPart p = ArrowPartRegistry.get(slot, material);
        return p == null ? null : p.ingredient();
    }

    @Nullable public static Item tipItem(String material)   { return ingredient(ArrowPart.SLOT_TIP, material); }
    @Nullable public static Item shaftItem(String material) { return ingredient(ArrowPart.SLOT_SHAFT, material); }
    @Nullable public static Item backItem(String material)  { return ingredient(ArrowPart.SLOT_BACK, material); }

    public static List<ArrowPart> sorted(String slot) {
        return ArrowPartRegistry.sorted(slot);
    }

    public static List<Item> allPartItems() {
        List<Item> out = new ArrayList<>();
        for (ArrowPart p : ArrowPartRegistry.all()) out.add(p.ingredient());
        return out;
    }

    public static double damageMultiplier(ItemStack stack) {
        ArrowPart t = tipPart(stack);
        ArrowPart s = shaftPart(stack);
        double tipFactor = t == null ? 1.0 : t.damage();
        int shaftWeight = s == null ? 0 : s.weight();
        return tipFactor * (1.0 + shaftWeight * 0.04);
    }

    public static int weightPoints(ItemStack stack) {
        ArrowPart t = tipPart(stack);
        ArrowPart s = shaftPart(stack);
        return (t == null ? 0 : t.weight()) + (s == null ? 0 : s.weight());
    }

    public static float inaccuracyMultiplier(ItemStack stack) {
        ArrowPart b = backPart(stack);
        return b == null ? 1.0F : (float) b.accuracy();
    }

    public static double gravityFor(ItemStack stack) {
        return 0.05 * (1.0 + 0.07 * weightPoints(stack));
    }

    public static boolean partsKnown(ItemStack stack, java.util.function.Predicate<Item> known) {
        if (!(stack.getItem() instanceof CompositeArrowItem)) {
            return true;
        }
        return partKnown(ArrowPart.SLOT_TIP, tip(stack), known)
            && partKnown(ArrowPart.SLOT_SHAFT, shaft(stack), known)
            && partKnown(ArrowPart.SLOT_BACK, back(stack), known);
    }

    private static boolean partKnown(String slot, String material, java.util.function.Predicate<Item> known) {
        Item ing = ingredient(slot, material);
        return ing != null && known.test(ing);
    }

    @Nullable
    public static ResourceLocation itemTexture(String slot, String material) {
        ArrowPart p = ArrowPartRegistry.get(slot, material);
        return p == null ? null : p.itemTexture();
    }

    @Nullable
    public static ResourceLocation projectileTexture(String slot, String material) {
        ArrowPart p = ArrowPartRegistry.get(slot, material);
        return p == null ? null : p.projectileTexture();
    }
}
