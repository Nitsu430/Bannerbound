package com.bannerbound.core.social;

/**
 * Bucket label for a relationship's signed score, resolved by {@link #of(int)} from a raw -100..100
 * score against the thresholds in {@link Relationships}. Positive tiers are user-defined and stable;
 * the negative tiers are placeholder names - only the labels change, not the cutoffs. Ladder:
 * HATED <=-80, ENEMIES -79..-50, RIVALS -49..-25, DISLIKED -24..-10, STRANGERS -9..9,
 * ACQUAINTANCES 10..24, FRIENDS 25..49, CLOSE_FRIENDS 50..79, FRIENDS_FOR_LIFE >=80.
 * <p>
 * FAMILY is the permanent parent-child bond: stronger than every score-based tier, never decays,
 * never removable except by the dead-citizen cleanup. {@link Relationship#tier()} returns it
 * whenever {@code isFamily} is set - the score on family entries is locked at 100 and never moves
 * through conversations. {@link #displayLabel()} renders the enum name as words (e.g. "Friends for
 * Life").
 */
public enum RelationshipTier {
    HATED,
    ENEMIES,
    RIVALS,
    DISLIKED,
    STRANGERS,
    ACQUAINTANCES,
    FRIENDS,
    CLOSE_FRIENDS,
    FRIENDS_FOR_LIFE,
    FAMILY;

    public String displayLabel() {
        String[] parts = name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    public static RelationshipTier of(int score) {
        if (score >= Relationships.FRIENDS_FOR_LIFE) return FRIENDS_FOR_LIFE;
        if (score >= Relationships.CLOSE_FRIENDS)    return CLOSE_FRIENDS;
        if (score >= Relationships.FRIENDS)          return FRIENDS;
        if (score >= Relationships.ACQUAINTANCES)    return ACQUAINTANCES;
        if (score > -Relationships.ACQUAINTANCES)    return STRANGERS;
        if (score > -Relationships.FRIENDS)          return DISLIKED;
        if (score > -Relationships.CLOSE_FRIENDS)    return RIVALS;
        if (score > -Relationships.FRIENDS_FOR_LIFE) return ENEMIES;
        return HATED;
    }
}
