package com.bannerbound.antiquity.poison;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * The poison currently afflicting one entity - the value of the {@code POISON_STATE} data attachment
 * ({@link com.bannerbound.antiquity.BannerboundAntiquity#POISON_STATE}). Serialized to NBT (so poison
 * survives save/reload) and natively synced to clients (so the player HUD vignette / citizen glyph can
 * read it), following the {@link com.bannerbound.antiquity.entity.StuckSpear} attachment pattern.
 *
 * <p>{@code type == null} (the {@link #NONE} sentinel) means "not poisoned" - it is the attachment
 * default, so {@code getData} never returns null; {@link #active()} guards against the sentinel and
 * malformed loads. {@code stage} is 1-based. {@code stageEndsAt} is the game-time at which this stage
 * escalates to the next - storing a deadline (rather than a per-tick countdown) means the state only
 * needs rewriting (and re-syncing) once per stage, not every tick. Both the NBT codec and the stream
 * codec store the type by string id ({@code ""} = none), so adding new poisons stays save-safe.
 */
public record PoisonState(@Nullable PoisonType type, int stage, long stageEndsAt) {

    public static final PoisonState NONE = new PoisonState(null, 0, 0L);

    public boolean active() {
        return type != null && stage > 0;
    }

    public static final Codec<PoisonState> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.optionalFieldOf("type", "").forGetter(s -> s.type == null ? "" : s.type.id()),
        Codec.INT.optionalFieldOf("stage", 0).forGetter(PoisonState::stage),
        Codec.LONG.optionalFieldOf("ends", 0L).forGetter(PoisonState::stageEndsAt)
    ).apply(i, (typeId, stage, ends) -> new PoisonState(PoisonType.fromId(typeId), stage, ends)));

    public static final StreamCodec<RegistryFriendlyByteBuf, PoisonState> STREAM_CODEC = StreamCodec.of(
        (buf, s) -> {
            buf.writeUtf(s.type == null ? "" : s.type.id());
            buf.writeVarInt(s.stage());
            buf.writeVarLong(s.stageEndsAt());
        },
        buf -> new PoisonState(PoisonType.fromId(buf.readUtf()), buf.readVarInt(), buf.readVarLong()));
}
