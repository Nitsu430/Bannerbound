package com.bannerbound.core.api.hunter;

import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * Expansion hooks for the Hunter citizen job (see {@code HunterWorkGoal}). Core's hunter walks up to
 * wild prey and kills it with the tool-age melee weapon (or a bow once {@link #FLAG_ARCHERY} is
 * researched). Every {@link Extension} method has a Core-only default so a Core-only install hunts
 * in the plain walk-up-and-stab style; Antiquity plugs in its immersive-hunting layer via
 * {@link #setExtension} once during common setup (mirrors the {@code FishingVessels} provider
 * pattern; last registration wins; {@link #get} never returns null).
 *
 * <p>The extension surface: fed-livestock taming lives on an Antiquity-only attachment Core can't
 * read, so {@code isDomesticated} folds it into the "is this animal wild?" test (domesticated -&gt;
 * livestock, never prey). Fear / stealth lets the hunter crouch-stalk while {@code wantsStealth}
 * says the prey is still calm and switch to an open chase once {@code isPreyScared} flips. A spear
 * opener ({@code isThrowableSpear} + {@code throwSpear}) opens an engagement with a thrown spear
 * (bleed + slow) before the melee kill. {@code bowVelocityFactor} / {@code createArrow} let
 * expansion bows fire a slower, quality-scaled shot with its own arrow entity (1.0 = vanilla bow;
 * slower arrows also hit softer, like a player's), and {@code shootSling} fires a guard's ranged
 * rock. CRITICAL dupe invariant: any projectile spawned by {@code throwSpear} / {@code createArrow}
 * / {@code shootSling} is conjured from a copy of the reusable tool/ammo and MUST be marked
 * non-recoverable / no-pickup, or hunters and guards would mint free items every shot.
 */
public final class HunterHooks {
    public static final String FLAG_ARCHERY = "bannerbound.archery";

    public interface Extension {
        default boolean isDomesticated(Mob animal) {
            return false;
        }

        default boolean isPreyScared(Mob animal) {
            return false;
        }

        default boolean wantsStealth(CitizenEntity hunter, Mob target) {
            return false;
        }

        default boolean isThrowableSpear(ItemStack stack) {
            return false;
        }

        default boolean throwSpear(CitizenEntity hunter, Mob target, ItemStack spear, double damage) {
            return false;
        }

        default float bowVelocityFactor(ItemStack bow) {
            return 1.0F;
        }

        @org.jetbrains.annotations.Nullable
        default net.minecraft.world.entity.projectile.AbstractArrow createArrow(
                CitizenEntity hunter, ItemStack bow) {
            return null;
        }

        default boolean shootSling(CitizenEntity shooter,
                net.minecraft.world.entity.LivingEntity target, ItemStack sling, double damage) {
            return false;
        }
    }

    private static final Extension NO_OP = new Extension() {};

    private static Extension extension = NO_OP;

    private HunterHooks() {
    }

    public static void setExtension(Extension e) {
        extension = e == null ? NO_OP : e;
    }

    public static Extension get() {
        return extension;
    }
}
