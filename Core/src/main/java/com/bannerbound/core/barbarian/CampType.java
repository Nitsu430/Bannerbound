package com.bannerbound.core.barbarian;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.DyeColor;

/**
 * The four barbarian-camp flavours, resolved once from the camp-center biome at seed time (see
 * the Antiquity biome->type table) and then stored on the {@link BarbarianCamp} record. Each type
 * fixes a default relation, a persuasion ceiling, and a banner/name-tag colour:
 * <ul>
 *   <li>NOMAD - hot/desert; starts NEUTRAL, scouts come to trade, ceiling FRIENDLY; yellow.</li>
 *   <li>TRIBE - jungle/swamp; starts HOSTILE, demands antidotes/poisons/food, ceiling NEUTRAL; green.</li>
 *   <li>RAIDER - cold; starts NEUTRAL, demands meat/livestock, tougher to defeat, ceiling FRIENDLY; aqua.</li>
 *   <li>MARAUDER - temperate plains/forest; ALWAYS hostile, never persuadable (accepting demands only
 *       buys raid-cooldown); red.</li>
 * </ul>
 *
 * <p>Note the name clash: "Barbarians" is both the umbrella system and the temperate type. In code
 * the temperate always-hostile type is {@link #MARAUDER} to disambiguate; its display string is
 * still "Barbarians" (lang key {@code bannerbound.barbarian.type.marauder}, englishName "Barbarians").
 */
public enum CampType {
    NOMAD(CampRelationState.NEUTRAL, true),
    TRIBE(CampRelationState.HOSTILE, true),
    RAIDER(CampRelationState.NEUTRAL, true),
    MARAUDER(CampRelationState.HOSTILE, false);

    private final CampRelationState defaultRelation;
    private final boolean persuadableCeilingIsNeutral;

    CampType(CampRelationState defaultRelation, boolean persuadable) {
        this.defaultRelation = defaultRelation;
        this.persuadableCeilingIsNeutral = persuadable;
    }

    public CampRelationState defaultRelation() {
        return defaultRelation;
    }

    public boolean isAlwaysHostile() {
        return this == MARAUDER;
    }

    public boolean canBePersuaded() {
        return persuadableCeilingIsNeutral && this != MARAUDER;
    }

    public String displayKey() {
        return "bannerbound.barbarian.type." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public net.minecraft.network.chat.Component displayName() {
        return net.minecraft.network.chat.Component.translatable(displayKey());
    }

    public String englishName() {
        return switch (this) {
            case NOMAD -> "Nomads";
            case TRIBE -> "Tribe";
            case RAIDER -> "Raiders";
            case MARAUDER -> "Barbarians";
        };
    }

    public DyeColor bannerDye() {
        return switch (this) {
            case NOMAD -> DyeColor.YELLOW;
            case TRIBE -> DyeColor.GREEN;
            case RAIDER -> DyeColor.LIGHT_BLUE;
            case MARAUDER -> DyeColor.RED;
        };
    }

    public CampRelationState relationCeiling() {
        return switch (this) {
            case MARAUDER -> CampRelationState.HOSTILE;
            case TRIBE -> CampRelationState.NEUTRAL;
            case NOMAD, RAIDER -> CampRelationState.FRIENDLY;
        };
    }

    public ChatFormatting nameColor() {
        return switch (this) {
            case NOMAD -> ChatFormatting.YELLOW;
            case TRIBE -> ChatFormatting.GREEN;
            case RAIDER -> ChatFormatting.AQUA;
            case MARAUDER -> ChatFormatting.RED;
        };
    }

    public static CampType fromName(String name) {
        if (name == null) return null;
        for (CampType t : values()) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return null;
    }
}
