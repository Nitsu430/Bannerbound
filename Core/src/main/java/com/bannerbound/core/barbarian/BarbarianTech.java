package com.bannerbound.core.barbarian;

import java.util.HashSet;
import java.util.Set;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Derives barbarian tech purely from world progress: camps are always exactly one research behind
 * the most advanced settlement.
 *
 * <p>"Most advanced" is the LIVE settlement furthest along right now (highest era, then most
 * completed research) - NOT the monotonic global "ever researched by anyone" set, which retains
 * research from disbanded settlements, {@code /bannerbound unlock} prereq-walks, and undone
 * {@code unresearch} commands. Barbarians know everything that settlement currently has EXCEPT its
 * single most-recent completion.
 *
 * <p>The global completion-ORDER log ({@link SettlementData#getGlobalResearchOrder()}) is used only
 * to identify which of the lead settlement's completions came last (so we drop the right one), so the
 * iteration order of that log is load-bearing (last match wins). When the lead settlement completes
 * archery, archery becomes its newest entry and the prior one drops into the camp known-set - camps
 * then start using primitive bows. No per-camp tech state, no simulation; a pure function evaluated
 * when a camp is observed or a raid is built.
 *
 * <p>Weaponry is derived from that known set: the camp's MELEE weapon comes from its highest
 * {@code set_tool_age} (the tree already orders bone->wood->stone->iron), plus a thin ranged override
 * that adds a bow when archery is known (see {@link BarbarianLoadoutLoader}). {@link #memberCapability}
 * rolls a per-member weapon role so a camp reads as a mixed roster (spear thrown; sword/club melee;
 * ~60% bows if archery) rather than a uniform wall of one weapon.
 */
public final class BarbarianTech {
    private BarbarianTech() {
    }

    public static Settlement mostAdvanced(SettlementData data) {
        Settlement lead = null;
        for (Settlement s : data.all()) {
            if (lead == null) {
                lead = s;
                continue;
            }
            int byEra = s.age().ordinal() - lead.age().ordinal();
            if (byEra > 0 || (byEra == 0
                    && s.completedResearches().size() > lead.completedResearches().size())) {
                lead = s;
            }
        }
        return lead;
    }

    public static String frontier(SettlementData data) {
        Settlement lead = mostAdvanced(data);
        if (lead == null) return null;
        return frontierOf(lead, data);
    }

    private static String frontierOf(Settlement lead, SettlementData data) {
        Set<String> completed = lead.completedResearches();
        String frontier = null;
        for (String id : data.getGlobalResearchOrder()) {
            if (completed.contains(id)) frontier = id;
        }
        return frontier;
    }

    public static Set<String> campKnownTech(SettlementData data) {
        Settlement lead = mostAdvanced(data);
        if (lead == null) return Set.of();
        Set<String> completed = lead.completedResearches();
        if (completed.size() <= 1) return Set.of();
        Set<String> known = new HashSet<>(completed);
        String frontier = frontierOf(lead, data);
        if (frontier != null) known.remove(frontier);
        return known;
    }

    public static Era techEra(Set<String> knownTech) {
        Era era = Era.ANCIENT;
        for (String id : knownTech) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > era.ordinal()) {
                era = def.minAge();
            }
        }
        return era;
    }

    private static final String SET_TOOL_AGE = "bannerbound.set_tool_age:";
    private static final String SPEAR_PROJECTILE = "bannerboundantiquity:spear";

    public static ToolAge campToolAge(Set<String> known) {
        ToolAge best = ToolAgeLoader.get("bone");
        int bestOrder = best == null ? Integer.MIN_VALUE : best.order();
        for (String id : known) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def == null) continue;
            for (String feat : def.unlocksFeatures()) {
                if (!feat.startsWith(SET_TOOL_AGE)) continue;
                ToolAge age = ToolAgeLoader.get(feat.substring(SET_TOOL_AGE.length()).trim());
                if (age != null && age.order() > bestOrder) {
                    best = age;
                    bestOrder = age.order();
                }
            }
        }
        return best;
    }

    public static BarbarianCapability capability(Set<String> known) {
        ToolAge age = campToolAge(known);
        Item meleeItem = age == null ? Items.AIR
            : age.tools().getOrDefault("spear", age.tools().getOrDefault("sword", Items.AIR));
        String meleeId = meleeItem == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(meleeItem).toString();
        int tier = age == null ? 0 : age.order();
        double dmg = age == null ? 2.0 : age.weaponDamage();
        double atk = age == null ? 1.2 : age.weaponAttackSpeed();

        BarbarianLoadoutLoader.Entry bow = BarbarianLoadoutLoader.rangedOverride(known);
        if (bow != null) {
            Set<String> behaviors = bow.behavior().isEmpty() ? Set.of() : Set.of(bow.behavior());
            return new BarbarianCapability(bow.weapon(), bow.weaponItem(), meleeId, tier + 1,
                bow.damage(), bow.attackSpeed(), true, bow.projectile(), behaviors, bow.squadWeight());
        }
        boolean isSpear = age != null && age.tools().containsKey("spear");
        return new BarbarianCapability(age == null ? "fists" : age.id() + "_weapon", meleeId, "", tier,
            dmg, atk, isSpear, isSpear ? SPEAR_PROJECTILE : "", Set.of("brute"), 1);
    }

    public static BarbarianCapability currentCapability(SettlementData data) {
        return capability(campKnownTech(data));
    }

    private static final String[] COMBAT_ROLES = {"spear", "sword", "club"};

    public static BarbarianCapability memberCapability(Set<String> known, net.minecraft.util.RandomSource rng) {
        ToolAge age = campToolAge(known);
        int tier = age == null ? 0 : age.order();
        double dmg = age == null ? 2.0 : age.weaponDamage();
        double atk = age == null ? 1.2 : age.weaponAttackSpeed();
        Item meleeFallback = age == null ? Items.AIR
            : age.tools().getOrDefault("sword", age.tools().getOrDefault("spear", Items.AIR));
        String meleeFallbackId = meleeFallback == Items.AIR ? ""
            : BuiltInRegistries.ITEM.getKey(meleeFallback).toString();

        BarbarianLoadoutLoader.Entry bow = BarbarianLoadoutLoader.rangedOverride(known);
        if (bow != null && rng.nextFloat() < 0.6f) {
            Set<String> behaviors = bow.behavior().isEmpty() ? Set.of() : Set.of(bow.behavior());
            return new BarbarianCapability(bow.weapon(), bow.weaponItem(), meleeFallbackId, tier + 1,
                bow.damage(), bow.attackSpeed(), true, bow.projectile(), behaviors, bow.squadWeight());
        }

        java.util.List<String> roles = new java.util.ArrayList<>();
        if (age != null) {
            for (String role : COMBAT_ROLES) {
                if (age.tools().containsKey(role)) roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            return new BarbarianCapability("fists", "", "", tier, dmg, atk, false, "", Set.of("brute"), 1);
        }
        String role = roles.get(rng.nextInt(roles.size()));
        Item weapon = age.tools().get(role);
        String weaponId = weapon == null || weapon == Items.AIR ? ""
            : BuiltInRegistries.ITEM.getKey(weapon).toString();
        boolean thrown = role.equals("spear");
        return new BarbarianCapability(age.id() + "_" + role, weaponId, "", tier, dmg, atk,
            thrown, thrown ? SPEAR_PROJECTILE : "", Set.of("brute"), 1);
    }
}
