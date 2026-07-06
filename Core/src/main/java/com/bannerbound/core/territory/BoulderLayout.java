package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The ore boulder of a metal/marble resource chunk as a pure deterministic function of world seed
 * + chunk coords + base height: every consumer -- worldgen placement (ResourceChunkPopulator), the
 * miner's chip targets (MinerWorkGoal) and the vein regen ticker (MinerVeinRegen) -- derives the
 * SAME pos->ore/body pattern with zero save data. Uses per-position hash RNG (never a sequential
 * stream), consumed in a FIXED order per spot (edge-cull roll then ore roll), so the pattern is
 * stable regardless of what already exists in the world or the order positions are visited;
 * reordering those two rolls silently desyncs the consumers.
 *
 * <p>The miner never destroys boulder blocks: it swaps an ORE-state block to the type's chipped
 * state ("the vein face is worked out") and the regen ticker swaps it back ("the face refreshes").
 * Block STATES change; the boulder's mass never does -- it stays the chunk's permanent identity
 * marker, and body/chipped blocks are indestructible.
 *
 * <p>Palette per resource type: copper/iron/coal use vanilla ores over a stone body; marble's "ore"
 * and body are both calcite, so a worked marble face greys to TUFF to stay visible; tin resolves
 * Antiquity's real tin_ore/raw_tin by string id (Core stays standalone) and falls back to an
 * andesite speckle over a tuff body when Antiquity is absent. isChippedState also accepts legacy
 * ANDESITE tin faces, so regen quietly migrates pre-tin-ore boulders to the real block. dropFor is
 * empty when the yield item does not exist in this install -- callers then refuse to mark/work that
 * chunk. Richness is a deterministic per-chunk tier poor(25%)/normal(50%)/rich(25%) -> ore share
 * 0.30/0.45/0.60: scoutable intel that makes deposits worth comparing, not just finding.
 *
 * <p>locateBaseY scores candidate base heights (a few blocks under the surface reading, since the
 * boulder's top sits at baseY+2) by how many layout positions hold ore/body/chipped blocks, and
 * demands a real cluster before accepting one. dress builds/re-dresses the boulder at the natural
 * surface, replacing only natural/replaceable ground (canCarve, never logs/leaves/player blocks);
 * it is both the populator's placement path and the rod's commit-time fallback for pre-feature
 * chunks. groundSurfaceY walks down past canopy/plants/water because WORLD_SURFACE alone lands on
 * the treetops.
 *
 * <p>Pre-existing boulders were placed with a sequential RNG whose stream consumed terrain-dependent
 * rolls, so their exact pattern is unrecoverable; they drift toward this layout as they are worked.
 * Geometry is identical, so the drift is invisible in practice.
 */
@ApiStatus.Internal
public final class BoulderLayout {
    public static final int RADIUS = 2;
    private static final float[] ORE_CHANCE_BY_RICHNESS = {0.30f, 0.45f, 0.60f};
    private static final ResourceLocation RAW_TIN_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "raw_tin");
    private static final ResourceLocation TIN_ORE_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "tin_ore");

    private BoulderLayout() {
    }

    public record Spot(BlockPos pos, boolean ore) {}

    public static List<Spot> spots(long worldSeed, ChunkPos cp, int baseY) {
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        float oreChance = ORE_CHANCE_BY_RICHNESS[richness(worldSeed, cp)];
        List<Spot> out = new ArrayList<>();
        int r = RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= r; dy++) {
                    double d = dx * dx + dy * dy * 1.5 + dz * dz;
                    if (d > r * r + 0.7) continue;
                    RandomSource posRand = posRand(worldSeed, cp, dx, dy, dz);
                    // Edge-cull roll BEFORE the ore roll: RNG call order is load-bearing for cross-consumer determinism.
                    if (d > (r - 1) * (r - 1) && posRand.nextBoolean()) continue;
                    boolean ore = posRand.nextFloat() < oreChance;
                    out.add(new Spot(new BlockPos(cx + dx, baseY + dy, cz + dz), ore));
                }
            }
        }
        return out;
    }

    public static int richness(long worldSeed, ChunkPos cp) {
        RandomSource r = RandomSource.create(worldSeed ^ cp.toLong() * 0x6A09E667F3BCC909L);
        int roll = r.nextInt(4);
        return roll == 0 ? 0 : roll == 3 ? 2 : 1;
    }

    private static RandomSource posRand(long worldSeed, ChunkPos cp, int dx, int dy, int dz) {
        long mixed = worldSeed
            ^ cp.toLong() * 0x9E3779B97F4A7C15L
            ^ BlockPos.asLong(dx, dy, dz) * 0xC2B2AE3D27D4EB4FL;
        return RandomSource.create(mixed);
    }

    public static BlockState oreBlock(ChunkResource type) {
        return switch (type) {
            case COPPER -> Blocks.COPPER_ORE.defaultBlockState();
            case IRON -> Blocks.IRON_ORE.defaultBlockState();
            case COAL -> Blocks.COAL_ORE.defaultBlockState();
            case MARBLE -> Blocks.CALCITE.defaultBlockState();
            case TIN -> BuiltInRegistries.BLOCK.getOptional(TIN_ORE_ID)
                .map(Block::defaultBlockState)
                .orElse(Blocks.ANDESITE.defaultBlockState());
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    public static BlockState bodyBlock(ChunkResource type) {
        return switch (type) {
            case MARBLE -> Blocks.CALCITE.defaultBlockState();
            case TIN -> Blocks.TUFF.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    public static BlockState chippedBlock(ChunkResource type) {
        return type == ChunkResource.MARBLE ? Blocks.TUFF.defaultBlockState() : bodyBlock(type);
    }

    public static boolean isChippedState(ChunkResource type, BlockState state) {
        if (state.is(chippedBlock(type).getBlock())) return true;
        return type == ChunkResource.TIN && state.is(Blocks.ANDESITE)
            && BuiltInRegistries.BLOCK.getOptional(TIN_ORE_ID).isPresent();
    }

    public static Optional<Item> dropFor(ChunkResource type) {
        return switch (type) {
            case COPPER -> Optional.of(net.minecraft.world.item.Items.RAW_COPPER);
            case IRON -> Optional.of(net.minecraft.world.item.Items.RAW_IRON);
            case COAL -> Optional.of(net.minecraft.world.item.Items.COAL);
            case MARBLE -> Optional.of(Blocks.CALCITE.asItem());
            case TIN -> BuiltInRegistries.ITEM.getOptional(RAW_TIN_ID);
            default -> Optional.empty();
        };
    }

    public static boolean isOreChunk(ChunkResource type) {
        return type == ChunkResource.COPPER || type == ChunkResource.IRON
            || type == ChunkResource.MARBLE || type == ChunkResource.TIN
            || type == ChunkResource.COAL;
    }

    public static Optional<Integer> locateBaseY(ServerLevel sl, ChunkPos cp, ChunkResource type) {
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int surface = groundSurfaceY(sl, cx, cz);
        Block ore = oreBlock(type).getBlock();
        Block body = bodyBlock(type).getBlock();
        int bestY = Integer.MIN_VALUE;
        int bestScore = 0;
        for (int baseY = surface - RADIUS - 1; baseY <= surface + 1; baseY++) {
            int score = 0;
            for (Spot s : spots(sl.getSeed(), cp, baseY)) {
                BlockState at = sl.getBlockState(s.pos());
                if (at.is(ore) || at.is(body) || isChippedState(type, at)) score++;
            }
            if (score > bestScore) { bestScore = score; bestY = baseY; }
        }
        return bestScore >= 6 ? Optional.of(bestY) : Optional.empty();
    }

    public static int dress(ServerLevel sl, ChunkPos cp) {
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int baseY = groundSurfaceY(sl, cx, cz);
        for (Spot s : spots(sl.getSeed(), cp, baseY)) {
            if (!canCarve(sl.getBlockState(s.pos()))) continue;
            sl.setBlock(s.pos(), s.ore() ? oreBlock(type) : bodyBlock(type), 3);
        }
        return baseY;
    }

    public static boolean canCarve(BlockState s) {
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) return false;
        return s.canBeReplaced()
            || s.is(BlockTags.DIRT)
            || s.is(BlockTags.SAND)
            || s.is(BlockTags.BASE_STONE_OVERWORLD)
            || s.is(Blocks.GRAVEL)
            || s.is(Blocks.SNOW_BLOCK)
            || s.is(Blocks.GRASS_BLOCK);
    }

    public static int groundSurfaceY(ServerLevel sl, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(
            x, sl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
        int floor = sl.getMinBuildHeight();
        while (m.getY() > floor) {
            m.move(Direction.DOWN);
            BlockState s = sl.getBlockState(m);
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) continue;
            if (s.getFluidState().isEmpty() && s.blocksMotion()) return m.getY();
        }
        return floor;
    }
}
