package com.bannerbound.core.api.quality;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * Craftsmanship quality of a tool/weapon - the canonical cross-cutting quality ladder for the whole
 * Bannerbound suite. Lives in Core so any expansion can read/write it without depending on the
 * expansion that produced the item (e.g. a future Medieval guild upgrading an Antiquity-made bow).
 * {@link #CRUDE}..{@link #FINE} are reachable by player hand-craft minigames ({@link #fromScore(int)}
 * caps at FINE - with 3 stretches only all-green or two-green-one-good reach it);
 * {@link #MASTERWORK} and above are reserved for veteran Crafter NPCs / Medieval guild upgrades and
 * are never produced by a hand-craft roll.
 *
 * <p>Quality scales item stats by {@link #statMultiplier()} - durability and effectiveness share the
 * one factor per the design (Crude -25%, Standard baseline, Fine slightly better); see
 * {@code FLETCHING_PLAN.md} Part 4. {@link #scaleWorkTicks} applies that to NPC gathering work goals
 * (miner/digger/forester/farmer) with the speed benefit AMPLIFIED so it's felt (Fine ~17% faster,
 * Crude ~33% slower, floored at 1 tick); {@link #of} treats an un-componented (creative-spawned)
 * item as STANDARD/x1.0. Persistence uses {@link #CODEC} (by serialized name); network sync uses the
 * ordinal-based {@link #STREAM_CODEC}, which only stays stable because this enum is append-only.
 */
public enum QualityTier implements StringRepresentable {
    // Append-only: STREAM_CODEC is ordinal-based, so never reorder/remove a constant (breaks sync).
    CRUDE("crude", 0.75F, ChatFormatting.RED, 0xFFFF5555),
    STANDARD("standard", 1.00F, ChatFormatting.GRAY, 0xFFAAAAAA),
    FINE("fine", 1.10F, ChatFormatting.GREEN, 0xFF55FF55),
    MASTERWORK("masterwork", 1.20F, ChatFormatting.AQUA, 0xFF55FFFF),
    PERFECT("perfect", 1.35F, ChatFormatting.LIGHT_PURPLE, 0xFFFF55FF),
    LEGENDARY("legendary", 1.50F, ChatFormatting.GOLD, 0xFFFFAA00);

    public static final Codec<QualityTier> CODEC = StringRepresentable.fromEnum(QualityTier::values);
    public static final StreamCodec<ByteBuf, QualityTier> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(i -> values()[i], QualityTier::ordinal);

    private final String name;
    private final float statMultiplier;
    private final ChatFormatting format;
    private final int color;

    QualityTier(String name, float statMultiplier, ChatFormatting format, int color) {
        this.name = name;
        this.statMultiplier = statMultiplier;
        this.format = format;
        this.color = color;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public float statMultiplier() {
        return statMultiplier;
    }

    public ChatFormatting format() {
        return format;
    }

    public int color() {
        return color;
    }

    public Component displayName() {
        return Component.translatable("bannerbound.quality." + name).withStyle(format);
    }

    public static QualityTier fromScore(int score) {
        if (score < 25) return CRUDE;
        if (score < 85) return STANDARD;
        return FINE;
    }

    public static QualityTier of(net.minecraft.world.item.ItemStack stack) {
        QualityTier tier = stack.get(com.bannerbound.core.BannerboundCore.TOOL_QUALITY.get());
        return tier == null ? STANDARD : tier;
    }

    public static int scaleWorkTicks(net.minecraft.world.item.ItemStack tool, int baseTicks) {
        float m = of(tool).statMultiplier();
        float speed = m >= 1.0F ? 1.0F + (m - 1.0F) * 2.0F : m;
        return Math.max(1, Math.round(baseTicks / speed));
    }
}
