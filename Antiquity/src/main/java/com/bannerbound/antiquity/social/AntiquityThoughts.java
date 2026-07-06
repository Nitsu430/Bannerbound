package com.bannerbound.antiquity.social;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.social.ThoughtType;
import com.bannerbound.core.social.ThoughtTypes;

import net.minecraft.resources.ResourceLocation;

/**
 * Antiquity's citizen thoughts, registered through Core's extensible {@link ThoughtType} API rather
 * than being baked into Core's {@code ThoughtKind} enum - grog and stew are Antiquity systems, so
 * their mood lives here. Call {@link #bootstrap()} once during setup to ensure the static fields
 * initialise (and thus register) before any citizen drinks or eats. ENJOYED_GROG is the brief warm
 * glow after drinking at a fermentation trough (GROG_PLAN.md Phase 4) - the social-lubricant payoff
 * for keeping grog on tap: +6 for 4-6 in-game minutes. ENJOYED_STEW is the hearty-meal payoff for
 * keeping a cooking pot on the fire: +10 for ~5-8 in-game minutes.
 */
public final class AntiquityThoughts {
    private AntiquityThoughts() {}

    public static final ThoughtType ENJOYED_GROG = ThoughtTypes.register(
        ThoughtType.builder(ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "enjoyed_grog"))
            .label("bannerboundantiquity.thought.enjoyed_grog")
            .modifier(6)
            .duration(4_800, 7_200)
            .category(com.bannerbound.core.social.HappinessCategory.FOOD)
            .build());

    public static final ThoughtType ENJOYED_STEW = ThoughtTypes.register(
        ThoughtType.builder(ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "enjoyed_stew"))
            .label("bannerboundantiquity.thought.enjoyed_stew")
            .modifier(10)
            .duration(6_000, 9_600)
            .category(com.bannerbound.core.social.HappinessCategory.FOOD)
            .build());

    public static void bootstrap() {}
}
