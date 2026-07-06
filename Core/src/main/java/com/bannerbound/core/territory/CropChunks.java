package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The crop-field analogue of BoulderLayout: the per-type block/seed mappings that make a
 * WHEAT/CARROT/BEETROOT/POTATO chunk a farmland resource, plus the world-gen "dress" that scatters
 * its visible wild farmland patches. cropBlock/seedFor give a chunk its vanilla crop and the seed a
 * farmer plants; cropChunkFor/bonusSeedIds run the reverse -- a field earns a 2x harvest bonus on
 * seeds whose crop chunk it overlaps (drives the green hint in the seed UI).
 *
 * <p>Unlike the ore boulder, nothing here needs a deterministic per-position layout: the farmer
 * just tends whatever crops stand on the farmland and the forager picks whatever is ripe -- neither
 * re-derives exact tile coordinates. So dressWildField is a one-shot deterministic scatter (seeded
 * by chunk), not a recoverable spots()-style layout, placing dry farmland only on natural
 * grass/dirt with a crop at random maturity above.
 *
 * <p>All edits stay within +/-5 of the chunk's +8 centre, so the +/-6 ring probe in
 * ChunkResources.typeAt never reads them and the chunk's type stays a pure function of the natural
 * terrain (the same guarantee the boulder relies on).
 */
@ApiStatus.Internal
public final class CropChunks {
    private CropChunks() {
    }

    public static boolean isCropChunk(ChunkResource type) {
        return type == ChunkResource.WHEAT || type == ChunkResource.CARROT
            || type == ChunkResource.BEETROOT || type == ChunkResource.POTATO;
    }

    public static CropBlock cropBlock(ChunkResource type) {
        return (CropBlock) switch (type) {
            case WHEAT -> Blocks.WHEAT;
            case CARROT -> Blocks.CARROTS;
            case BEETROOT -> Blocks.BEETROOTS;
            case POTATO -> Blocks.POTATOES;
            default -> Blocks.WHEAT;
        };
    }

    public static Item seedFor(ChunkResource type) {
        return switch (type) {
            case WHEAT -> Items.WHEAT_SEEDS;
            case CARROT -> Items.CARROT;
            case BEETROOT -> Items.BEETROOT_SEEDS;
            case POTATO -> Items.POTATO;
            default -> Items.AIR;
        };
    }

    public static ChunkResource cropChunkFor(Item seed) {
        if (seed == Items.WHEAT_SEEDS) return ChunkResource.WHEAT;
        if (seed == Items.CARROT) return ChunkResource.CARROT;
        if (seed == Items.BEETROOT_SEEDS) return ChunkResource.BEETROOT;
        if (seed == Items.POTATO) return ChunkResource.POTATO;
        return ChunkResource.NONE;
    }

    public static List<String> bonusSeedIds(ServerLevel sl, int minX, int minZ, int maxX, int maxZ) {
        List<String> out = new ArrayList<>();
        EnumSet<ChunkResource> seen = EnumSet.noneOf(ChunkResource.class);
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                ChunkResource t = ChunkResources.typeAt(sl, new ChunkPos(cx, cz));
                if (isCropChunk(t) && seen.add(t)) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(seedFor(t));
                    if (id != null) out.add(id.toString());
                }
            }
        }
        return out;
    }

    private static final int MIN_TILES = 10;
    private static final int TILE_SPREAD = 7;
    // Must stay inside ChunkResources.typeAt's +/-6 ring probe or dressing flips the chunk's own type reading.
    private static final int FIELD_RADIUS = 5;

    public static void dressWildField(ServerLevel sl, ChunkPos cp) {
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        if (!isCropChunk(type)) return;
        CropBlock crop = cropBlock(type);
        int maxAge = crop.getMaxAge();
        RandomSource rand = RandomSource.create(sl.getSeed() ^ cp.toLong() ^ 0x5DEECE66DL);
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int tiles = MIN_TILES + rand.nextInt(TILE_SPREAD);
        for (int i = 0; i < tiles; i++) {
            int x = cx + rand.nextInt(FIELD_RADIUS * 2 + 1) - FIELD_RADIUS;
            int z = cz + rand.nextInt(FIELD_RADIUS * 2 + 1) - FIELD_RADIUS;
            int y = BoulderLayout.groundSurfaceY(sl, x, z);
            BlockPos ground = new BlockPos(x, y, z);
            BlockState gs = sl.getBlockState(ground);
            if (!(gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT))) continue;
            if (!sl.getBlockState(ground.above()).isAir()) continue;
            sl.setBlock(ground, Blocks.FARMLAND.defaultBlockState(), 3);
            sl.setBlock(ground.above(), crop.getStateForAge(rand.nextInt(maxAge + 1)), 3);
        }
    }
}
