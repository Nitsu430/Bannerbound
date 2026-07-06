package com.bannerbound.core.language;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.network.chat.Component;

/**
 * Public helpers that render controlled, name-like surfaces (job labels, citizen names) in a
 * settlement's generated "Tongue". Given names are styled ONCE at citizen creation (styleGiven) so
 * the stored name already IS the in-language name that chat / recall / roster / workshop surfaces
 * read; detached citizens (null settlement) keep the base verbatim. compose appends the
 * separately-styled earned surname per-call, using the baked given name verbatim. clientJob mirrors
 * job() off a synced seed for client-only rendering.
 */
public final class CustomLanguageLabel {
    private CustomLanguageLabel() {
    }

    public static Component job(Settlement settlement, String jobTypeId, boolean quarryUnlocked) {
        LanguageConcept concept = LanguageConceptResolver.forJob(jobTypeId, quarryUnlocked);
        String label = SettlementLanguage.word(settlement, concept, LanguageRegister.COMMON);
        return Component.literal(capitalize(label));
    }

    public static Component clientJob(long seed, Era era, String jobTypeId, boolean quarryUnlocked) {
        LanguageConcept concept = LanguageConceptResolver.forJob(jobTypeId, quarryUnlocked);
        String label = LanguageProfile.of(seed)
            .formsForConcept(concept, era)
            .defaultWord(era, concept.role());
        return Component.literal(capitalize(label));
    }

    public static String styleGiven(Settlement settlement, String base, String salt) {
        if (base == null) return null;
        if (settlement == null) return base;
        return SettlementLanguage.citizenName(settlement, base, null, null, salt);
    }

    public static String compose(Settlement settlement, String styledGiven, String surnameConcept,
                                 String surnameJob, String salt) {
        if (styledGiven == null) return "";
        if (surnameConcept == null || surnameConcept.isBlank()) return styledGiven;
        String surname = settlement != null
            ? SettlementLanguage.surname(settlement, surnameConcept, surnameJob, salt)
            : LanguageConceptResolver.surnameFallback(surnameConcept, surnameJob);
        return (surname == null || surname.isBlank()) ? styledGiven : styledGiven + " " + surname;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "Na";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
