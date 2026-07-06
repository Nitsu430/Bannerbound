package com.bannerbound.antiquity.recipe;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.item.ArrowParts;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The fletching station's free-mix arrow assembly. Rather than a JSON recipe per material
 * combination, a pile of exactly three parts - one tip, one shaft, one back, each a single item -
 * is matched by {@code tryMatch} and turned into a synthetic {@link FletchingRecipe} whose result
 * is a {@link #BATCH}-sized stack of composite arrows stamped with the corresponding
 * ARROW_TIP/SHAFT/BACK components. The station runs the normal stretch minigame on it, so quality
 * rolls exactly as for any other fletch; research gating on the result item is the caller's job.
 * Valid parts are resolved by {@link ArrowParts} (tag-mirrored in {@code tags/item/arrow_*}):
 * tips = flint blade / cast metal arrowheads; shafts = stick / metal ingot (metal costs an ingot
 * per batch); backs = feather / plant fiber. {@code ghostCandidate} previews a PARTIAL pile (any
 * mix of valid parts, at least one, no junk, <= 3 items total): missing slots are filled with the
 * registry's default flint/stick/feather parts so the station's ghost shows the parts still needed
 * and the arrow they would make - null when the pile holds no valid part, or a needed default part
 * isn't defined in the registry.
 */
@ApiStatus.Internal
public final class ModularArrow {
    private ModularArrow() {}

    public static final int BATCH = 16;

    @Nullable
    public static FletchingRecipe tryMatch(List<ItemStack> contents) {
        String tip = null;
        String shaft = null;
        String back = null;
        Item tipItem = null;
        Item shaftItem = null;
        Item backItem = null;
        int total = 0;

        for (ItemStack s : contents) {
            if (s.isEmpty()) continue;
            total += s.getCount();
            if (s.getCount() != 1 || total > 3) return null;

            Item it = s.getItem();
            String t = ArrowParts.tipMaterial(it);
            String sh = ArrowParts.shaftMaterial(it);
            String b = ArrowParts.backMaterial(it);
            if (t != null) {
                if (tip != null) return null;
                tip = t; tipItem = it;
            } else if (sh != null) {
                if (shaft != null) return null;
                shaft = sh; shaftItem = it;
            } else if (b != null) {
                if (back != null) return null;
                back = b; backItem = it;
            } else {
                return null;
            }
        }

        if (tip == null || shaft == null || back == null) return null;

        return recipe(tipItem, shaftItem, backItem, tip, shaft, back);
    }

    @Nullable
    public static FletchingRecipe ghostCandidate(List<ItemStack> contents) {
        String tip = null, shaft = null, back = null;
        Item tipItem = null, shaftItem = null, backItem = null;
        int total = 0;
        boolean any = false;

        for (ItemStack s : contents) {
            if (s.isEmpty()) continue;
            total += s.getCount();
            if (total > 3) return null;
            Item it = s.getItem();
            String t = ArrowParts.tipMaterial(it);
            String sh = ArrowParts.shaftMaterial(it);
            String b = ArrowParts.backMaterial(it);
            if (t != null) {
                if (tip != null) return null;
                tip = t; tipItem = it; any = true;
            } else if (sh != null) {
                if (shaft != null) return null;
                shaft = sh; shaftItem = it; any = true;
            } else if (b != null) {
                if (back != null) return null;
                back = b; backItem = it; any = true;
            } else {
                return null;
            }
        }
        if (!any) return null;

        if (tipItem == null)   { tip = ArrowParts.DEFAULT_TIP;   tipItem = ArrowParts.tipItem(tip); }
        if (shaftItem == null) { shaft = ArrowParts.DEFAULT_SHAFT; shaftItem = ArrowParts.shaftItem(shaft); }
        if (backItem == null)  { back = ArrowParts.DEFAULT_BACK;  backItem = ArrowParts.backItem(back); }
        if (tipItem == null || shaftItem == null || backItem == null) return null;
        return recipe(tipItem, shaftItem, backItem, tip, shaft, back);
    }

    private static FletchingRecipe recipe(Item tipItem, Item shaftItem, Item backItem,
                                          String tip, String shaft, String back) {
        List<FletchingRecipe.Ing> ings = List.of(
            new FletchingRecipe.Ing(tipItem, 1),
            new FletchingRecipe.Ing(shaftItem, 1),
            new FletchingRecipe.Ing(backItem, 1));
        ItemStack result = ArrowParts.makeArrow(tip, shaft, back, BATCH);
        // Minigame knob values deliberately match the old metal-arrow fletch recipe JSON.
        return new FletchingRecipe(ings, result, 3, 0.20F, 0.70F, 0.07F, 0.06F, Optional.empty());
    }
}
