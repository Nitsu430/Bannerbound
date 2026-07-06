package com.bannerbound.core.api.quality;

/**
 * Single source of truth for turning per-step minigame scores into a {@link QualityTier}. Shared by
 * every quality-producing minigame (the fletching stretch bar, metalworking's cold-hammer, ...) and
 * by Crafter-NPC simulation, so quality is computed identically no matter who produced it.
 * {@link #clampScore} bounds one step to 0..100 (defends against bad client input), {@link #aggregate}
 * averages the steps (empty = 0 = Crude), and {@link #tierFromScores} maps that to a player/NPC tier.
 * The NPC path diverges only in that a near-perfect aggregate (&gt;={@value #NPC_MASTERWORK_MIN})
 * rolls {@link QualityTier#MASTERWORK} (player hand-craft stays capped at FINE);
 * {@link #simulateNpcTier} rolls {@code samples} step scores from an XP-driven gaussian - a novice
 * averages ~40 with wild variance, a veteran ~92 with a tight hand, halfway to veteran after
 * {@value #NPC_XP_HALF} crafts - so every crafter profession levels on one curve. {@link #skillTierKey}
 * and {@link #skillProgress} drive the Job tab's skill line/XP bar off the same XP=~completed-crafts
 * bands (novice/apprentice/journeyman/veteran/master at 10/30/80/200).
 */
public final class QualityMath {
    private QualityMath() {
    }

    public static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    public static int aggregate(int[] stepScores) {
        if (stepScores == null || stepScores.length == 0) return 0;
        long sum = 0;
        for (int s : stepScores) sum += clampScore(s);
        return (int) Math.round((double) sum / stepScores.length);
    }

    public static QualityTier tierFromScores(int[] stepScores) {
        return QualityTier.fromScore(aggregate(stepScores));
    }

    public static QualityTier npcTierFromScore(int score) {
        return score >= NPC_MASTERWORK_MIN ? QualityTier.MASTERWORK : QualityTier.fromScore(score);
    }

    public static final int NPC_MASTERWORK_MIN = 93;

    public static QualityTier simulateNpcTier(net.minecraft.util.RandomSource rng, float xp, int samples) {
        float skill = xp / (xp + NPC_XP_HALF);      // 0 -> 1 as crafts accumulate
        double mean = 40.0 + 55.0 * skill;
        double sd = 30.0 - 22.0 * skill;
        int[] scores = new int[Math.max(1, samples)];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = clampScore((int) Math.round(mean + rng.nextGaussian() * sd));
        }
        return npcTierFromScore(aggregate(scores));
    }

    public static final float NPC_XP_HALF = 30.0F;

    public static String skillTierKey(int xp) {
        if (xp < 10) return "novice";
        if (xp < 30) return "apprentice";
        if (xp < 80) return "journeyman";
        if (xp < 200) return "veteran";
        return "master";
    }

    public static float skillProgress(int xp) {
        int lo;
        int hi;
        if (xp < 10) { lo = 0; hi = 10; }
        else if (xp < 30) { lo = 10; hi = 30; }
        else if (xp < 80) { lo = 30; hi = 80; }
        else if (xp < 200) { lo = 80; hi = 200; }
        else return 1.0F;
        return (xp - lo) / (float) (hi - lo);
    }
}
