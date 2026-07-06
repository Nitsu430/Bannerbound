package com.bannerbound.core.social;

import net.minecraft.nbt.CompoundTag;

/**
 * One citizen's view of one other citizen: the raw score plus two reserved overflow counters
 * ({@code loverProgress}, {@code bestFriendProgress}) that v2 will use for the mutex-overflow bars
 * without forcing an NBT migration. {@code isFamily} flips the entry into the permanent parent-child
 * bond, short-circuiting the score path entirely. Records are immutable; mutation goes through
 * {@link #withScoreDelta} which clamps into {@code [-Relationships.MIN, Relationships.MAX]} and
 * refreshes the interact tick. Family relationships absorb deltas unchanged - every conversation
 * outcome with a family member is a score no-op, keeping the family score locked at MAX; without
 * that guard the resolve path would slide the score below MAX (still rendering as FAMILY via
 * {@link #tier()} but drifting the field, breaking the invariant). {@link #save} omits empty
 * defaults to keep NBT compact; {@link #load} is tolerant - missing keys default to 0/false, so
 * pre-family saves load with {@code isFamily = false}.
 */
public record Relationship(int score, int loverProgress, int bestFriendProgress,
                           long lastInteractTick, boolean isFamily) {
    public static final Relationship STRANGERS = new Relationship(0, 0, 0, 0L, false);
    public static final Relationship FAMILY = new Relationship(
        Relationships.MAX, 0, 0, 0L, true);

    public Relationship withScoreDelta(int delta, long now) {
        if (isFamily) return this;
        int next = Math.max(Relationships.MIN, Math.min(Relationships.MAX, score + delta));
        return new Relationship(next, loverProgress, bestFriendProgress, now, false);
    }

    public RelationshipTier tier() {
        if (isFamily) return RelationshipTier.FAMILY;
        return RelationshipTier.of(score);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (score != 0)              t.putInt("S", score);
        if (loverProgress != 0)      t.putInt("L", loverProgress);
        if (bestFriendProgress != 0) t.putInt("B", bestFriendProgress);
        if (lastInteractTick != 0L)  t.putLong("T", lastInteractTick);
        if (isFamily)                t.putBoolean("F", true);
        return t;
    }

    public static Relationship load(CompoundTag t) {
        return new Relationship(
            t.contains("S") ? t.getInt("S")  : 0,
            t.contains("L") ? t.getInt("L")  : 0,
            t.contains("B") ? t.getInt("B")  : 0,
            t.contains("T") ? t.getLong("T") : 0L,
            t.contains("F") && t.getBoolean("F"));
    }
}
