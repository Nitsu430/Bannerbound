package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.world.entity.animal.Wolf;

/**
 * Helpers for citizen-bonded pets (Domestication policy). A bonded pet is a vanilla {@link Wolf}
 * whose owner UUID points at a {@link CitizenEntity} (not a player) and which carries a
 * {@code bb_pet_settlement} UUID in its persistent data. Both are vanilla-persisted, so no custom
 * attachment registration is needed.
 *
 * <p>Because runtime-added goals don't survive a reload, {@link #ensureFollowGoal} is idempotent
 * and is called both at bond time and from {@code PetEvents} on entity-join, so the
 * {@link PetFollowCitizenGoal} is re-attached (at goal priority 6) when a bonded wolf re-enters
 * the world. {@code TAG_SETTLEMENT} ("bb_pet_settlement") is the persistent-data key that both
 * marks a wolf as bonded and holds its settlement UUID. Collar colour is left the vanilla default
 * red for v1: the collar setter is vanilla-protected and a settlement-coloured collar would need
 * an access transformer, so it is deferred as cosmetic.
 */
@ApiStatus.Internal
public final class PetBonding {
    private PetBonding() {}

    public static final String TAG_SETTLEMENT = "bb_pet_settlement";

    public static boolean isBondedPet(Wolf wolf) {
        return wolf.getPersistentData().hasUUID(TAG_SETTLEMENT);
    }

    public static void bond(Wolf wolf, CitizenEntity citizen, Settlement settlement) {
        wolf.setOwnerUUID(citizen.getUUID());
        wolf.setTame(true, true);
        wolf.setPersistenceRequired();
        wolf.getPersistentData().putUUID(TAG_SETTLEMENT, settlement.id());
        ensureFollowGoal(wolf);
    }

    public static void ensureFollowGoal(Wolf wolf) {
        boolean present = wolf.goalSelector.getAvailableGoals().stream()
            .anyMatch(g -> g.getGoal() instanceof PetFollowCitizenGoal);
        if (!present) {
            wolf.goalSelector.addGoal(6, new PetFollowCitizenGoal(wolf));
        }
    }
}
