package com.bannerbound.core.barbarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Assembles a barbarian camp village-style: a procedural center (campfire + the type-coloured
 * standard = raze target), then a ring of authored {@code .nbt} building pieces around it - one
 * chief hut, a stockpile, and enough huts to house the camp's population (big hut = 2, others = 1) -
 * each rotated to face the campfire with a dirt path trodden back to the center. Layout is a pure
 * function of the camp seed (stable across re-stamps / per-chunk stamping).
 *
 * <p>Authored pieces (built facing NORTH, ground included in the .nbt -> placed one Y lower) come from
 * {@link CampPieces}. A type with no authored pieces yet falls back to placeholder procedural tents.
 * Every write is {@code hasChunk}-guarded so a camp straddling chunk borders never force-loads a
 * neighbor (the cascade-freeze hazard).
 */
@ApiStatus.Internal
public final class CampStructureStamper {
    private static final int MIN_RING = 8;
    private static final int MAX_RING = 16;
    private static final double PIECE_SPACING = 14.0;

    private CampStructureStamper() {
    }

    public static void stamp(ServerLevel level, BarbarianCamp camp) {
        int cx = camp.center.getX();
        int cz = camp.center.getZ();

        prepArea(level, cx, cz, 1);
        BlockPos fire = surface(level, cx, cz);
        if (fire != null) {
            level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos bannerPos = surface(level, cx + 1, cz);
        if (bannerPos != null) {
            BannerBlock banner = (BannerBlock) BannerBlock.byColor(camp.type.bannerDye());
            level.setBlock(bannerPos, banner.defaultBlockState().setValue(BannerBlock.ROTATION, 12),
                Block.UPDATE_ALL);
            camp.bannerPos = bannerPos;
        }

        RandomSource rng = RandomSource.create(camp.languageSeed);
        List<ResourceLocation> ring = buildPlacementList(level, camp, rng);
        if (ring == null) {
            proceduralRing(level, camp, rng);
            return;
        }
        int n = ring.size();
        int ringR = Math.max(MIN_RING, Math.min(MAX_RING,
            (int) Math.round(n * PIECE_SPACING / (2.0 * Math.PI))));
        for (int i = 0; i < n; i++) {
            StructureTemplate template = level.getStructureManager().get(ring.get(i)).orElse(null);
            if (template == null) continue;
            double angle = (Math.PI * 2.0 / n) * i + (rng.nextDouble() - 0.5) * 0.25;
            int[] anchor = findFlatAnchor(level, cx, cz, angle, ringR, template.getSize());
            int ax = anchor[0], az = anchor[1];
            placePiece(level, template, ax, az, rotationFacingCenter(ax, az, cx, cz),
                camp.type.bannerDye());
            drawPath(level, ax, az, cx, cz, rng);
        }
    }

    private static int[] findFlatAnchor(ServerLevel level, int cx, int cz, double angle, int ringR,
                                        Vec3i size) {
        int half = Math.max(size.getX(), size.getZ()) / 2 + 1;
        int[] radii = { ringR, ringR - 2, ringR + 2, ringR - 3, ringR + 3, ringR - 4, ringR + 4 };
        int[] best = null;
        int bestRelief = Integer.MAX_VALUE;
        for (int r : radii) {
            if (r < MIN_RING - 2) continue;
            int ax = cx + (int) Math.round(Math.cos(angle) * r);
            int az = cz + (int) Math.round(Math.sin(angle) * r);
            int relief = footprintRelief(level, ax, az, half);
            if (relief <= 2) return new int[] { ax, az };
            if (relief < bestRelief) {
                bestRelief = relief;
                best = new int[] { ax, az };
            }
        }
        return best != null ? best
            : new int[] { cx + (int) Math.round(Math.cos(angle) * ringR),
                          cz + (int) Math.round(Math.sin(angle) * ringR) };
    }

    private static int footprintRelief(ServerLevel level, int cx, int cz, int half) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -half; dx <= half; dx += half) {
            for (int dz = -half; dz <= half; dz += half) {
                int x = cx + dx, z = cz + dz;
                if (!loaded(level, x, z)) continue;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        return max == Integer.MIN_VALUE ? 0 : max - min;
    }

    private static List<ResourceLocation> buildPlacementList(ServerLevel level, BarbarianCamp camp,
                                                             RandomSource rng) {
        List<CampPieces.Piece> all = CampPieces.forType(level.getServer(), camp.type);
        if (all.isEmpty()) return null;
        List<CampPieces.Piece> chiefs = ofRole(all, CampPieces.Role.CHIEF);
        List<CampPieces.Piece> stores = ofRole(all, CampPieces.Role.STOCKPILE);
        List<CampPieces.Piece> huts = ofRole(all, CampPieces.Role.HUT);

        List<ResourceLocation> ring = new ArrayList<>();
        int housed = 0;
        if (!chiefs.isEmpty()) {
            ring.add(pick(chiefs, rng).id());
            housed += 1;
        }
        if (!stores.isEmpty()) {
            ring.add(pick(stores, rng).id());
        }
        while (housed < camp.memberTarget && !huts.isEmpty()) {
            CampPieces.Piece hut = pick(huts, rng);
            ring.add(hut.id());
            housed += hutCapacity(hut.id());
        }
        return ring;
    }

    private static int hutCapacity(ResourceLocation id) {
        return id.getPath().toLowerCase(Locale.ROOT).contains("big") ? 2 : 1;
    }

    private static void placePiece(ServerLevel level, StructureTemplate template, int x, int z,
                                   Rotation facing, DyeColor dye) {
        if (!loaded(level, x, z)) return;
        Vec3i size = template.getSize();
        prepArea(level, x, z, Math.max(size.getX(), size.getZ()) / 2 + 1);
        BlockPos pivot = new BlockPos(size.getX() / 2, 0, size.getZ() / 2);
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(facing).setRotationPivot(pivot).setIgnoreEntities(true)
            // STRUCTURE_AND_AIR: skip the .nbt's air cells or they carve the terrain
            .addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem
                .BlockIgnoreProcessor.STRUCTURE_AND_AIR);
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos placePos = new BlockPos(x - pivot.getX(), gy - 1, z - pivot.getZ());
        template.placeInWorld(level, placePos, placePos, settings, level.random, Block.UPDATE_CLIENTS);
        int reach = Math.max(size.getX(), size.getZ());
        recolorBanners(level, x, z, gy - 1, reach, size.getY() + 1, dye);
    }

    private static void recolorBanners(ServerLevel level, int cx, int cz, int y0, int reach,
                                       int height, DyeColor dye) {
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!loaded(level, x, z)) continue;
                for (int dy = 0; dy <= height; dy++) {
                    BlockPos p = new BlockPos(x, y0 + dy, z);
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.WHITE_BANNER)) {
                        level.setBlock(p, BannerBlock.byColor(dye).defaultBlockState()
                            .setValue(BannerBlock.ROTATION, st.getValue(BannerBlock.ROTATION)),
                            Block.UPDATE_CLIENTS);
                    } else if (st.is(Blocks.WHITE_WALL_BANNER)) {
                        level.setBlock(p, wallBannerFor(dye).defaultBlockState().setValue(
                            net.minecraft.world.level.block.WallBannerBlock.FACING,
                            st.getValue(net.minecraft.world.level.block.WallBannerBlock.FACING)),
                            Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static Block wallBannerFor(DyeColor color) {
        return switch (color) {
            case YELLOW -> Blocks.YELLOW_WALL_BANNER;
            case GREEN -> Blocks.GREEN_WALL_BANNER;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WALL_BANNER;
            case RED -> Blocks.RED_WALL_BANNER;
            default -> Blocks.WHITE_WALL_BANNER;
        };
    }

    private static CampPieces.Piece pick(List<CampPieces.Piece> list, RandomSource rng) {
        return list.get(rng.nextInt(list.size()));
    }

    private static List<CampPieces.Piece> ofRole(List<CampPieces.Piece> all, CampPieces.Role role) {
        List<CampPieces.Piece> out = new ArrayList<>();
        for (CampPieces.Piece p : all) {
            if (p.role() == role) out.add(p);
        }
        return out;
    }

    private static void proceduralRing(ServerLevel level, BarbarianCamp camp, RandomSource rng) {
        int tents = Math.max(3, camp.memberTarget / 2);
        for (int i = 0; i < tents; i++) {
            double angle = (Math.PI * 2.0 / tents) * i + (rng.nextDouble() - 0.5) * 0.4;
            int radius = MIN_RING + rng.nextInt(MAX_RING - MIN_RING + 1);
            int ax = camp.center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int az = camp.center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            placeProceduralTent(level, camp, ax, az);
            drawPath(level, ax, az, camp.center.getX(), camp.center.getZ(), rng);
        }
    }

    private static void placeProceduralTent(ServerLevel level, BarbarianCamp camp, int x, int z) {
        prepArea(level, x, z, 1);
        BlockPos base = surface(level, x, z);
        if (base == null) return;
        BlockState canopy = woolFor(camp.type.bannerDye());
        level.setBlock(base, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.above(), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.above(2), canopy, Block.UPDATE_ALL);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = base.above().relative(d);
            if (loaded(level, side.getX(), side.getZ()) && level.getBlockState(side).canBeReplaced()) {
                level.setBlock(side, canopy, Block.UPDATE_ALL);
            }
        }
        level.setBlock(base.above(3), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void drawPath(ServerLevel level, int x0, int z0, int x1, int z1, RandomSource rng) {
        int dx = x1 - x0, dz = z1 - z0;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return;
        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        for (int s = 1; s < steps; s++) {
            int x = x0 + Math.round((float) dx * s / steps);
            int z = z0 + Math.round((float) dz * s / steps);
            pavePathCell(level, x, z, true, rng);
            if (alongX) {
                pavePathCell(level, x, z - 1, false, rng);
                pavePathCell(level, x, z + 1, false, rng);
            } else {
                pavePathCell(level, x - 1, z, false, rng);
                pavePathCell(level, x + 1, z, false, rng);
            }
        }
    }

    private static void pavePathCell(ServerLevel level, int x, int z, boolean always, RandomSource rng) {
        if (!always && rng.nextFloat() < 0.5f) return;
        if (!loaded(level, x, z)) return;
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, gy - 1, z);
        BlockState gs = level.getBlockState(ground);
        if (gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT)
                || gs.is(Blocks.PODZOL) || gs.is(Blocks.SAND) || gs.is(Blocks.GRAVEL)) {
            level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos above = new BlockPos(x, gy, z);
            if (level.getBlockState(above).canBeReplaced() && level.getFluidState(above).isEmpty()) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static Rotation rotationFacingCenter(int x, int z, int cx, int cz) {
        // pieces are built facing NORTH; verified Rotation.rotate(NORTH): NONE->N, CW90->E, CW180->S, CCW90->W
        int dx = cx - x, dz = cz - z;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Rotation.CLOCKWISE_90
                           : Rotation.COUNTERCLOCKWISE_90;
        }
        return dz >= 0 ? Rotation.CLOCKWISE_180
                       : Rotation.NONE;
    }

    private static void prepArea(ServerLevel level, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!loaded(level, x, z)) continue;
                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int dy = 0; dy < 3; dy++) {
                    BlockPos p = new BlockPos(x, gy + dy, z);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) continue;
                    if (st.canBeReplaced() && st.getFluidState().isEmpty()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        if (!loaded(level, x, z)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    private static BlockState woolFor(DyeColor color) {
        return (switch (color) {
            case YELLOW -> Blocks.YELLOW_WOOL;
            case GREEN -> Blocks.GREEN_WOOL;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WOOL;
            case RED -> Blocks.RED_WOOL;
            default -> Blocks.WHITE_WOOL;
        }).defaultBlockState();
    }
}
