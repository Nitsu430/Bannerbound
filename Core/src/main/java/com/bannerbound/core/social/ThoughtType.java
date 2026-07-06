package com.bannerbound.core.social;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * A kind of citizen thought (a happiness/mood modifier) -- the extensible, registry-backed model that
 * replaces the old enum-only one. Core's built-ins are the {@link ThoughtKind} enum constants (each
 * constant is a ThoughtType); any mod defines its own with builder() + {@link ThoughtTypes#register}
 * and attaches it via {@code citizen.getThoughts().add(type, partner, now, rng)} exactly like a
 * built-in. The Builder mirrors the ThoughtKind constructor knobs (label, modifier, optional finite
 * duration, optional per-partner binding, optional escalation) and produces an immutable
 * SimpleThoughtType.
 *
 * <p>Persisted and looked up by {@link #id()} (a stable ResourceLocation), so registration order is
 * irrelevant and addon thoughts survive save/load. The network never serialises the type -- the
 * server resolves {@link #labelKey()} to a Component before sending -- so new types need no protocol
 * change. category() defaults to SOCIETY so a type declared without one still aggregates somewhere.
 */
public interface ThoughtType {
    ResourceLocation id();

    String labelKey();

    int modifier();

    boolean isInfinite();

    boolean isPerPartner();

    default HappinessCategory category() { return HappinessCategory.SOCIETY; }

    boolean escalates();

    int modifierAt(long ageTicks);

    int rollDurationTicks(RandomSource rng);

    static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    final class Builder {
        private final ResourceLocation id;
        private String labelKey = "";
        private int modifier;
        private int minDuration = ThoughtKind.INFINITE_DURATION;
        private int maxDuration = ThoughtKind.INFINITE_DURATION;
        private boolean perPartner;
        private int escalationFloor;
        private boolean escalationSet;
        private int escalationRamp;
        private HappinessCategory category = HappinessCategory.SOCIETY;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public Builder label(String key) { this.labelKey = key; return this; }
        public Builder modifier(int m) { this.modifier = m; return this; }
        public Builder category(HappinessCategory c) { this.category = c; return this; }

        public Builder duration(int minTicks, int maxTicks) {
            this.minDuration = minTicks;
            this.maxDuration = maxTicks;
            return this;
        }

        public Builder perPartner() { this.perPartner = true; return this; }

        public Builder escalating(int floor, int rampTicks) {
            this.escalationFloor = floor;
            this.escalationSet = true;
            this.escalationRamp = rampTicks;
            return this;
        }

        public ThoughtType build() {
            int floor = escalationSet ? escalationFloor : modifier;
            return new SimpleThoughtType(id, labelKey, modifier, minDuration, maxDuration,
                perPartner, floor, escalationRamp, category);
        }
    }

    record SimpleThoughtType(ResourceLocation id, String labelKey, int modifier, int minDurationTicks,
                             int maxDurationTicks, boolean isPerPartner, int escalationFloor,
                             int escalationRampTicks, HappinessCategory category) implements ThoughtType {
        @Override
        public boolean isInfinite() {
            return minDurationTicks == ThoughtKind.INFINITE_DURATION;
        }

        @Override
        public boolean escalates() {
            return escalationRampTicks > 0 && escalationFloor != modifier;
        }

        @Override
        public int modifierAt(long ageTicks) {
            if (!escalates() || ageTicks <= 0) return modifier;
            if (ageTicks >= escalationRampTicks) return escalationFloor;
            double t = ageTicks / (double) escalationRampTicks;
            return (int) Math.round(modifier + t * (escalationFloor - modifier));
        }

        @Override
        public int rollDurationTicks(RandomSource rng) {
            if (isInfinite()) return ThoughtKind.INFINITE_DURATION;
            if (maxDurationTicks <= minDurationTicks) return minDurationTicks;
            return minDurationTicks + rng.nextInt(maxDurationTicks - minDurationTicks + 1);
        }
    }
}
