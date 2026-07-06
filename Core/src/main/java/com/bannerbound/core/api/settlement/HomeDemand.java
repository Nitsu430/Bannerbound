package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Home demands - comforts a home wants BEYOND beds to be liveable. Beds make a home functional;
 * demands make it happy. Each demand is research-gated (a {@code bannerbound.home_demand:<suffix>}
 * flag via {@link #flag()}), so the set escalates as the civ advances - a fresh Antiquity hut is
 * asked for nothing but beds. Two kinds: FIXTURES are a block in the marked region (LIGHTING = a
 * light source at {@code >= LIGHT_MIN}, STORAGE = a {@code #bannerbound:workshop_storage}
 * container, CAMPFIRE = a lit hearth in {@code HEARTH_TAG}); LUXURIES are an item the home keeps
 * in its OWN storage container (COOKED_FOOD, CHARCOAL, matched by
 * {@code #bannerbound:home_luxury/<suffix>} tags) - a luxury inherently implies the STORAGE
 * fixture. Demands are SOFT: an unmet demand never invalidates a home, it only lowers
 * {@link #computeHappiness happiness}, which drives the resident mood thought
 * ({@link #moodThoughtFor}) and the nightly reproduction chance ({@link #reproductionBonus}).
 *
 * <p>{@link #evaluate} scans the marked region once (light / storage / lit hearth, gather
 * containers) then tests each active luxury against those containers, returning one
 * {@link DemandState} per active demand in enum order. Luxuries are an ongoing sink, not a
 * one-time stock: {@link #consumeDaily} (once per in-game day, from {@code HomeUpkeep}) drains
 * {@code residents x PER_RESIDENT_DAILY} of each from the home's own pantry, so a stocked pantry
 * empties over days, the demand lapses, and the stocker (or later a market) refills it.
 *
 * <p>Happiness math ({@link #computeHappiness}): a plain BLAND home with nothing demanded sits at
 * the neutral midpoint 50 (== 0 reproduction bonus, matching the old appeal-only behaviour);
 * appeal shifts it +/-24 across the beauty tiers ({@code APPEAL_WEIGHT}), met demands add
 * {@code MET_BONUS}, unmet subtract the larger {@code UNMET_PENALTY} (an unmet expectation stings
 * more than a met one pleases), and a roomy home earns a small spaciousness bonus. Crowding is a
 * HARD CEILING, not a subtraction: below 10 blocks/bed {@link #crowdingCeiling} falls steeply so
 * a packed dormitory is forced into the UNCOMFORTABLE/HATE band regardless of comforts - crowding
 * overrides comforts while staying soft (never invalidates). {@code spacePerBed} of
 * {@link #NO_CROWDING} means no measurable crowding (bedless/broken) and is uncapped.
 */
public enum HomeDemand {
    LIGHTING("lighting", Kind.FIXTURE, null),
    STORAGE("storage", Kind.FIXTURE, null),
    CAMPFIRE("campfire", Kind.FIXTURE, null),
    COOKED_FOOD("cooked_food", Kind.LUXURY, luxuryTag("cooked_food")),
    CHARCOAL("charcoal", Kind.LUXURY, luxuryTag("charcoal"));

    public enum Kind { FIXTURE, LUXURY }

    private final String suffix;
    private final Kind kind;
    private final TagKey<Item> luxuryTag;

    HomeDemand(String suffix, Kind kind, TagKey<Item> luxuryTag) {
        this.suffix = suffix;
        this.kind = kind;
        this.luxuryTag = luxuryTag;
    }

    public String suffix() { return suffix; }
    public Kind kind() { return kind; }
    public boolean isLuxury() { return kind == Kind.LUXURY; }
    public TagKey<Item> luxuryTag() { return luxuryTag; }
    public String flag() { return FLAG_PREFIX + suffix; }

    private static final String FLAG_PREFIX = "bannerbound.home_demand:";
    // LIGHTING threshold: torch=14, lantern/campfire/glowstone=15, soul torch=10; weak candles/redstone fall short.
    public static final int LIGHT_MIN = 8;
    public static final TagKey<net.minecraft.world.level.block.Block> HEARTH_TAG = TagKey.create(
        Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("bannerbound", "home_hearth"));

    private static TagKey<Item> luxuryTag(String suffix) {
        return TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("bannerbound", "home_luxury/" + suffix));
    }

    public static List<HomeDemand> activeDemands(Settlement settlement) {
        List<HomeDemand> out = new ArrayList<>();
        for (HomeDemand d : values()) {
            if (ResearchManager.hasFlag(settlement, d.flag())) out.add(d);
        }
        return out;
    }

    public record DemandState(HomeDemand demand, boolean met) {}

    public static List<DemandState> evaluate(ServerLevel sl, Settlement settlement, Set<BlockPos> marked) {
        List<HomeDemand> active = activeDemands(settlement);
        if (active.isEmpty()) return List.of();

        boolean hasLight = false, hasStorage = false, hasHearth = false;
        List<Container> containers = new ArrayList<>();
        for (BlockPos p : marked) {
            BlockState s = sl.getBlockState(p);
            if (!hasLight && s.getLightEmission(sl, p) >= LIGHT_MIN) hasLight = true;
            if (s.is(Workshops.STORAGE_TAG)) {
                hasStorage = true;
                BlockEntity be = sl.getBlockEntity(p);
                if (be instanceof Container c) containers.add(c);
            }
            if (!hasHearth && s.is(HEARTH_TAG)
                    && s.hasProperty(CampfireBlock.LIT) && s.getValue(CampfireBlock.LIT)) {
                hasHearth = true;
            }
        }

        List<DemandState> out = new ArrayList<>(active.size());
        for (HomeDemand d : active) {
            boolean met = switch (d) {
                case LIGHTING -> hasLight;
                case STORAGE -> hasStorage;
                case CAMPFIRE -> hasHearth;
                default -> d.isLuxury() && containersHold(containers, d.luxuryTag);
            };
            out.add(new DemandState(d, met));
        }
        return out;
    }

    private static boolean containersHold(List<Container> containers, TagKey<Item> tag) {
        for (Container c : containers) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack stack = c.getItem(i);
                if (!stack.isEmpty() && stack.is(tag)) return true;
            }
        }
        return false;
    }

    private static final int PER_RESIDENT_DAILY = 1;

    public static void consumeDaily(ServerLevel sl, Settlement settlement, Home home) {
        int residents = home.residents().size();
        if (residents <= 0) return;
        List<HomeDemand> active = activeDemands(settlement);
        boolean anyLuxury = false;
        for (HomeDemand d : active) if (d.isLuxury()) { anyLuxury = true; break; }
        if (!anyLuxury) return;
        List<BlockPos> containers = Homes.deliverableContainers(sl, home);
        if (containers.isEmpty()) return;
        for (HomeDemand d : active) {
            if (!d.isLuxury()) continue;
            consumeFromContainers(sl, containers, d.luxuryTag, residents * PER_RESIDENT_DAILY);
        }
    }

    private static void consumeFromContainers(ServerLevel sl, List<BlockPos> containers,
                                              TagKey<Item> tag, int amount) {
        int remaining = amount;
        for (BlockPos pos : containers) {
            if (remaining <= 0) break;
            Container c = com.bannerbound.core.entity.DropOffContainers.resolveDropOff(sl, pos);
            if (c == null) continue;
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty() || !st.is(tag)) continue;
                ItemStack removed = c.removeItem(i, remaining);
                remaining -= removed.getCount();
            }
        }
    }

    private static final double MET_BONUS = 6.0;
    private static final double UNMET_PENALTY = 8.0;
    private static final double APPEAL_WEIGHT = 6.0;

    public static final int NO_CROWDING = Integer.MAX_VALUE;

    public static double computeHappiness(ChunkBeauty beauty, int met, int unmet, int spacePerBed) {
        int tier = beauty != null ? beauty.tierIndex() : 0;
        double base = 50 + tier * APPEAL_WEIGHT;
        double demandTerm = met * MET_BONUS - unmet * UNMET_PENALTY;
        double happiness = base + demandTerm + spaciousnessBonus(spacePerBed);
        return Math.max(0.0, Math.min(crowdingCeiling(spacePerBed), Math.min(100.0, happiness)));
    }

    private static double spaciousnessBonus(int spacePerBed) {
        if (spacePerBed >= 20) return 4.0;
        if (spacePerBed >= 14) return 2.0;
        return 0.0;
    }

    private static double crowdingCeiling(int spacePerBed) {
        if (spacePerBed >= 10) return 100.0;
        if (spacePerBed <= 4) return Math.max(8.0, 22.0 - (4 - spacePerBed) * 7.0);
        return 22.0 + (spacePerBed - 4) * 13.0;
    }

    public static double reproductionBonus(double homeHappiness) {
        double b = (homeHappiness - 50.0) / 50.0 * 0.5;
        return Math.max(-0.35, Math.min(0.5, b));
    }

    public static ThoughtKind moodThoughtFor(double homeHappiness) {
        if (homeHappiness >= 85.0) return ThoughtKind.LOVE_HOME;
        if (homeHappiness >= 60.0) return ThoughtKind.LIKE_HOME;
        if (homeHappiness >= 45.0) return null;
        if (homeHappiness >= 25.0) return ThoughtKind.UNCOMFORTABLE_HOME;
        return ThoughtKind.HATE_HOME;
    }
}
