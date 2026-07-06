package com.bannerbound.core.language;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;

/**
 * Static entry point for turning a settlement's procedural "Tongue" into concrete words and citizen
 * names. Every method resolves through a {@link LanguageProfile} keyed on a stable seed: deriveSeed
 * folds the settlement UUID and name via an FNV-style hash so the same civ always speaks the same
 * language across saves. Seed-only overloads exist for callers that have no Settlement (barbarian
 * camps) and must reach the same lexicon.
 *
 * Key design decision on names: a citizen's given name is baked into the language ONCE at creation
 * (the stored name IS the in-language name; see CitizenEntity#initializeCitizen), so it is never
 * re-styled here. Surnames are EARNED later, so they stay display-time styled - these helpers
 * resolve just the family-name half on demand. When a job is known the surname borrows that
 * profession's family/worker-kind so it grows from the SAME stem as the "Work" word the lexicon
 * shows (a hunter's surname and the "Hunter" entry share a root); with no job it falls back to the
 * bare profession concept. surname() returns "" when a citizen has no surname yet.
 */
public final class SettlementLanguage {
    private SettlementLanguage() {
    }

    public static long deriveSeed(UUID settlementId, String name) {
        long seed = settlementId == null ? 0xB00DBA11L : settlementId.getMostSignificantBits() ^ settlementId.getLeastSignificantBits();
        byte[] bytes = (name == null ? "" : name).getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            seed ^= (b & 0xFFL);
            seed *= 0x100000001B3L;
        }
        return LanguageProfile.mix(seed, "settlement-language");
    }

    public static LanguageProfile profile(Settlement settlement) {
        return LanguageProfile.of(settlement == null ? 0L : settlement.languageSeed());
    }

    public static String word(Settlement settlement, LanguageConcept concept, LanguageRegister register) {
        Era era = settlement == null ? Era.ANCIENT : settlement.age();
        RegisterForms forms = profile(settlement).formsForConcept(concept, era);
        return forms.get(register == null ? LanguageRegister.COMMON : register);
    }

    public static String citizenName(Settlement settlement, String given, String surnameConcept,
                                     String surnameJob, String salt) {
        Era era = settlement == null ? Era.ANCIENT : settlement.age();
        return citizenName(settlement == null ? 0L : settlement.languageSeed(), era, given,
            surnameConcept, surnameJob, salt);
    }

    public static LanguageProfile profileForSeed(long seed) {
        return LanguageProfile.of(seed);
    }

    public static String word(long seed, Era era, LanguageConcept concept, LanguageRegister register) {
        RegisterForms forms = LanguageProfile.of(seed)
            .formsForConcept(concept, era == null ? Era.ANCIENT : era);
        return forms.get(register == null ? LanguageRegister.COMMON : register);
    }

    public static String citizenName(long seed, Era era, String given, String surnameConcept,
                                     String surnameJob, String salt) {
        Era e = era == null ? Era.ANCIENT : era;
        LanguageProfile profile = LanguageProfile.of(seed);
        String displayGiven = profile.formsForName(given, e, "given:" + salt).defaultName(e);
        String surname = surname(seed, e, surnameConcept, surnameJob, salt);
        return surname.isBlank() ? displayGiven : displayGiven + " " + surname;
    }

    public static String surname(Settlement settlement, String surnameConcept, String surnameJob,
                                 String salt) {
        Era era = settlement == null ? Era.ANCIENT : settlement.age();
        long seed = settlement == null ? 0L : settlement.languageSeed();
        return surname(seed, era, surnameConcept, surnameJob, salt);
    }

    public static String surname(long seed, Era era, String surnameConcept, String surnameJob,
                                 String salt) {
        if (surnameConcept == null || surnameConcept.isBlank()) {
            return "";
        }
        Era e = era == null ? Era.ANCIENT : era;
        LanguageProfile profile = LanguageProfile.of(seed);
        LanguageConcept concept = surnameConceptFor(surnameConcept, surnameJob);
        return profile.formsForFamilyName(concept, e,
                (salt == null ? "" : salt) + ":" + (surnameJob == null ? "" : surnameJob))
            .defaultName(e);
    }

    private static LanguageConcept surnameConceptFor(String surnameConcept, String surnameJob) {
        if (surnameJob != null && !surnameJob.isBlank()) {
            LanguageConcept job = LanguageConceptResolver.forJob(surnameJob, false);
            return LanguageConceptOverrideLoader.applyOverride(
                new LanguageConcept("surname:" + surnameJob, surnameConcept, ConceptRole.FAMILY_NAME,
                    job.family(), job.modifier(), job.kind()),
                "surname:" + surnameJob,
                surnameConcept + " " + surnameJob);
        }
        return LanguageConceptOverrideLoader.applyOverride(
            "surname:" + surnameConcept,
            surnameConcept,
            surnameConcept + " " + surnameConcept,
            LanguageConceptResolver.bestBase(surnameConcept, surnameConcept),
            ConceptRole.FAMILY_NAME);
    }
}
