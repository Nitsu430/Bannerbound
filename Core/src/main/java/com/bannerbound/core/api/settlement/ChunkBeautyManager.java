package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Server-side driver for chunk beauty: owns the tracked-chunk set, the debounced rescan loop,
 * and the culture-contribution / beauty-tag queries the rest of the mod reads. Tracked chunks =
 * every settlement's claimed chunks plus their 8-neighbour ring (the ring is scored only for the
 * expand-territory preview); the set is rebuilt every 10 seconds straight from SettlementData
 * (recomputeTrackedSet), so no claim/disband hooks are needed. tickAll runs every server tick
 * but acts once per second: scanning is lazy and budgeted (a tracked chunk is scanned the first
 * second it is loaded; dirty chunks rescan at most MAX_SCANS_PER_SECOND per second so an edit
 * burst can't spike a tick, and scanChunk returns false to retry later when the chunk isn't
 * loaded), and one tracked chunk per second is re-marked dirty round-robin as a safety net so
 * block changes that fire no player events (fluids, explosions, leaf decay) self-heal within
 * minutes.
 *
 * <p>After scanning, every scanned chunk's base score is refreshed under its owner's current
 * styles/palettes (cheap - no block reads, and recomputeScore self-skips when nothing changed)
 * so the culture and adjacency reads see fresh base tags. Homes are then rescored when their
 * settlement's styles/palettes hash changed - fixing the audited stale-cache bug where chunks
 * refreshed on style changes but home scores didn't until the next block edit - plus a slow
 * staleness sweep, budgeted at 4/second so a big settlement's style change spreads its rescans
 * over a few seconds instead of spiking one.
 *
 * <p>onBlockPlaced/onBlockRemoved feed player edits into the per-chunk placement queues. If the
 * chunk isn't tracked yet OR hasn't completed its initial scan, they still track it and mark it
 * dirty so the next budget batch (within ~125ms at the 8/s budget) picks it up - otherwise
 * placements made in fresh territory or inside the 10-second tracked-set-refresh window were
 * silently lost, producing a stale debug overlay and stale culture rates on freshly-claimed
 * chunks.
 *
 * <p>Adjacency: a chunk's effective beauty (effectiveBeautyOf, and the per-chunk term inside
 * cultureBonus) is its base score plus adjacencyBonus, re-mapped through the beauty thresholds.
 * The bonus is the summed BASE tier index (-4..+4) of the four orthogonally adjacent neighbours,
 * halved rounding down so adjacency stays a gentle nudge; diagonals don't count and untracked or
 * unscanned neighbours count as bland (0). It is a single non-recursive hop - neighbours
 * contribute their own base tier, never their adjacency-adjusted tier - so it cannot cascade
 * into a feedback loop. cultureBonus sums the effective-tier culture-per-second over a
 * settlement's claimed chunks only.
 */
@ApiStatus.Internal
public final class ChunkBeautyManager {
    private ChunkBeautyManager() {
    }

    private static final int MAX_SCANS_PER_SECOND = 8;
    private static final int TRACKED_SET_REFRESH_SECONDS = 10;

    private static int tickCounter = 0;
    private static int secondCounter = 0;
    private static int safetyCursor = 0;

    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        if (++tickCounter < 20) return;
        tickCounter = 0;
        secondCounter++;
        ServerLevel overworld = server.overworld();

        if (secondCounter % TRACKED_SET_REFRESH_SECONDS == 1) {
            recomputeTrackedSet(overworld);
        }

        ChunkBeautyData data = ChunkBeautyData.get(overworld);
        if (data.chunks().isEmpty()) return;
        SettlementData sd = SettlementData.get(overworld);

        List<Long> keys = new ArrayList<>(data.chunks().keySet());
        long safety = keys.get(Math.floorMod(safetyCursor++, keys.size()));
        ChunkAppealData safetyEntry = data.chunks().get(safety);
        if (safetyEntry != null && safetyEntry.isScanned()) {
            safetyEntry.markDirty();
        }

        int budget = MAX_SCANS_PER_SECOND;
        for (Map.Entry<Long, ChunkAppealData> e : data.chunks().entrySet()) {
            if (budget <= 0) break;
            ChunkAppealData cad = e.getValue();
            if (cad.isScanned() && !cad.isDirty()) continue;
            if (scanChunk(overworld, sd, e.getKey(), cad)) {
                budget--;
                data.setDirty();
            }
        }

        for (Map.Entry<Long, ChunkAppealData> e : data.chunks().entrySet()) {
            ChunkAppealData cad = e.getValue();
            if (!cad.isScanned()) continue;
            Settlement owner = sd.getByChunk(e.getKey());
            cad.recomputeScore(owner != null ? owner.cultureStyles() : List.of(),
                owner != null ? owner.activePalettes() : List.of());
        }

        int homeBudget = 4;
        long now = overworld.getGameTime();
        for (Settlement s : sd.all()) {
            if (homeBudget <= 0) break;
            int styleHash = s.cultureStyles().hashCode() * 31 + s.activePalettes().hashCode();
            for (Home h : s.homes().values()) {
                if (homeBudget <= 0) break;
                boolean styleChanged = h.lastScoredStyleHash() != styleHash;
                boolean stale = now - h.lastScoredTick() > 6_000; // 6000 ticks = 5 min safety sweep
                if (!styleChanged && !stale) continue;
                HouseAppealData.scoreOf(overworld, s, h);
                h.setLastScoredStyleHash(styleHash);
                homeBudget--;
            }
        }
    }

    public static void recomputeTrackedSet(ServerLevel level) {
        ChunkBeautyData data = ChunkBeautyData.get(level);
        SettlementData sd = SettlementData.get(level);

        Set<Long> desired = new HashSet<>();
        for (Settlement s : sd.all()) {
            for (long claimed : s.claimedChunks()) {
                ChunkPos cp = new ChunkPos(claimed);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        desired.add(new ChunkPos(cp.x + dx, cp.z + dz).toLong());
                    }
                }
            }
        }
        for (long c : desired) {
            data.track(c);
        }
        boolean removed = false;
        for (Iterator<Long> it = data.chunks().keySet().iterator(); it.hasNext(); ) {
            if (!desired.contains(it.next())) {
                it.remove();
                removed = true;
            }
        }
        if (removed) data.setDirty();
    }

    public static void onBlockPlaced(ServerLevel level, BlockPos pos, Block block) {
        // Ignore the UPPER half of two-tall blocks or appeal double-counts; the lower half's event records it.
        if (AppealResolver.isAppealDuplicateHalf(level.getBlockState(pos))) return;
        ChunkBeautyData data = ChunkBeautyData.get(level);
        long key = new ChunkPos(pos).toLong();
        ChunkAppealData cad = data.get(key);
        if (cad == null) {
            data.track(key);
            cad = data.get(key);
            data.setDirty();
        }
        if (cad == null) return;
        if (cad.isScanned()) {
            cad.recordPlace(pos, block);
        } else {
            cad.markDirty();
        }
        data.setDirty();
    }

    public static void onBlockRemoved(ServerLevel level, BlockPos pos) {
        ChunkBeautyData data = ChunkBeautyData.get(level);
        long key = new ChunkPos(pos).toLong();
        ChunkAppealData cad = data.get(key);
        if (cad == null) {
            data.track(key);
            cad = data.get(key);
            data.setDirty();
        }
        if (cad == null) return;
        if (cad.isScanned()) {
            cad.recordBreak(pos);
        } else {
            cad.markDirty();
        }
        data.setDirty();
    }

    public static double cultureBonus(ServerLevel level, Settlement s) {
        if (s == null) return 0.0;
        ChunkBeautyData data = ChunkBeautyData.get(level);
        double sum = 0.0;
        for (long claimed : s.claimedChunks()) {
            ChunkAppealData cad = data.get(claimed);
            if (cad == null || !cad.isScanned()) continue;
            ChunkBeauty effective =
                ChunkBeauty.fromScore(cad.score() + adjacencyBonus(level, claimed));
            sum += effective.culturePerSecond();
        }
        return sum;
    }

    private static final int[][] ADJACENT_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static int adjacencyBonus(ServerLevel level, long packedChunk) {
        ChunkBeautyData data = ChunkBeautyData.get(level);
        ChunkPos cp = new ChunkPos(packedChunk);
        int sum = 0;
        for (int[] d : ADJACENT_DIRS) {
            ChunkAppealData n = data.get(new ChunkPos(cp.x + d[0], cp.z + d[1]).toLong());
            if (n != null && n.isScanned()) {
                sum += n.tag().tierIndex();
            }
        }
        return Math.floorDiv(sum, 2);
    }

    public static ChunkBeauty beautyOf(ServerLevel level, long packedChunk) {
        ChunkAppealData cad = ChunkBeautyData.get(level).get(packedChunk);
        return (cad != null && cad.isScanned()) ? cad.tag() : null;
    }

    public static ChunkBeauty effectiveBeautyOf(ServerLevel level, long packedChunk) {
        ChunkAppealData cad = ChunkBeautyData.get(level).get(packedChunk);
        if (cad == null || !cad.isScanned()) return null;
        return ChunkBeauty.fromScore(cad.score() + adjacencyBonus(level, packedChunk));
    }

    private static boolean scanChunk(ServerLevel level, SettlementData sd, long packed,
                                     ChunkAppealData cad) {
        ChunkPos cp = new ChunkPos(packed);
        if (!level.hasChunk(cp.x, cp.z)) return false;
        LevelChunk chunk = level.getChunk(cp.x, cp.z);
        if (!cad.isScanned()) {
            cad.captureReferences(chunk);
        }
        cad.fullScan(chunk);
        Settlement owner = sd.getByChunk(packed);
        cad.recomputeScore(owner != null ? owner.cultureStyles() : List.of(),
            owner != null ? owner.activePalettes() : List.of());
        cad.markScanned();
        cad.clearDirty();
        return true;
    }
}
