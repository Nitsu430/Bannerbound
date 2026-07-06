package com.bannerbound.core.territory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.MinerWorkGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The slow heartbeat of every marked ore boulder: BoulderLayout positions that are ORE but currently
 * sit in their chipped/body state swap back to ore, one wave at a time per marker. This is what makes
 * the miner's chip cycle sustainable forever -- the vein face "refreshes" via a state swap
 * (deliberately NOT new blocks appearing from thin air; the boulder's shape never changes, only its
 * speckle pattern breathes).
 *
 * <p>Marker-driven and loaded-only: only a chunk with a committed miner workstation marker regenerates
 * (the marker IS the "this deposit is being worked" signal), and only while loaded -- an unloaded mine
 * simply pauses, which reads fine since nothing was being chipped there. Per-marker due-times live in
 * a transient map (worst case after a restart every marker is immediately due once -- harmless),
 * cleared once no markers remain.
 *
 * <p>REGEN_WAVE_INTERVAL_TICKS is the throttle: each wave restores ALL chipped faces at once, so the
 * interval sets ore income. 8000t (3x/day) left the miner idle for minutes between waves and read as
 * "broken / refuses to mine"; 1200t (~1 min, 20x/day) keeps a working face almost continuously
 * available so a miner with ore on the rock actually mines it, while still pacing yield by the
 * boulder's exposed-face count. TUNABLE -- raise toward the old value to throttle ore income, lower
 * for a near-continuous trickle. The off-screen OutpostYieldManager mirrors this rate so an unloaded
 * outpost produces at the same pace.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class MinerVeinRegen {
    private static final int SWEEP_INTERVAL_TICKS = 100;
    private static final int REGEN_WAVE_INTERVAL_TICKS = 1_200;

    private static final Map<Long, Long> NEXT_DUE = new ConcurrentHashMap<>();

    private MinerVeinRegen() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return;
        long now = sl.getGameTime();
        if (now % SWEEP_INTERVAL_TICKS != 0) return;

        boolean anyMarker = false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getAll()) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!MinerWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            anyMarker = true;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            long key = anchor.asLong();
            Long due = NEXT_DUE.get(key);
            if (due != null && now < due) continue;
            if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;
            regenWave(sl, sel, anchor);
            NEXT_DUE.put(key, now + REGEN_WAVE_INTERVAL_TICKS);
        }
        if (!anyMarker && !NEXT_DUE.isEmpty()) NEXT_DUE.clear();
    }

    private static void regenWave(ServerLevel sl, BlockSelection sel, BlockPos anchor) {
        ChunkResource type = MinerWorkGoal.mineResource(sel.seedItemId());
        int baseY = MinerWorkGoal.mineBaseY(sel.seedItemId());
        if (!BoulderLayout.isOreChunk(type) || baseY == Integer.MIN_VALUE) return;
        ChunkPos cp = new ChunkPos(anchor);
        int restored = 0;
        for (BoulderLayout.Spot s : BoulderLayout.spots(sl.getSeed(), cp, baseY)) {
            if (!s.ore()) continue;
            // isChippedState also matches legacy stand-in blocks (pre-tin-ore andesite) so old boulders migrate to the real ore block as faces refresh
            if (!BoulderLayout.isChippedState(type, sl.getBlockState(s.pos()))) continue;
            sl.setBlock(s.pos(), BoulderLayout.oreBlock(type), 3);
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, BoulderLayout.oreBlock(type)),
                s.pos().getX() + 0.5, s.pos().getY() + 0.5, s.pos().getZ() + 0.5, 3, 0.25, 0.25, 0.25, 0.0);
            restored++;
        }
        if (restored > 0) {
            BlockPos center = new BlockPos(cp.getMinBlockX() + 8, baseY + 1, cp.getMinBlockZ() + 8);
            sl.playSound(null, center, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 0.6f);
        }
    }
}
