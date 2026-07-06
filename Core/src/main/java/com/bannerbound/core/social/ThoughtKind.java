package com.bannerbound.core.social;

import net.minecraft.resources.ResourceLocation;

/**
 * The catalogue of Core's built-in per-citizen Thoughts. Each constant ships its default happiness
 * modifier, its label translation key, and the [min, max] duration range a fresh thought rolls into
 * (INFINITE_DURATION == -1 means "no expiry", held until the trigger condition resolves, e.g.
 * UNEMPLOYED clears when a workstation is assigned; Thoughts.tick never decays those). Adding a
 * thought is a one-liner here -- trigger code refers to the enum value, never to the raw numbers.
 *
 * <p>Two flavours: single-instance (only one per citizen; adding refreshes) and per-partner
 * (isPerPartner() -- multiple coexist keyed by partner UUID: conversation outcomes, MY_CHILD_BORN,
 * the five *_DIED death thoughts, and NO_WORK_AS_JOB whose "partner" is a UUID derived from the job
 * id so a Forester refusal does not block a later Farmer assignment). Escalating kinds (UNEMPLOYED,
 * NO_HOME, STARVING) deepen linearly from modifier toward escalationFloor over escalationRampTicks of
 * continuous activity -- an ignored grievance festers; modifierAt does the ramp and every reader goes
 * through Thought.effectiveModifier so the displayed and the felt numbers match. category() sorts each
 * kind into a happiness pillar (Food/Culture/Comfort/Society) that the Citizen screen groups into rings.
 * NICE_HOME intentionally duplicates LIKE_HOME's +5 under a distinct user-spec'd "Nice housing" label
 * so the daily home eval can split them later -- it is not a redundant constant to deduplicate.
 *
 * <p>Persistence: legacy saves key thoughts by ordinal ("K"), new saves by id ("KID",
 * bannerbound:<lowercased name>), so the ordering is an on-disk contract -- new constants are
 * APPEND-ONLY. Each constant self-registers into ThoughtTypes on class init (bootstrap() forces that
 * init from the registry); expansion thoughts register their own ThoughtType rather than extend this enum.
 */
public enum ThoughtKind implements ThoughtType {
    RECENTLY_RAINED ("bannerbound.thought.recently_rained",          +2, 2_400, 3_600),
    RECENTLY_STORMED("bannerbound.thought.recently_stormed",         -5, 3_600, 4_800),

    ROUGH_LODGING   ("bannerbound.thought.rough_lodging",            -4, 10_000, 14_000),

    ARGUMENT_WITH          ("bannerbound.thought.argument_with",            -5, 1_200, 2_400),
    FIGHT_WITH             ("bannerbound.thought.fight_with",              -10, 2_400, 3_600),
    GREAT_CONVERSATION_WITH("bannerbound.thought.great_conversation_with",  +5, 1_200, 2_400),

    // -1 == INFINITE_DURATION, inlined: enum constants init before static fields, so the field is not visible here yet.
    UNEMPLOYED("bannerbound.thought.unemployed", -5, -1, -1, -30, 48_000),

    LIKE_HERE         ("bannerbound.thought.like_here",          +5, 24_000, 24_000),
    LOVE_HERE         ("bannerbound.thought.love_here",         +15, 24_000, 24_000),
    UNCOMFORTABLE_HERE("bannerbound.thought.uncomfortable_here",-10, 24_000, 24_000),
    HATE_HERE         ("bannerbound.thought.hate_here",         -25, 24_000, 24_000),

    NO_HOME           ("bannerbound.thought.no_home",            -5, -1, -1, -40, 48_000),
    NICE_HOME         ("bannerbound.thought.nice_home",          +5, 24_000, 24_000),
    LIKE_HOME         ("bannerbound.thought.like_home",          +5, 24_000, 24_000),
    LOVE_HOME         ("bannerbound.thought.love_home",         +15, 24_000, 24_000),
    UNCOMFORTABLE_HOME("bannerbound.thought.uncomfortable_home",-10, 24_000, 24_000),
    HATE_HOME         ("bannerbound.thought.hate_home",         -25, 24_000, 24_000),

    PROGRESSED_RECENTLY("bannerbound.thought.progressed_recently", +5, 6_000, 6_000),

    MY_CHILD_BORN("bannerbound.thought.my_child_born", +25, 24_000, 24_000),

    NEW_CHILD_IN_SETTLEMENT("bannerbound.thought.new_child_in_settlement", +15, 12_000, 12_000),

    CHILD_SAW_BIRD     ("bannerbound.thought.child_saw_bird",      +5, 3_600, 3_600),
    CHILD_CURIOUS      ("bannerbound.thought.child_curious",       +5, 3_600, 3_600),
    CHILD_MUDDY_PUDDLE ("bannerbound.thought.child_muddy_puddle",  +5, 3_600, 3_600),
    CHILD_FOUND_PEBBLE ("bannerbound.thought.child_found_pebble",  +5, 3_600, 3_600),

    DIED_RECENTLY            ("bannerbound.thought.died_recently",             -5,  6_000, 6_000),
    FRIEND_DIED              ("bannerbound.thought.friend_died",              -10,  6_000, 6_000),
    CLOSE_FRIEND_DIED        ("bannerbound.thought.close_friend_died",        -15,  6_000, 6_000),
    FRIEND_FOR_LIFE_DIED     ("bannerbound.thought.friend_for_life_died",     -20,  6_000, 6_000),
    FAMILY_DIED              ("bannerbound.thought.family_died",              -30,  6_000, 6_000),

    STARVING                  ("bannerbound.thought.starving",                 -10,    -1,    -1, -40, 24_000),
    WAS_STARVING_RECENTLY     ("bannerbound.thought.was_starving_recently",    -5,  2_400, 2_400),
    EATING_WELL               ("bannerbound.thought.eating_well",              +5,    -1,    -1),
    EATING_VERY_WELL          ("bannerbound.thought.eating_very_well",        +10,    -1,    -1),

    I_M_BADLY_INJURED         ("bannerbound.thought.badly_injured",            -10,    -1,    -1),
    I_M_IN_PAIN               ("bannerbound.thought.in_pain",                   -3,    -1,    -1),

    NO_WORK_RIGHT_NOW("bannerbound.thought.no_work_right_now", -2, 1_200, 1_200),
    NO_WORK_AS_JOB   ("bannerbound.thought.no_work_as_job",    -2, 1_200, 1_200),
    NO_WORK_TODAY    ("bannerbound.thought.no_work_today",     -5, 24_000, 24_000),

    NIGHTSHIFT_FATIGUE ("bannerbound.thought.nightshift_fatigue", -10, -1, -1),
    DOMESTICATION_HAPPY("bannerbound.thought.domestication_happy", +15, -1, -1),
    NIGHT_WATCH_WEARY  ("bannerbound.thought.night_watch_weary",   -12, -1, -1),

    PROMOTED("bannerbound.thought.promoted", +10, 6_000, 6_000),

    LOVELY_WORKPLACE("bannerbound.thought.lovely_workplace", +5, 6_000, 7_200),
    DREARY_WORKPLACE("bannerbound.thought.dreary_workplace", -5, 6_000, 7_200),

    FORSOOK_THE_GODS("bannerbound.thought.forsook_the_gods", -8, 48_000, 48_000),

    CRISIS_STARTED("bannerbound.thought.crisis_started", -8, 24_000, 24_000),
    CRISIS_RESOLVED("bannerbound.thought.crisis_resolved", +8, 12_000, 12_000),

    WAR_WEARINESS("bannerbound.thought.war_weariness", -10, -1, -1),
    RALLYING_SPEECHES("bannerbound.thought.rallying_speeches", -10, -1, -1),
    GLORY_TALES("bannerbound.thought.glory_tales", +10, 24_000, 24_000),

    // APPEND-ONLY: append new CORE built-ins below POISONED; never insert above (breaks legacy ordinal saves).
    POISONED("bannerbound.thought.poisoned", -12, -1, -1);

    public static final ThoughtKind[] CHILD_FLAVOUR_THOUGHTS = {
        CHILD_SAW_BIRD, CHILD_CURIOUS, CHILD_MUDDY_PUDDLE, CHILD_FOUND_PEBBLE
    };

    public static final int INFINITE_DURATION = -1;

    private final String labelKey;
    private final int modifier;
    private final int minDurationTicks;
    private final int maxDurationTicks;
    private final int escalationFloor;
    private final int escalationRampTicks;

    ThoughtKind(String labelKey, int modifier, int minDurationTicks, int maxDurationTicks) {
        this(labelKey, modifier, minDurationTicks, maxDurationTicks, modifier, 0);
    }

    ThoughtKind(String labelKey, int modifier, int minDurationTicks, int maxDurationTicks,
                int escalationFloor, int escalationRampTicks) {
        this.labelKey = labelKey;
        this.modifier = modifier;
        this.minDurationTicks = minDurationTicks;
        this.maxDurationTicks = maxDurationTicks;
        this.escalationFloor = escalationFloor;
        this.escalationRampTicks = escalationRampTicks;
    }

    public boolean escalates() { return escalationRampTicks > 0 && escalationFloor != modifier; }

    public int modifierAt(long ageTicks) {
        if (!escalates() || ageTicks <= 0) return modifier;
        if (ageTicks >= escalationRampTicks) return escalationFloor;
        double t = ageTicks / (double) escalationRampTicks;
        return (int) Math.round(modifier + t * (escalationFloor - modifier));
    }

    public String labelKey() { return labelKey; }
    public int modifier() { return modifier; }
    public boolean isInfinite() { return minDurationTicks == INFINITE_DURATION; }
    public boolean isPerPartner() {
        return this == ARGUMENT_WITH || this == FIGHT_WITH || this == GREAT_CONVERSATION_WITH
            || this == MY_CHILD_BORN
            || this == DIED_RECENTLY || this == FRIEND_DIED || this == CLOSE_FRIEND_DIED
            || this == FRIEND_FOR_LIFE_DIED || this == FAMILY_DIED
            || this == NO_WORK_AS_JOB;
    }

    @Override
    public HappinessCategory category() {
        return switch (this) {
            case STARVING, WAS_STARVING_RECENTLY, EATING_WELL, EATING_VERY_WELL ->
                HappinessCategory.FOOD;
            case LIKE_HERE, LOVE_HERE, UNCOMFORTABLE_HERE, HATE_HERE,
                 MY_CHILD_BORN, NEW_CHILD_IN_SETTLEMENT,
                 CHILD_SAW_BIRD, CHILD_CURIOUS, CHILD_MUDDY_PUDDLE, CHILD_FOUND_PEBBLE,
                 PROGRESSED_RECENTLY, FORSOOK_THE_GODS, GLORY_TALES ->
                HappinessCategory.CULTURE;
            case RECENTLY_RAINED, RECENTLY_STORMED, ROUGH_LODGING,
                 NO_HOME, NICE_HOME, LIKE_HOME, LOVE_HOME, UNCOMFORTABLE_HOME, HATE_HOME,
                 I_M_BADLY_INJURED, I_M_IN_PAIN, POISONED,
                 CRISIS_STARTED, CRISIS_RESOLVED, WAR_WEARINESS, NIGHT_WATCH_WEARY ->
                HappinessCategory.COMFORT;
            case UNEMPLOYED, NO_WORK_RIGHT_NOW, NO_WORK_AS_JOB, NO_WORK_TODAY,
                 NIGHTSHIFT_FATIGUE, DOMESTICATION_HAPPY, PROMOTED,
                 LOVELY_WORKPLACE, DREARY_WORKPLACE,
                 ARGUMENT_WITH, FIGHT_WITH, GREAT_CONVERSATION_WITH,
                 DIED_RECENTLY, FRIEND_DIED, CLOSE_FRIEND_DIED, FRIEND_FOR_LIFE_DIED, FAMILY_DIED,
                 RALLYING_SPEECHES ->
                HappinessCategory.SOCIETY;
        };
    }

    public static ThoughtKind deathThoughtFor(RelationshipTier tier) {
        return switch (tier) {
            case FAMILY            -> FAMILY_DIED;
            case FRIENDS_FOR_LIFE  -> FRIEND_FOR_LIFE_DIED;
            case CLOSE_FRIENDS     -> CLOSE_FRIEND_DIED;
            case FRIENDS           -> FRIEND_DIED;
            case ACQUAINTANCES, STRANGERS, DISLIKED -> DIED_RECENTLY;
            default -> null;
        };
    }

    public int rollDurationTicks(net.minecraft.util.RandomSource rng) {
        if (isInfinite()) return INFINITE_DURATION;
        if (maxDurationTicks <= minDurationTicks) return minDurationTicks;
        return minDurationTicks + rng.nextInt(maxDurationTicks - minDurationTicks + 1);
    }

    private static final ThoughtKind[] VALUES = values();

    static {
        for (ThoughtKind k : VALUES) {
            ThoughtTypes.register(k);
        }
    }

    public static void bootstrap() {}

    private ResourceLocation id;

    @Override
    public ResourceLocation id() {
        if (id == null) {
            id = ResourceLocation.fromNamespaceAndPath("bannerbound", name().toLowerCase(java.util.Locale.ROOT));
        }
        return id;
    }

    public static ThoughtKind fromOrdinal(int ord) {
        return (ord >= 0 && ord < VALUES.length) ? VALUES[ord] : null;
    }
}
