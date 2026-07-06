package com.bannerbound.antiquity.entity;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * One spear embedded in a mob - the arrow-style replacement for a follow-entity. A mob carries a
 * {@code List<StuckSpear>} as a NeoForge data attachment ({@code BannerboundAntiquity.STUCK_SPEARS})
 * that is serialized to NBT (server) and natively synced to clients, so a render layer can draw the
 * stuck spears with no separate entity, no per-tick following, and no relog loss.
 *
 * <p>{@code local{X,Y,Z}} is the hit point in the renderer's MODEL-SPACE frame (blocks; Y is down,
 * X is flipped, origin lifted to the model pivot, body yaw already applied - see
 * {@code SpearProjectile.addStuckSpear} for the capture math). {@code yaw}/{@code pitch} are the
 * spear's flight angles at impact (degrees), stored body-relative so the spear keeps pointing the
 * struck direction as the mob turns.
 *
 * <p>{@code recoverable} distinguishes a player's spear (pull-out + death-drop return the item)
 * from an NPC hunter's throwaway copy (pure visual - never extractable, or the citizen's reusable
 * tool would be duplicated). Non-recoverable spears carry an {@code expireGameTime} and vanish off
 * the mob after a while, like a landed arrow despawning ({@code -1} = never, the player case).
 * The synced list is capped on the wire at 8 spears; {@code STREAM_CODEC} is hand-written because
 * eight fields outstrips {@code StreamCodec.composite}'s arities.
 */
public record StuckSpear(ItemStack stack, float localX, float localY, float localZ,
                         float yaw, float pitch, boolean recoverable, long expireGameTime) {

    public boolean isExpired(long gameTime) {
        return expireGameTime >= 0L && gameTime > expireGameTime;
    }

    // "rec"/"exp" stay optional with player-spear defaults so pre-NPC saves keep loading.
    public static final Codec<StuckSpear> CODEC = RecordCodecBuilder.create(i -> i.group(
        ItemStack.OPTIONAL_CODEC.fieldOf("stack").forGetter(StuckSpear::stack),
        Codec.FLOAT.fieldOf("lx").forGetter(StuckSpear::localX),
        Codec.FLOAT.fieldOf("ly").forGetter(StuckSpear::localY),
        Codec.FLOAT.fieldOf("lz").forGetter(StuckSpear::localZ),
        Codec.FLOAT.fieldOf("yaw").forGetter(StuckSpear::yaw),
        Codec.FLOAT.fieldOf("pitch").forGetter(StuckSpear::pitch),
        Codec.BOOL.optionalFieldOf("rec", true).forGetter(StuckSpear::recoverable),
        Codec.LONG.optionalFieldOf("exp", -1L).forGetter(StuckSpear::expireGameTime)
    ).apply(i, StuckSpear::new));
    public static final Codec<List<StuckSpear>> LIST_CODEC = CODEC.listOf();

    public static final StreamCodec<RegistryFriendlyByteBuf, StuckSpear> STREAM_CODEC = StreamCodec.of(
        (buf, s) -> {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, s.stack());
            buf.writeFloat(s.localX());
            buf.writeFloat(s.localY());
            buf.writeFloat(s.localZ());
            buf.writeFloat(s.yaw());
            buf.writeFloat(s.pitch());
            buf.writeBoolean(s.recoverable());
            buf.writeVarLong(s.expireGameTime());
        },
        buf -> new StuckSpear(
            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
            buf.readFloat(),
            buf.readFloat(),
            buf.readFloat(),
            buf.readFloat(),
            buf.readFloat(),
            buf.readBoolean(),
            buf.readVarLong()));
    public static final StreamCodec<RegistryFriendlyByteBuf, List<StuckSpear>> LIST_STREAM_CODEC =
        STREAM_CODEC.apply(ByteBufCodecs.list(8));
}
