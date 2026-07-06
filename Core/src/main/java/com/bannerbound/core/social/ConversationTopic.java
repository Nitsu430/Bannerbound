package com.bannerbound.core.social;

import net.minecraft.util.RandomSource;

/**
 * One of the conversation topics citizens chat about, each mapping to an icon shown in the overhead
 * bubble. Two flavours. Static (CULTURE, FOOD, SCIENCE): the icon is fixed per era, the same glyph
 * for every citizen who picked the topic. Dynamic (HAPPINESS, JOB): the icon reflects per-citizen
 * state at display time - HAPPINESS shows a face for the citizen's mood bucket, JOB shows the
 * workstation's hotbar block icon (or none if unemployed). Two citizens can share a topic (which
 * counts as a match, even if the two dynamic icons differ visually) yet render different icons - the
 * topic is what they "talk about", the icon is what they personally have to say about it.
 * <p>
 * The DATA_BUBBLE synched-data slot carries a packed int: bubbleId in the low 8 bits, subType in the
 * high 24 (packed = bubbleId | (subType << 8), up to 0xFFFFFF subType values). bubbleId encodes the
 * rolled topic (0 = no bubble); subType carries the dynamic-icon state (happiness bucket for
 * HAPPINESS, workstation-type ordinal for JOB). Static topics pack subType = 0. Keep the bubbleId
 * integers stable - clients decode by id, not enum ordinal. During the Hearth stage citizens have no
 * organised jobs, so {@link #random(RandomSource, boolean)} excludes JOB when {@code allowWork} is
 * false (callers pass {@code settlement.isTribe()}).
 */
public enum ConversationTopic {
    CULTURE(1),
    FOOD(2),
    SCIENCE(3),
    HAPPINESS(4),
    JOB(5);

    private static final ConversationTopic[] BY_ID = new ConversationTopic[]{
        null, CULTURE, FOOD, SCIENCE, HAPPINESS, JOB
    };
    private final int bubbleId;

    ConversationTopic(int bubbleId) {
        this.bubbleId = bubbleId;
    }

    public int bubbleId() {
        return bubbleId;
    }

    public int packBubbleId(int subType) {
        return bubbleId | ((subType & 0xFFFFFF) << 8);
    }

    public static ConversationTopic fromBubbleId(int id) {
        int low = id & 0xFF;
        if (low < 1 || low >= BY_ID.length) return null;
        return BY_ID[low];
    }

    public static int subTypeFromPackedId(int packed) {
        return (packed >>> 8) & 0xFFFFFF;
    }

    public static ConversationTopic random(RandomSource rng) {
        return random(rng, true);
    }

    public static ConversationTopic random(RandomSource rng, boolean allowWork) {
        if (allowWork) {
            ConversationTopic[] values = values();
            return values[rng.nextInt(values.length)];
        }
        ConversationTopic[] nonWork = { CULTURE, FOOD, SCIENCE, HAPPINESS };
        return nonWork[rng.nextInt(nonWork.length)];
    }
}
