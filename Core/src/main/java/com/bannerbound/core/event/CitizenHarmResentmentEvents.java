package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Resentment-from-harm wiring: two server-side hooks that translate player violence against
 * citizens into the resentment values that feed the compliance/mood system.
 *
 * A player hitting a citizen adds RESENTMENT_PER_HIT to that citizen's resentment toward the
 * attacker; a player killing a citizen adds RESENTMENT_PER_WITNESS_KILL to every OTHER loaded
 * settlement citizen (the dead one is moot). Leader status is irrelevant - a Council member
 * accrues resentment as fast as a wanderer. Only direct player -> citizen damage counts;
 * fall/mob damage is "the world hurt them", not a social act, so it is ignored.
 *
 * Constants are tuned against the compliance-drop threshold (20): a few hits cross it but a
 * single bump does not, while one kill reliably pushes witnesses past it (a death is the kind
 * of event a tribe remembers). Kept separate from {@link CitizenLifecycleEvents} so death-roster
 * cleanup and the resentment broadcast stay independently testable.
 *
 * Gotcha: Settlement's Citizen roster is a snapshot of UUID/name records, not live entities.
 * The resentment map lives on CitizenEntity, so the kill hook iterates loaded entities via
 * SettlementManager.allCitizensOf rather than the roster.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CitizenHarmResentmentEvents {
    public static final int RESENTMENT_PER_HIT = 10;
    public static final int RESENTMENT_PER_WITNESS_KILL = 40;

    private CitizenHarmResentmentEvents() {
    }

    @SubscribeEvent
    public static void onCitizenHit(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity citizen)) return;
        Entity attackerEnt = event.getSource() == null ? null : event.getSource().getEntity();
        if (!(attackerEnt instanceof Player attacker)) return;
        citizen.addResentment(attacker.getUUID(), RESENTMENT_PER_HIT);
    }

    @SubscribeEvent
    public static void onCitizenKilled(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity killed)) return;
        if (!(killed.level() instanceof ServerLevel level)) return;
        Entity killerEnt = event.getSource() == null ? null : event.getSource().getEntity();
        if (!(killerEnt instanceof Player killer)) return;
        if (level.getServer() == null) return;
        if (killed.getSettlementId() == null) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement settlement = data.getById(killed.getSettlementId());
        if (settlement == null) return;
        for (CitizenEntity other
                : com.bannerbound.core.api.settlement.SettlementManager.allCitizensOf(level, settlement)) {
            if (other == killed) continue;
            other.addResentment(killer.getUUID(), RESENTMENT_PER_WITNESS_KILL);
        }
    }
}
