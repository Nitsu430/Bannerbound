package com.bannerbound.antiquity.deco;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.DecoChunkSyncPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side face-decoration event handlers. Pushes a chunk's full decoration list to a player when
 * they start tracking it (per-edit deltas go out from {@link FaceDecorations#set}). On explosions,
 * plaster shields the wall: a block whose blast-facing face is plastered is spared from the affected
 * list, but that face's plaster coat shatters (is stripped); trim is purely decorative and gives no
 * protection; blocks still slated for destruction have their decorations cleared with item drops.
 * A player breaking a decorated block likewise drops its plaster/dyes, unless in creative.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
public final class DecoServerEvents {
    private DecoServerEvents() {}

    @SubscribeEvent
    static void onChunkSent(ChunkWatchEvent.Sent event) {
        ChunkDecorations cd = FaceDecorations.of(event.getChunk());
        if (cd.isEmpty()) {
            return;
        }
        ChunkPos pos = event.getPos();
        PacketDistributor.sendToPlayer(event.getPlayer(),
            new DecoChunkSyncPayload(pos.x, pos.z, cd.toEntries()));
    }

    @SubscribeEvent
    static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = event.getExplosion().center();
        List<BlockPos> affected = event.getAffectedBlocks();
        List<BlockPos> spared = new ArrayList<>();
        for (BlockPos pos : affected) {
            Direction toBlast = Direction.getNearest(
                center.x - (pos.getX() + 0.5),
                center.y - (pos.getY() + 0.5),
                center.z - (pos.getZ() + 0.5));
            FaceDeco d = FaceDecorations.get(level, pos, toBlast);
            if (d.plaster()) {
                FaceDecorations.set(level, pos, toBlast, d.withPlaster(false));
                spared.add(pos);
            }
        }
        affected.removeAll(spared);
        for (BlockPos pos : affected) {
            FaceDecorations.onBlockRemoved(level, pos, true);
        }
    }

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            boolean drop = event.getPlayer() == null || !event.getPlayer().getAbilities().instabuild;
            FaceDecorations.onBlockRemoved(level, event.getPos(), drop);
        }
    }
}
