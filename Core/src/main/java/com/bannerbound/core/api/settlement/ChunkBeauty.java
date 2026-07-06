package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.ApiStatus;

/**
 * The nine beauty tiers a chunk can fall into, derived from its collective block-appeal score.
 * Each tier owns an inclusive score band, a culture-per-second contribution applied while the
 * chunk is claimed, and a translation key. Score bands (the design spec's +2 overlap is resolved
 * as bland 0..1 / pleasant 2..5): &lt;=-15 atrocious, -14..-8 repulsive, -7..-4 disgusting,
 * -3..-1 unappealing, 0..1 bland, 2..5 pleasant, 6..9 attractive, 10..14 stunning, &gt;=15
 * breathtaking. fromScore rounds to the nearest int before banding, so a chunk at 1.6 reads as
 * pleasant, not bland; the bands cover the whole int range.
 *
 * <p>tierIndex() maps the tier onto a -4..+4 scale (ATROCIOUS = -4, BLAND = 0, BREATHTAKING =
 * +4) - via the adjacency layer each chunk lends this many score points to each neighbour. The
 * enum is deliberately free of client-only imports so it can be used on both the server (culture
 * math) and the client (expand-territory tooltip, reached via byNetworkId from a packet).
 */
@ApiStatus.Internal
public enum ChunkBeauty {
    ATROCIOUS(Integer.MIN_VALUE, -15, -1.00, "bannerbound.beauty.atrocious"),
    REPULSIVE(-14, -8, -0.50, "bannerbound.beauty.repulsive"),
    DISGUSTING(-7, -4, -0.25, "bannerbound.beauty.disgusting"),
    UNAPPEALING(-3, -1, -0.10, "bannerbound.beauty.unappealing"),
    BLAND(0, 1, 0.00, "bannerbound.beauty.bland"),
    PLEASANT(2, 5, 0.10, "bannerbound.beauty.pleasant"),
    ATTRACTIVE(6, 9, 0.25, "bannerbound.beauty.attractive"),
    STUNNING(10, 14, 0.50, "bannerbound.beauty.stunning"),
    BREATHTAKING(15, Integer.MAX_VALUE, 1.00, "bannerbound.beauty.breathtaking");

    private final int minScore;
    private final int maxScore;
    private final double culturePerSecond;
    private final String langKey;

    ChunkBeauty(int minScore, int maxScore, double culturePerSecond, String langKey) {
        this.minScore = minScore;
        this.maxScore = maxScore;
        this.culturePerSecond = culturePerSecond;
        this.langKey = langKey;
    }

    public static ChunkBeauty fromScore(double score) {
        int s = (int) Math.round(score);
        for (ChunkBeauty b : values()) {
            if (s >= b.minScore && s <= b.maxScore) return b;
        }
        return BLAND;
    }

    public double culturePerSecond() { return culturePerSecond; }

    public int tierIndex() { return ordinal() - 4; }

    public String langKey() { return langKey; }

    public byte networkId() { return (byte) ordinal(); }

    public static ChunkBeauty byNetworkId(int id) {
        ChunkBeauty[] v = values();
        return (id >= 0 && id < v.length) ? v[id] : BLAND;
    }
}
