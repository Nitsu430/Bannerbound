package com.bannerbound.core.barbarian;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.BarbarianEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

/**
 * Overworld-only, server-side projectile hook: when a projectile NOT fired by the camp lands within
 * TERRITORY_R of a camp's centre, members within ALERT_R of the impact that lack a live target rally onto
 * the shooter. Lets the player provoke a camp from range and means a hunter's stray spear into a camp
 * won't go unanswered. The camp's own volleys are ignored so members never turn on each other.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class BarbarianAlertEvents {
    private static final double TERRITORY_R = 40.0;
    private static final double ALERT_R = 28.0;

    private BarbarianAlertEvents() {
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile proj = event.getProjectile();
        if (!(proj.level() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return;
        Entity owner = proj.getOwner();
        if (owner instanceof BarbarianEntity) return;
        if (!(owner instanceof LivingEntity attacker) || !attacker.isAlive()) return;
        Vec3 hit = proj.position();
        BlockPos hitPos = BlockPos.containing(hit);
        BarbarianData data = BarbarianData.get(sl);

        BarbarianCamp camp = null;
        double best = TERRITORY_R * TERRITORY_R;
        for (BarbarianCamp c : data.all()) {
            if (c.razed) continue;
            double dsq = c.center.distSqr(hitPos);
            if (dsq <= best) {
                best = dsq;
                camp = c;
            }
        }
        if (camp == null) return;

        AABB box = new AABB(hitPos).inflate(ALERT_R);
        for (BarbarianEntity m : sl.getEntitiesOfClass(BarbarianEntity.class, box)) {
            if (!camp.id.equals(m.campId())) continue;
            LivingEntity cur = m.getTarget();
            if (cur == null || !cur.isAlive()) {
                m.setTarget(attacker);
                m.setLastHurtByMob(attacker); // kicks HurtByTargetGoal's alert-others to rally the rest
            }
        }
    }
}
