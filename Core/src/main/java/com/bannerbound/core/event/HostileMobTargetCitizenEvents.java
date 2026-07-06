package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Injects a citizen-targeting goal into every hostile mob that joins the level
 * (EntityJoinLevelEvent). Without this, hostile mobs would treat citizens like villagers and
 * mostly ignore them (vanilla mob target selectors are AbstractVillager-specific, not
 * LivingEntity-broad); with it, the same mobs that target the player also target citizens.
 *
 * The whitelist is shared with CitizenEntity.isHostileToCitizens (Zombies, Skeletons, Spiders,
 * Creepers, Witches, Illagers, Vex -- Enderman excluded). The reciprocal target on the citizen
 * side lives in CitizenEntity.registerGoals; together they form a symmetric combat relationship
 * that fires whenever the two are in range of each other.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class HostileMobTargetCitizenEvents {
    private HostileMobTargetCitizenEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!CitizenEntity.isHostileToCitizens(mob)) return;
        // Priority 3: vanilla's "target nearest villager" tier, so citizens don't outrank the player.
        mob.targetSelector.addGoal(3,
            new NearestAttackableTargetGoal<>(mob, CitizenEntity.class, true));
    }
}
