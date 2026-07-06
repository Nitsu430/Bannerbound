package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One row in the Thoughts tab, built server-side from a per-citizen Thought: the label
 * component already has any social-partner name substituted in, and modifier is the signed
 * happiness delta. Sent inside {@link OpenCitizenScreenPayload}; never persisted.
 *
 * <p>Live time-left: expireGameTime is the absolute world tick at which the thought expires
 * (-1 for infinite thoughts). The client subtracts it from level.getGameTime() every render
 * frame to compute remaining ticks, so the time bar shrinks in real time while the screen is
 * open without re-fetching the entity. totalDurationTicks is the original full duration, used
 * as the bar's denominator; both fields are -1 for infinite thoughts (no bar drawn).
 * categoryEnum() decodes the synced category ordinal, clamping out-of-range values to SOCIETY.
 */
@ApiStatus.Internal
public record ThoughtEntry(Component label, int modifier, long expireGameTime, int totalDurationTicks,
                           int category) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ThoughtEntry> STREAM_CODEC = StreamCodec.of(
        (buf, e) -> {
            ComponentSerialization.STREAM_CODEC.encode(buf, e.label());
            ByteBufCodecs.VAR_INT.encode(buf, e.modifier());
            ByteBufCodecs.VAR_LONG.encode(buf, e.expireGameTime());
            ByteBufCodecs.VAR_INT.encode(buf, e.totalDurationTicks());
            ByteBufCodecs.VAR_INT.encode(buf, e.category());
        },
        buf -> new ThoughtEntry(
            ComponentSerialization.STREAM_CODEC.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_LONG.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf)
        )
    );

    public com.bannerbound.core.social.HappinessCategory categoryEnum() {
        com.bannerbound.core.social.HappinessCategory[] v = com.bannerbound.core.social.HappinessCategory.values();
        return v[category >= 0 && category < v.length ? category : com.bannerbound.core.social.HappinessCategory.SOCIETY.ordinal()];
    }
}
