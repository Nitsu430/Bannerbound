package com.bannerbound.core.entity;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Helper run whenever housing logic evicts residents -- bed loss, House Block break, or a
 * validation flip to invalid. Centralised here so callers (the BE, the lifecycle event) don't each
 * reimplement every per-citizen side effect of eviction.
 *
 * <p>Per evicted (and currently loaded) citizen: clear all *_HOME thoughts
 * (NICE / LOVE / LIKE / UNCOMFORTABLE / HATE) so the screen doesn't show stale rows -- NO_HOME is
 * re-applied by the citizen's own 20-tick poll on the next cycle, so it is not added here -- then
 * recompute happiness so the synched-data slot reflects the cleared thoughts immediately, and wake
 * anyone asleep in the now-invalid home, clearing the OCCUPIED flag on the bed HEAD so the freed
 * bed can be reused. No-op for citizens that aren't loaded: they re-evaluate from chunk thoughts
 * the next time they tick.
 */
@ApiStatus.Internal
public final class HousingEvictionHook {
    private HousingEvictionHook() {}

    private static final ThoughtKind[] HOME_THOUGHTS = {
        ThoughtKind.NICE_HOME,
        ThoughtKind.LOVE_HOME,
        ThoughtKind.LIKE_HOME,
        ThoughtKind.UNCOMFORTABLE_HOME,
        ThoughtKind.HATE_HOME
    };

    public static void onEvict(ServerLevel sl, List<UUID> citizenIds) {
        for (UUID id : citizenIds) {
            Entity e = sl.getEntity(id);
            if (!(e instanceof CitizenEntity c)) continue;
            boolean changed = false;
            for (ThoughtKind k : HOME_THOUGHTS) {
                if (c.getThoughts().remove(k, null)) changed = true;
            }
            if (changed) c.recomputeHappiness();
            wakeIfSleeping(sl, c);
        }
    }

    private static void wakeIfSleeping(ServerLevel sl, CitizenEntity c) {
        if (!c.isSleeping()) return;
        net.minecraft.core.BlockPos bedPos = c.getSleepingPos().orElse(null);
        c.stopSleeping();
        if (bedPos == null) return;
        net.minecraft.world.level.block.state.BlockState bs = sl.getBlockState(bedPos);
        if (bs.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && bs.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            sl.setBlock(bedPos,
                bs.setValue(net.minecraft.world.level.block.BedBlock.OCCUPIED, false),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }
}
