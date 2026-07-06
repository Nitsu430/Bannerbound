package com.bannerbound.core.territory;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.SiteWarning;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Cheap, one-time terrain check run the moment a player opens the founding screen, so the screen can
 * warn them off obviously poor sites (desert with no water, barren ground) before they commit.
 * assess/assessMask sample a coarse grid (every STEP=4th surface column) over a 5x5-chunk footprint
 * around the town hall -- RADIUS_CHUNKS=2 is deliberately one wider than the initial claim radius,
 * because a settlement can expand to own more than its starting chunks, so we judge the land it could
 * plausibly reach. Each land column is dug up to FOLIAGE_DEPTH=12 blocks past canopy/tall-grass/snow
 * to the real ground and classified fertile (BlockTags.DIRT -- grass, dirt, podzol, coarse/rooted
 * dirt, mycelium, moss, mud -- plus farmland and moss block); below MIN_GRASS_FRACTION=0.15
 * fertile-of-land the site reads POOR_SOIL, and zero open-water columns reads NO_WATER. Sampling is
 * forgiving: unloaded columns are skipped, and if the whole area is unloaded (shouldn't happen -- the
 * player stands in it) we raise no warnings rather than a false alarm.
 */
@ApiStatus.Internal
public final class SettlementSiteAssessor {
    private static final int RADIUS_CHUNKS = 2;
    private static final int STEP = 4;
    private static final int FOLIAGE_DEPTH = 12;
    private static final double MIN_GRASS_FRACTION = 0.15;

    private SettlementSiteAssessor() {
    }

    public static int assessMask(ServerLevel level, BlockPos center) {
        return SiteWarning.toMask(assess(level, center));
    }

    public static EnumSet<SiteWarning> assess(ServerLevel level, BlockPos center) {
        ChunkPos centerChunk = new ChunkPos(center);
        int minX = (centerChunk.x - RADIUS_CHUNKS) << 4;
        int minZ = (centerChunk.z - RADIUS_CHUNKS) << 4;
        int span = (RADIUS_CHUNKS * 2 + 1) * 16;

        int sampled = 0;
        int water = 0;
        int fertile = 0;
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < span; dx += STEP) {
            for (int dz = 0; dz < span; dz += STEP) {
                int x = minX + dx;
                int z = minZ + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                sampled++;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

                cursor.set(x, top - 1, z);
                if (level.getBlockState(cursor).getFluidState().is(FluidTags.WATER)) {
                    water++;
                    continue;
                }

                for (int y = top - 1; y >= top - FOLIAGE_DEPTH && y > minY; y--) {
                    cursor.set(x, y, z);
                    BlockState s = level.getBlockState(cursor);
                    if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)
                            || s.is(BlockTags.REPLACEABLE) || s.is(Blocks.SNOW)) {
                        continue;
                    }
                    if (isFertile(s)) {
                        fertile++;
                    }
                    break;
                }
            }
        }

        EnumSet<SiteWarning> warnings = EnumSet.noneOf(SiteWarning.class);
        if (sampled == 0) {
            return warnings;
        }
        if (water == 0) {
            warnings.add(SiteWarning.NO_WATER);
        }
        int land = sampled - water;
        double grassFraction = land <= 0 ? 1.0 : (double) fertile / land;
        if (grassFraction < MIN_GRASS_FRACTION) {
            warnings.add(SiteWarning.POOR_SOIL);
        }
        return warnings;
    }

    private static boolean isFertile(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.FARMLAND) || s.is(Blocks.MOSS_BLOCK);
    }
}
