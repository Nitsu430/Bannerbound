package com.bannerbound.core.api.faith;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The faith passive-effect tables + aggregation (FAITH_PLAN Part 3 - Hybrid gods).
 *
 * <p>Two tables, structured as data so a JSON override-loader drops in later without
 * touching callers: {@code DOMAIN_EFFECTS} maps each of the 7 domains -> its base passive
 * effect(s); {@code COMBO_EFFECTS} maps a domain PAIR (unordered, keyed by {@code comboKey})
 * -> its synergy effect(s).
 *
 * <p>Application rules per constellation: a PURE god (no secondary) gets its primary domain's
 * effects x{@value #PURITY_BONUS}; a HYBRID with a defined combo gets primary effects x1.0 plus
 * the combo synergy; a hybrid with no combo defined gets primary effects x1.0 and no bonus, so
 * undefined pairs are never strictly better than a pure god (which keeps combo authoring
 * worthwhile). {@code computeInto} recomputes a bundle for a whole pantheon; {@code effectsFor}
 * describes one god for client-safe UI readouts.
 *
 * <p>Magnitudes live in code for now; the doc's {@code faith_combos/*.json} loader is a
 * localized future swap. SEA->food and WAR->speed are placeholders until the bite-rate and
 * combat hooks exist (a data/value edit, no structural change).
 */
public final class FaithEffects {
    public static final double PURITY_BONUS = 1.25;

    private static final Map<DeityDomain, List<FaithEffect>> DOMAIN_EFFECTS =
        new EnumMap<>(DeityDomain.class);
    private static final Map<Integer, List<FaithEffect>> COMBO_EFFECTS = new HashMap<>();

    static {
        domain(DeityDomain.HARVEST, FaithEffectType.FOOD_PER_SECOND, 0.15);
        domain(DeityDomain.KNOWLEDGE, FaithEffectType.SCIENCE_PER_SECOND, 0.15);
        domain(DeityDomain.CRAFT, FaithEffectType.CULTURE_PER_SECOND, 0.10);
        domain(DeityDomain.JOURNEY, FaithEffectType.CITIZEN_SPEED, 0.02);
        domain(DeityDomain.KINSHIP, FaithEffectType.DEVOTION_PER_SECOND, 0.03);
        domain(DeityDomain.SEA, FaithEffectType.FOOD_PER_SECOND, 0.12);
        domain(DeityDomain.WAR, FaithEffectType.CITIZEN_SPEED, 0.02);

        combo(DeityDomain.HARVEST, DeityDomain.SEA, FaithEffectType.FOOD_PER_SECOND, 0.10);
        combo(DeityDomain.HARVEST, DeityDomain.KINSHIP, FaithEffectType.FOOD_PER_SECOND, 0.10);
        combo(DeityDomain.KNOWLEDGE, DeityDomain.CRAFT, FaithEffectType.SCIENCE_PER_SECOND, 0.10);
        combo(DeityDomain.KNOWLEDGE, DeityDomain.JOURNEY, FaithEffectType.SCIENCE_PER_SECOND, 0.10);
        combo(DeityDomain.WAR, DeityDomain.JOURNEY, FaithEffectType.CITIZEN_SPEED, 0.03);
        combo(DeityDomain.KINSHIP, DeityDomain.SEA, FaithEffectType.DEVOTION_PER_SECOND, 0.04);
        combo(DeityDomain.CRAFT, DeityDomain.HARVEST, FaithEffectType.CULTURE_PER_SECOND, 0.08);
    }

    private FaithEffects() {
    }

    public static void computeInto(FaithEffectBundle out, Faith faith) {
        out.clear();
        if (faith == null) return;
        for (Constellation c : faith.constellations()) {
            List<FaithEffect> primary = DOMAIN_EFFECTS.get(c.primaryDomain());
            if (c.secondaryDomain() == null) {
                if (primary != null) {
                    for (FaithEffect e : primary) out.add(e.type(), e.value() * PURITY_BONUS);
                }
            } else {
                if (primary != null) {
                    for (FaithEffect e : primary) out.add(e.type(), e.value());
                }
                List<FaithEffect> combo = COMBO_EFFECTS.get(comboKey(c.primaryDomain(), c.secondaryDomain()));
                if (combo != null) {
                    for (FaithEffect e : combo) out.add(e.type(), e.value());
                }
            }
        }
    }

    public static List<FaithEffect> effectsFor(Constellation c) {
        return effectsFor(c.primaryDomain(), c.secondaryDomain());
    }

    public static List<FaithEffect> effectsFor(DeityDomain primaryDomain,
                                               @org.jetbrains.annotations.Nullable DeityDomain secondaryDomain) {
        List<FaithEffect> out = new ArrayList<>();
        List<FaithEffect> primary = DOMAIN_EFFECTS.get(primaryDomain);
        if (secondaryDomain == null) {
            if (primary != null) {
                for (FaithEffect e : primary) out.add(new FaithEffect(e.type(), e.value() * PURITY_BONUS));
            }
        } else {
            if (primary != null) out.addAll(primary);
            List<FaithEffect> combo = COMBO_EFFECTS.get(comboKey(primaryDomain, secondaryDomain));
            if (combo != null) out.addAll(combo);
        }
        return out;
    }

    private static int comboKey(DeityDomain a, DeityDomain b) {
        int lo = Math.min(a.ordinal(), b.ordinal());
        int hi = Math.max(a.ordinal(), b.ordinal());
        return lo * 100 + hi;
    }

    private static void domain(DeityDomain d, FaithEffectType type, double value) {
        DOMAIN_EFFECTS.computeIfAbsent(d, k -> new ArrayList<>()).add(new FaithEffect(type, value));
    }

    private static void combo(DeityDomain a, DeityDomain b, FaithEffectType type, double value) {
        COMBO_EFFECTS.computeIfAbsent(comboKey(a, b), k -> new ArrayList<>())
            .add(new FaithEffect(type, value));
    }

    public record FaithEffect(FaithEffectType type, double value) {
    }
}
