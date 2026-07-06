package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.SpearProjectile;
import com.bannerbound.antiquity.entity.SpearedFishEntity;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Spear fishing's research gate and reel-in target resolution. The rope-tethered throw + reel-in is
 * gated behind FLAG (a per-settlement research unlock); spearing a fish still produces the floating catch
 * without it (purely cosmetic). FLAG is kept as a plain String constant here (mirroring
 * FisherCatchTable.FLAG_TREASURE_FISHING) so the Research Tree Editor auto-discovers it. Reel-in
 * (startReel) is server-side only and scans within REEL_RANGE for the player's nearest tethered catch or
 * spear; hasTether is a read-only either-side probe the client uses to decide whether a reel-click is
 * worth sending.
 */
@ApiStatus.Internal
public final class SpearFishing {
    public static final String FLAG = "bannerbound.unlock_spear_fishing";
    private static final double REEL_RANGE = 48.0;

    private SpearFishing() {}

    public static boolean unlocked(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return false;
        }
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(serverPlayer.getUUID());
            return ResearchManager.hasFlag(settlement, FLAG);
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean startReel(Player player) {
        Level level = player.level();
        if (level.isClientSide) {
            return false;
        }
        AABB area = player.getBoundingBox().inflate(REEL_RANGE);
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (SpearedFishEntity catch_ : level.getEntitiesOfClass(SpearedFishEntity.class, area,
                e -> e.isTetheredTo(player))) {
            double d = catch_.distanceToSqr(player);
            if (d < bestSq) {
                bestSq = d;
                best = catch_;
            }
        }
        for (SpearProjectile spear : level.getEntitiesOfClass(SpearProjectile.class, area,
                e -> e.isRopeTethered() && isOwner(e, player))) {
            double d = spear.distanceToSqr(player);
            if (d < bestSq) {
                bestSq = d;
                best = spear;
            }
        }
        if (best instanceof SpearedFishEntity catch_) {
            catch_.startReeling();
        } else if (best instanceof SpearProjectile spear) {
            spear.startReeling();
        } else {
            return false;
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            BannerboundAntiquity.SPEAR_REEL_SOUND.get(), SoundSource.PLAYERS,
            0.9F, 0.8F + player.getRandom().nextFloat() * 0.2F);
        return true;
    }

    public static boolean hasTether(Player player) {
        Level level = player.level();
        AABB area = player.getBoundingBox().inflate(REEL_RANGE);
        if (!level.getEntitiesOfClass(SpearedFishEntity.class, area, e -> e.isTetheredTo(player)).isEmpty()) {
            return true;
        }
        return !level.getEntitiesOfClass(SpearProjectile.class, area,
            e -> e.isRopeTethered() && isOwner(e, player)).isEmpty();
    }

    private static boolean isOwner(SpearProjectile spear, Player player) {
        Entity owner = spear.getOwner();
        return owner != null && owner.getUUID().equals(player.getUUID());
    }
}
