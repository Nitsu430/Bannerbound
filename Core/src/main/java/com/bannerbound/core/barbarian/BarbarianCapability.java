package com.bannerbound.core.barbarian;

import java.util.Set;

/**
 * The resolved combat/behaviour capability of a camp, folded from every loadout entry whose gating
 * research is in the camp's known-tech set (BarbarianLoadoutLoader.resolve), capped at that set so a
 * camp can never wield a weapon whose research the world hasn't (almost) reached. weaponItem/
 * meleeWeaponItem/projectile are item/entity ids ("" = fists / none), resolved to concrete objects at
 * spawn because camp NPCs have no settlement; the highest weaponTier among known entries wins. ranged
 * fires projectile at range with meleeWeaponItem as the up-close fallback; behaviors is the union of
 * tags across known entries and drives kites() (ranged skirmishers hold distance vs brutes that
 * charge). FISTS is the know-nothing fallback.
 */
public record BarbarianCapability(String weaponKey, String weaponItem, String meleeWeaponItem,
                                  int weaponTier, double damage, double attackSpeed, boolean ranged,
                                  String projectile, Set<String> behaviors, int squadWeight) {

    public static final BarbarianCapability FISTS =
        new BarbarianCapability("fists", "", "", 0, 1.0, 1.0, false, "", Set.of(), 1);

    public boolean has(String behavior) {
        return behaviors.contains(behavior);
    }

    public boolean kites() {
        return ranged && behaviors.contains("skirmisher");
    }
}
