package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.GuardWorkGoal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Guard combat event hooks (GUARD_PLAN.md section 11, "guards counter-attack whoever damages
 * them"), plus the settlement friendly-fire rule for citizen projectiles.
 *
 * Retaliation license: any damage to a guard from a non-friendly living attacker records that
 * attacker as the guard's retaliation target (noteGuardRetaliation). GuardTargetingGoal folds it
 * into target scoring and GuardCombatGoal accepts it even outside the defense-band leash -- so a
 * guard is never a free kill for an attacker its normal hostile predicate doesn't cover (an enemy
 * player outside a rally, a raider plinking from past the border). This also justifies the guard's
 * combat-hurt panic immunity: a guard that can't flee combat pain must always be allowed to answer
 * it. isRetaliationWorthy is shared with CitizenBrawlEvents so the brawl roll and guard retaliation
 * never both claim the same hit. Friendly attackers never trigger it: a same-settlement citizen
 * (that's a brawl) or a player member of the guard's own settlement (an accidental whack from the
 * boss shouldn't start a fight to the death).
 *
 * Kill XP: guards earn guards_post XP on the KILL by any route (melee, arrow, sling rock) -- the
 * death event sees the projectile owner, which the combat goal's swing loop can't -- but only for
 * legitimate quarry (hostiles, or the licensed retaliation target) so herding livestock into a
 * guard can't farm watch XP.
 *
 * Projectile friendly-fire: an arrow or sling rock fired by a citizen that would strike a fellow
 * citizen or member player passes clean through (impact cancelled). Without this a stray guard
 * arrow into the melee line dealt real damage AND read as "citizen hit citizen", rolling a BRAWL
 * between two guards mid-raid. Barbarians/mercenaries subclass CitizenEntity but carry no (or a
 * different) settlement id, so their shots still hurt and their hits still provoke retaliation.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class GuardCombatEvents {
    private GuardCombatEvents() {
    }

    public static boolean isRetaliationWorthy(CitizenEntity guard, LivingEntity attacker) {
        if (attacker == guard || !attacker.isAlive()) return false;
        Settlement s = guard.getSettlement();
        if (attacker instanceof CitizenEntity ac) {
            // Barbarians/mercenaries subclass CitizenEntity but carry no settlement id -> fall through.
            return ac.getSettlementId() == null
                || !ac.getSettlementId().equals(guard.getSettlementId());
        }
        if (attacker instanceof Player p) {
            return s == null || !s.members().contains(p.getUUID());
        }
        return true;
    }

    @SubscribeEvent
    public static void onGuardHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity guard) || !guard.isGuard()) return;
        if (!(guard.level() instanceof ServerLevel sl)) return;
        Entity raw = event.getSource().getEntity();
        if (!(raw instanceof LivingEntity attacker)) return;
        if (!isRetaliationWorthy(guard, attacker)) return;
        guard.noteGuardRetaliation(attacker.getUUID(), sl.getGameTime());
        LivingEntity current = guard.getTarget();
        if (current == null || !current.isAlive()) {
            guard.setTarget(attacker);
        }
    }

    @SubscribeEvent
    public static void onProjectileImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit)) return;
        if (!(event.getProjectile().getOwner() instanceof CitizenEntity shooter)) return;
        java.util.UUID home = shooter.getSettlementId();
        if (home == null) return;   // barbarians / mercenaries carry no settlement id: not our rule
        Entity struck = hit.getEntity();
        boolean friendly = (struck instanceof CitizenEntity c && home.equals(c.getSettlementId()))
            || (struck instanceof Player p && shooter.getSettlement() != null
                && shooter.getSettlement().members().contains(p.getUUID()));
        if (friendly) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onGuardKill(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof CitizenEntity guard) || !guard.isGuard()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!guard.isHostileToMe(victim) && !guard.isGuardRetaliationTarget(victim)) return;
        guard.grantJobXp(GuardWorkGoal.JOB_TYPE_ID, 1.0f, "guard");
    }
}
