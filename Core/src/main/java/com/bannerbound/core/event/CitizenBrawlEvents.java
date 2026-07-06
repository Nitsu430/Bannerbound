package com.bannerbound.core.event;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Citizen-on-citizen brawl loop. When citizen A hits citizen B (typically via
 * {@code AnarchyWorkGoal}'s occasional punch, or a player's aggression - citizens defend against
 * players too, with the same rolls), this listener rolls B's retaliation and, on a pass, schedules
 * B's swing-back via {@link CitizenEntity#schedulePendingRetaliation} with a small delay so the
 * brawl reads as alternating swings rather than a same-tick mash. The scheduled swing fires in
 * {@link CitizenEntity#aiStep}, which re-triggers this listener on the new victim, which (maybe)
 * schedules the next return swing - and so on until one side fails a roll or somebody dies.
 *
 * <p>Rolls (per user spec "75% that the recipient will hit back, then loop until one rolls 50% out
 * of hitting or one dies"): the FIRST retaliation (no active brawl record with the attacker) passes
 * at {@link #FIRST_RETALIATE_CHANCE}; a SUBSEQUENT one (attacker already the brawl opponent inside
 * {@link CitizenEntity#BRAWL_ONGOING_WINDOW_TICKS}) passes at {@link #ONGOING_CONTINUE_CHANCE}, so
 * brawls naturally peter out. Retaliation is suppressed past {@link #RETALIATE_RANGE_SQ} (the
 * attacker walked off - anarchy punches are quick walk-ups) and when a swing is already pending
 * (don't stack the chain). noteBrawlExchange runs AFTER the swing lands (in
 * {@code BrawlRetaliationGoal.tick}) so the brawl window reflects the actual exchange, not intent.
 *
 * <p>A guard struck by an OUTSIDER escalates to real combat (GuardCombatEvents), not a one-punch
 * brawl swing; same-settlement scuffles still brawl like anyone else's. Creative players trigger
 * retaliation (so the chain is testable); spectators self-filter by dealing no damage; self-hits
 * are dropped.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CitizenBrawlEvents {
    private static final double RETALIATE_RANGE_SQ = 16.0;
    private static final float FIRST_RETALIATE_CHANCE = 0.75f;
    private static final float ONGOING_CONTINUE_CHANCE = 0.50f;

    private CitizenBrawlEvents() {
    }

    @SubscribeEvent
    public static void onCitizenHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity victim)) return;
        if (!(victim.level() instanceof ServerLevel sl)) return;
        Entity raw = event.getSource().getEntity();
        if (!(raw instanceof LivingEntity attackerLiving)) return;
        if (attackerLiving == victim || !attackerLiving.isAlive()) return;
        if (!(attackerLiving instanceof CitizenEntity) && !(attackerLiving instanceof Player)) {
            return;
        }
        // Guard hit by an outsider escalates to real combat (GuardCombatEvents), not a brawl swing.
        if (victim.isGuard() && GuardCombatEvents.isRetaliationWorthy(victim, attackerLiving)) {
            return;
        }
        if (victim.distanceToSqr(attackerLiving) > RETALIATE_RANGE_SQ) return;
        if (victim.getPendingRetaliationTargetId() != null) return;

        long now = sl.getGameTime();
        UUID attackerId = attackerLiving.getUUID();
        boolean ongoing = attackerId.equals(victim.getLastBrawlOpponentId())
            && (now - victim.getLastBrawlTick()) < CitizenEntity.BRAWL_ONGOING_WINDOW_TICKS;
        float chance = ongoing ? ONGOING_CONTINUE_CHANCE : FIRST_RETALIATE_CHANCE;
        if (victim.getRandom().nextFloat() >= chance) return;

        victim.schedulePendingRetaliation(attackerId,
            now + CitizenEntity.BRAWL_RETALIATION_DELAY_TICKS);
    }
}
