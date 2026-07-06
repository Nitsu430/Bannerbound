package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;

/**
 * Per-chunk override of vanilla's world-wide mobGriefing game rule. Whenever a mob is about to do
 * something the rule guards (creeper/ghast/wither explosion block damage, enderman pickup, sheep
 * grass-eating, zombie door breaking, villager farming, etc.), this listener consults
 * SettlementData for the entity's CURRENT chunk and denies the action (setCanGrief(false)) if that
 * chunk is owned by any settlement. Unclaimed chunks behave per the normal game rule -- players
 * exploring outside their territory still see vanilla creeper craters.
 *
 * Keying on the entity's current chunk at rule-check time is the right anchor: for a creeper that's
 * the moment the fuse runs out (it is standing on the block it would crater), so one that wanders
 * into a settlement and explodes there is neutered while one that explodes a block outside is not.
 * SettlementData is overworld-keyed.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class SettlementMobGriefingEvents {
    private SettlementMobGriefingEvents() {
    }

    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        Level level = entity.level();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel sl)) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        long chunkKey = new ChunkPos(entity.blockPosition()).toLong();
        if (data.getByChunk(chunkKey) != null) {
            event.setCanGrief(false);
        }
    }
}
