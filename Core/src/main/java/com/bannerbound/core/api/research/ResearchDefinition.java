package com.bannerbound.core.api.research;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One node in a research/culture/faith tree, parsed from a datapack JSON file and synced to
 * clients verbatim via STREAM_CODEC. id is the full ResourceLocation string (e.g.
 * "bannerboundantiquity:knapping") derived from the file path; cost is in points and the tree's
 * per-second rate fills the bar. minAge is the minimum settlement era required - if the civ
 * regresses below it, a completed node un-completes (see the *Manager regress paths).
 * <p>
 * Unlocks are three orthogonal lists: items (ItemStack ids the settlement now recognizes,
 * additive to starting_items), features (one-shot effects fired on completion, e.g.
 * bannerbound.advance_age:medieval or bannerbound.science_per_second_delta:0.5), and flags
 * (persistent capability gates true while completed, e.g. bannerbound.allow_animal_breeding).
 * <p>
 * Optional gates/fields: ponderScene carries a Create-style Ponder scene id that Core never
 * resolves - it just ships it to the client, which asks {@link ResearchPonderBridge} to open it
 * ("" = none). governmentType restricts render/research to a matching current government (null =
 * any) and drives government-exclusive policy nodes. faithPath is the faith-tree analog (null =
 * shared trunk, and always null for science/culture nodes). heraldryPoints ("heraldry_points",
 * default 0) accrue while completed and are spent on banner pattern layers (see FactionBanner).
 * important ("important", default false) renders an ornate frame so era-defining choices read as
 * bigger. insight is the optional learn-by-doing boost ({@link InsightDefinition}).
 * <p>
 * Wire format: governmentType and faithPath use a null-safe VarInt scheme (0 = none, else
 * ordinal+1) to avoid negative varints; encode and decode MUST stay in lockstep, and decode
 * guards against an out-of-range ordinal from a malformed packet.
 */
public record ResearchDefinition(
    String id,
    String name,
    String description,
    double cost,
    int x,
    int y,
    boolean autoUnlock,
    Era minAge,
    List<String> prerequisites,
    List<String> unlocksItems,
    List<String> unlocksFeatures,
    List<String> unlocksFlags,
    String ponderScene,
    @Nullable Settlement.Government governmentType,
    boolean requiresTribe,
    int heraldryPoints,
    boolean important,
    @Nullable com.bannerbound.core.api.faith.FaithPath faithPath,
    @Nullable InsightDefinition insight
) {
    private static final StreamCodec<ByteBuf, List<String>> STRING_LIST =
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, ResearchDefinition> STREAM_CODEC = StreamCodec.of(
        (buf, def) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, def.id());
            ByteBufCodecs.STRING_UTF8.encode(buf, def.name());
            ByteBufCodecs.STRING_UTF8.encode(buf, def.description());
            buf.writeDouble(def.cost());
            ByteBufCodecs.VAR_INT.encode(buf, def.x());
            ByteBufCodecs.VAR_INT.encode(buf, def.y());
            buf.writeBoolean(def.autoUnlock());
            ByteBufCodecs.VAR_INT.encode(buf, def.minAge().ordinal());
            STRING_LIST.encode(buf, def.prerequisites());
            STRING_LIST.encode(buf, def.unlocksItems());
            STRING_LIST.encode(buf, def.unlocksFeatures());
            STRING_LIST.encode(buf, def.unlocksFlags());
            ByteBufCodecs.STRING_UTF8.encode(buf, def.ponderScene());
            // Null-safe wire scheme: 0 = none, else ordinal+1 (decode side must match).
            ByteBufCodecs.VAR_INT.encode(buf,
                def.governmentType() == null ? 0 : def.governmentType().ordinal() + 1);
            buf.writeBoolean(def.requiresTribe());
            ByteBufCodecs.VAR_INT.encode(buf, def.heraldryPoints());
            buf.writeBoolean(def.important());
            ByteBufCodecs.VAR_INT.encode(buf,
                def.faithPath() == null ? 0 : def.faithPath().ordinal() + 1);
            buf.writeBoolean(def.insight() != null);
            if (def.insight() != null) InsightDefinition.STREAM_CODEC.encode(buf, def.insight());
        },
        buf -> new ResearchDefinition(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            buf.readDouble(),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            buf.readBoolean(),
            Era.fromOrdinalOrDefault(ByteBufCodecs.VAR_INT.decode(buf)),
            STRING_LIST.decode(buf),
            STRING_LIST.decode(buf),
            STRING_LIST.decode(buf),
            STRING_LIST.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            decodeGovernment(ByteBufCodecs.VAR_INT.decode(buf)),
            buf.readBoolean(),
            ByteBufCodecs.VAR_INT.decode(buf),
            buf.readBoolean(),
            decodeFaithPath(ByteBufCodecs.VAR_INT.decode(buf)),
            buf.readBoolean() ? InsightDefinition.STREAM_CODEC.decode(buf) : null
        )
    );

    @Nullable
    private static com.bannerbound.core.api.faith.FaithPath decodeFaithPath(int wire) {
        if (wire <= 0) return null;
        com.bannerbound.core.api.faith.FaithPath[] vals = com.bannerbound.core.api.faith.FaithPath.values();
        int idx = wire - 1;
        return idx < vals.length ? vals[idx] : null;
    }

    @Nullable
    private static Settlement.Government decodeGovernment(int wire) {
        if (wire <= 0) return null;
        Settlement.Government[] vals = Settlement.Government.values();
        int idx = wire - 1;
        return idx < vals.length ? vals[idx] : null;
    }
}
