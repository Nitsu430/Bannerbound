package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the server's immigration state for the player's settlement. The Town Hall
 * screen and any HUDs read from here; the server keeps it fresh via {@code PopulationStatePayload}
 * (each second) and {@code OpenTownHallScreenPayload} (which sets Chief state). {@code populationMax}
 * is the birth/immigration cap = 7 + true spare beds. {@code governmentOrdinal} is
 * {@link Settlement.Government}'s ordinal, defaulting to 0 (NONE = anarchy) so a fresh join reads as
 * Hearth until the first payload. Title/stage helpers mirror {@link Settlement#titleKey()} /
 * {@link Settlement#isTribe()} exactly (Tribe once a government is enacted OR pop >= 8) so the
 * client shows the right title between broadcasts and greys out tribe-gated research. {@code members}
 * is this settlement's player UUIDs, letting the client tell who shares the viewer's settlement
 * (e.g. who laced a food).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientPopulationState {
    private static volatile String settlementId = "";
    private static volatile int population = 0;
    private static volatile int populationMax = Settlement.IMMIGRATION_CAP;
    private static volatile double foodPerSecond = 0.0;
    private static volatile double culturePerSecond = 0.0;
    /** Portion of {@code culturePerSecond} that comes from claimed-territory block appeal
     *  (signed — attractive chunks add, ugly ones subtract). Drives the culture-bar tooltip. */
    private static volatile double appealCulturePerSecond = 0.0;
    private static volatile double foodStored = 0.0;
    private static volatile double cultureStored = 0.0;
    private static volatile double storedFoodValue = 0.0;
    private static volatile double storedFoodPerSecond = 0.0;
    private static volatile double foodConsumptionPerSecond = 0.0;
    private static volatile java.util.Map<String, Double> foodSourceRates = java.util.Map.of();
    private static volatile double nextFoodCost = 0.0;
    private static volatile double nextCultureCost = 0.0;
    private static volatile double foodCap = 0.0;
    private static volatile double cultureCap = 0.0;
    private static volatile int governmentOrdinal = 0;
    private static volatile boolean playerIsChief = false;

    private static volatile java.util.Set<java.util.UUID> members = java.util.Set.of();

    private ClientPopulationState() {
    }

    public static void update(String id, int pop, int popMax, double fps, double cps,
                              double fStored, double cStored, double storageFoodValue, double storageFoodPerSecond,
                              double fCost, double cCost,
                              double fCap, double cCap, int govOrdinal,
                              java.util.List<java.util.UUID> memberList,
                              double foodConsumption, java.util.Map<String, Double> sourceRates,
                              double appealCps) {
        members = memberList == null ? java.util.Set.of() : java.util.Set.copyOf(memberList);
        settlementId = id;
        population = pop;
        populationMax = popMax;
        foodPerSecond = fps;
        culturePerSecond = cps;
        appealCulturePerSecond = appealCps;
        foodStored = fStored;
        cultureStored = cStored;
        storedFoodValue = storageFoodValue;
        storedFoodPerSecond = storageFoodPerSecond;
        nextFoodCost = fCost;
        nextCultureCost = cCost;
        foodCap = fCap;
        cultureCap = cCap;
        governmentOrdinal = govOrdinal;
        foodConsumptionPerSecond = foodConsumption;
        foodSourceRates = sourceRates == null ? java.util.Map.of() : java.util.Map.copyOf(sourceRates);
    }

    public static boolean isMember(java.util.UUID playerId) {
        return playerId != null && members.contains(playerId);
    }

    public static String getSettlementId() { return settlementId; }
    public static int getPopulation() { return population; }
    public static int getPopulationMax() { return populationMax; }
    public static double getFoodPerSecond() { return foodPerSecond; }
    public static double getCulturePerSecond() { return culturePerSecond; }
    /** Signed culture/sec from claimed-territory block appeal (already part of the culture rate). */
    public static double getAppealCulturePerSecond() { return appealCulturePerSecond; }
    public static double getFoodStored() { return foodStored; }
    public static double getCultureStored() { return cultureStored; }
    public static double getStoredFoodValue() { return storedFoodValue; }
    public static double getStoredFoodPerSecond() { return storedFoodPerSecond; }
    public static double getFoodConsumptionPerSecond() { return foodConsumptionPerSecond; }
    public static java.util.Map<String, Double> getFoodSourceRates() { return foodSourceRates; }
    public static double getNextFoodCost() { return nextFoodCost; }
    public static double getNextCultureCost() { return nextCultureCost; }
    public static double getFoodCap() { return foodCap; }
    public static double getCultureCap() { return cultureCap; }
    public static int getGovernmentOrdinal() { return governmentOrdinal; }
    public static boolean isPlayerChief() { return playerIsChief; }

    public static void setChiefState(int govOrdinal, boolean isChief) {
        governmentOrdinal = govOrdinal;
        playerIsChief = isChief;
    }

    public static String getTitleKey() {
        if (population >= com.bannerbound.core.api.settlement.SettlementStage.VILLAGE_THRESHOLD) {
            return "bannerbound.settlement.title.village";
        }
        return isTribe()
            ? "bannerbound.settlement.title.tribe"
            : "bannerbound.settlement.title.hearth";
    }

    public static boolean isTribe() {
        return governmentOrdinal != 0 || population >= 8;
    }
}
