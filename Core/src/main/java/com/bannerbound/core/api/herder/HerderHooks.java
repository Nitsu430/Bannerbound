package com.bannerbound.core.api.herder;

import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/**
 * Expansion hook for the Herder citizen job (see {@code HerderWorkGoal}). When the herder culls a
 * surplus animal it rolls the vanilla loot table straight into the harvest chest; an expansion can
 * add its own harvest product via an {@link Extension} - Antiquity drops a quality-tagged raw HIDE
 * (the tannery's input) graded by the pen's living conditions and the herder's skill. Core supplies
 * the two scalars it can compute (living-conditions 0..1 = {@code BreedingEvents.penBreedQuality},
 * and herder {@code "herders_pen"} XP) plus the vanilla {@code Animal}; the expansion owns the item
 * id and the POOR/STANDARD/GREAT mapping and returns a finished, already-tagged {@link ItemStack},
 * so Core never names an expansion type. Mirrors {@link com.bannerbound.core.api.hunter.HunterHooks}:
 * Core ships a no-op default and an expansion calls {@link #setExtension} once during common setup
 * (last non-null wins). {@link #get} never returns null.
 */
public final class HerderHooks {
    public interface Extension {
        default ItemStack herdHide(Animal victim, double livingConditions, int herderXp) {
            return ItemStack.EMPTY;
        }
    }

    private static final Extension NO_OP = new Extension() {};

    private static Extension extension = NO_OP;

    private HerderHooks() {
    }

    public static void setExtension(Extension e) {
        extension = e == null ? NO_OP : e;
    }

    public static Extension get() {
        return extension;
    }
}
