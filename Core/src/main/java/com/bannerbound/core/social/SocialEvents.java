package com.bannerbound.core.social;

import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

/**
 * The single chokepoint for symmetric relationship mutations and social particle bursts. The social
 * system's core invariant is that citizen A's view of B and B's view of A always carry the same
 * score; funnelling every change through here means no caller can forget to update both sides and
 * let asymmetry creep in. All three mutators are no-ops when either citizen is null or off-server,
 * and each marks SettlementData dirty so the per-citizen NBT (saved with the entity) lands on disk.
 *
 * <p>applyMutual is the gameplay path (family/lover guard rails apply via Relationships.applyDelta);
 * setMutualScore is a debug-only hard-overwrite for {@code /bannerbound set_relationship} that wipes
 * any prior FAMILY flag; linkMutualFamily installs the permanent parent<->child bond on both sides.
 * The particle helpers are server-side only: ServerLevel.sendParticles broadcasts to every viewing
 * client. spawnOutcomeParticles maps a conversation match count to a burst (0=angry villager,
 * 1=smoke, 2=firework, 3+=happy villager); spawnHearts is BabyMakingManager's lovemaking pulse.
 */
public final class SocialEvents {
    private SocialEvents() {}

    public static void applyMutual(CitizenEntity a, CitizenEntity b, int delta) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        a.getRelationships().applyDelta(b.getUUID(), delta, now);
        b.getRelationships().applyDelta(a.getUUID(), delta, now);
        if (sl.getServer() != null) {
            SettlementData.get(sl.getServer().overworld()).setDirty();
        }
    }

    public static void setMutualScore(CitizenEntity a, CitizenEntity b, int value) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        a.getRelationships().setScore(b.getUUID(), value, now);
        b.getRelationships().setScore(a.getUUID(), value, now);
        if (sl.getServer() != null) {
            SettlementData.get(sl.getServer().overworld()).setDirty();
        }
    }

    public static void linkMutualFamily(CitizenEntity a, CitizenEntity b) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        a.getRelationships().linkFamily(b.getUUID());
        b.getRelationships().linkFamily(a.getUUID());
        if (sl.getServer() != null) {
            SettlementData.get(sl.getServer().overworld()).setDirty();
        }
    }

    public static void spawnOutcomeParticles(CitizenEntity a, CitizenEntity b, int matches) {
        if (a == null || b == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        ParticleOptions type = switch (matches) {
            case 0 -> ParticleTypes.ANGRY_VILLAGER;
            case 1 -> ParticleTypes.SMOKE;
            case 2 -> ParticleTypes.FIREWORK;
            default -> ParticleTypes.HAPPY_VILLAGER;
        };
        burstAt(sl, a, type);
        burstAt(sl, b, type);
    }

    public static void spawnHearts(ServerLevel sl, CitizenEntity c) {
        burstAt(sl, c, ParticleTypes.HEART);
    }

    private static void burstAt(ServerLevel sl, CitizenEntity c, ParticleOptions type) {
        sl.sendParticles(type,
            c.getX(), c.getY() + c.getBbHeight() + 0.3, c.getZ(),
            12, 0.3, 0.3, 0.3, 0.02);
    }
}
