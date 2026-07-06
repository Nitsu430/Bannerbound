package com.bannerbound.core.territory;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Gives each specialized chunk its natural content the moment it generates: horse chunks spawn a
 * herd, metal/coal chunks get a surface ore boulder, generic material chunks a stone boulder or
 * clay/sand pit, fish chunks a school plus seagrass, crop chunks a wild field. The chunk simply IS
 * its ChunkResource (no marker block, no hiding); type is read from the deterministic ChunkResources,
 * and per-chunk layout uses a seeded RandomSource so it stays stable across regen.
 *
 * <p>Entry point is ChunkEvent.Load filtered to isNewChunk() (server-only, fires once when a chunk is
 * first generated). Per the event contract the chunk may not be FULL yet, so every world edit is
 * deferred one server tick via MinecraftServer.execute -- by then it is a normal loaded chunk and
 * plain ServerLevel APIs are safe. The deferred task is wrapped in try/catch so a thrown edit cannot
 * propagate into the server task runner.
 *
 * <p>CRITICAL: every terrain probe/edit stays within the 16x16 chunk (offsets kept within about +-6
 * of the +8 centre). Reaching a neighbour chunk force-generates it and cascades into a
 * server-freezing chunk-gen storm.
 *
 * <p>Vanilla spawn suppression (onFinalizeSpawn): only horses stay chunk-exclusive -- their NATURAL
 * and CHUNK_GENERATION spawns are cancelled so they exist only where this populator places them;
 * cow/pig/sheep/chicken now spawn naturally. Our own herds bypass the cancel because we call
 * Mob#finalizeSpawn DIRECTLY, which (unlike vanilla spawn paths) does not fire FinalizeSpawnEvent;
 * breeding, spawn eggs and buckets are untouched.
 *
 * <p>Tethering (onEntityTick): a free herd animal or fish that drifts past its leash radius from the
 * chunk centre is navigated home so the herd/school keeps marking its chunk. Leashing an animal means
 * the player has claimed it -- its home key is cleared and it is released for good; breeding, babies,
 * spooked (recently hurt), ridden and passenger animals are left alone.
 *
 * <p>Marble/tin have no dedicated block yet, so their boulders use calcite / tuff+andesite stand-ins.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ResourceChunkPopulator {
    private static final int HERD_HORSES = 3;

    private ResourceChunkPopulator() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return;
        ChunkPos cp = event.getChunk().getPos();
        // Defer one tick: chunk may not be FULL yet; guard so a thrown edit can't reach the task runner.
        sl.getServer().execute(() -> {
            try {
                if (sl.hasChunk(cp.x, cp.z)) populate(sl, cp);
            } catch (Exception e) {
                BannerboundCore.LOGGER.error("Resource-chunk populate failed at {}", cp, e);
            }
        });
    }

    private static void populate(ServerLevel sl, ChunkPos cp) {
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        if (type == ChunkResource.NONE) return;
        RandomSource rand = RandomSource.create(sl.getSeed() ^ cp.toLong() ^ 0x5DEECE66DL);
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        switch (type) {
            case HORSES -> { decorate(sl, rand, cx, cz); spawnHerd(sl, rand, cx, cz, EntityType.HORSE, HERD_HORSES); }
            case COPPER, IRON, MARBLE, TIN, COAL -> BoulderLayout.dress(sl, cp);
            case FISH -> spawnFishingGround(sl, rand, cx, cz);
            case WHEAT, CARROT, BEETROOT, POTATO -> CropChunks.dressWildField(sl, cp);
            case STONE, CLAY, SAND, LIMESTONE, ANDESITE, DIORITE, GRANITE -> MaterialDepositLayout.dress(sl, cp);
            default -> { }
        }
    }

    private static final Set<EntityType<?>> MANAGED_ANIMALS = Set.of(EntityType.HORSE);

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType st = event.getSpawnType();
        if (st != MobSpawnType.NATURAL && st != MobSpawnType.CHUNK_GENERATION) return;
        if (MANAGED_ANIMALS.contains(event.getEntity().getType())) {
            event.setSpawnCancelled(true);
        }
    }

    private static final String ANIMAL_HOME_KEY = "BannerboundHerdHome";
    private static final double ANIMAL_LEASH = 12.0;

    private static void spawnHerd(ServerLevel sl, RandomSource rand, int cx, int cz,
                                  EntityType<? extends Mob> type, int count) {
        for (int i = 0; i < count; i++) {
            int ax = cx + rand.nextInt(9) - 4;
            int az = cz + rand.nextInt(9) - 4;
            int groundY = groundSurfaceY(sl, ax, az);
            int ay = groundY + 1;
            BlockPos feet = new BlockPos(ax, ay, az);
            if (sl.getBlockState(feet).blocksMotion() || sl.getBlockState(feet.above()).blocksMotion()) continue;
            if (!sl.getBlockState(feet).getFluidState().isEmpty()) continue;
            Mob mob = type.create(sl);
            if (mob == null) continue;
            mob.moveTo(ax + 0.5, ay, az + 0.5, rand.nextFloat() * 360f, 0f);
            mob.finalizeSpawn(sl, sl.getCurrentDifficultyAt(feet), MobSpawnType.NATURAL, null);
            mob.setPersistenceRequired();
            mob.getPersistentData().putLong(ANIMAL_HOME_KEY, BlockPos.asLong(cx, ay, cz));
            sl.addFreshEntity(mob);
        }
    }

    private static void tetherAnimal(Animal a) {
        CompoundTag d = a.getPersistentData();
        if (!d.contains(ANIMAL_HOME_KEY)) return;
        if (a.isLeashed()) { d.remove(ANIMAL_HOME_KEY); return; }
        if (a.tickCount % 40 != 0) return;
        if (a.isInLove() || a.isBaby() || a.isVehicle() || a.isPassenger()) return;
        if (a.hurtTime > 0 || a.getLastHurtByMob() != null) return;
        BlockPos home = BlockPos.of(d.getLong(ANIMAL_HOME_KEY));
        double dx = home.getX() + 0.5 - a.getX();
        double dz = home.getZ() + 0.5 - a.getZ();
        if (dx * dx + dz * dz > ANIMAL_LEASH * ANIMAL_LEASH) {
            a.getNavigation().moveTo(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5, 1.0);
        }
    }

    private static int groundSurfaceY(ServerLevel sl, int x, int z) {
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

    private static void spawnFishingGround(ServerLevel sl, RandomSource rand, int cx, int cz) {
        int fishCount = 3 + rand.nextInt(3);
        for (int i = 0; i < fishCount; i++) {
            int fx = cx + rand.nextInt(9) - 4;
            int fz = cz + rand.nextInt(9) - 4;
            int floor = sl.getHeight(Heightmap.Types.OCEAN_FLOOR, fx, fz);
            int top = sl.getHeight(Heightmap.Types.WORLD_SURFACE, fx, fz);
            int depth = top - floor;
            if (depth < 1) continue;
            int fy = floor + rand.nextInt(depth);
            BlockPos p = new BlockPos(fx, fy, fz);
            if (sl.getBlockState(p).getFluidState().isEmpty()) continue;
            Mob fish = (rand.nextInt(4) == 0 ? EntityType.SALMON : EntityType.COD).create(sl);
            if (fish == null) continue;
            fish.moveTo(fx + 0.5, fy + 0.5, fz + 0.5, rand.nextFloat() * 360f, 0f);
            fish.finalizeSpawn(sl, sl.getCurrentDifficultyAt(p), MobSpawnType.NATURAL, null);
            fish.setPersistenceRequired();
            fish.getPersistentData().putLong(FISH_HOME_KEY, BlockPos.asLong(cx, fy, cz));
            sl.addFreshEntity(fish);
        }
        for (int i = 0; i < 4; i++) {
            int gx = cx + rand.nextInt(9) - 4;
            int gz = cz + rand.nextInt(9) - 4;
            int floor = sl.getHeight(Heightmap.Types.OCEAN_FLOOR, gx, gz);
            BlockPos water = new BlockPos(gx, floor, gz);
            BlockState ground = sl.getBlockState(water.below());
            if (sl.getBlockState(water).is(Blocks.WATER) && !ground.isAir()
                && ground.getFluidState().isEmpty()) {
                sl.setBlock(water, Blocks.SEAGRASS.defaultBlockState(), 3);
            }
        }
    }

    private static final String FISH_HOME_KEY = "BannerboundFishHome";
    private static final double FISH_LEASH = 11.0;

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (e.level().isClientSide) return;
        if (e instanceof AbstractFish fish) {
            tetherFish(fish);
        } else if (e instanceof Animal animal) {
            tetherAnimal(animal);
        }
    }

    private static void tetherFish(AbstractFish fish) {
        if (fish.tickCount % 20 != 0) return;
        CompoundTag data = fish.getPersistentData();
        if (!data.contains(FISH_HOME_KEY)) return;
        BlockPos home = BlockPos.of(data.getLong(FISH_HOME_KEY));
        double dx = home.getX() + 0.5 - fish.getX();
        double dz = home.getZ() + 0.5 - fish.getZ();
        if (dx * dx + dz * dz > FISH_LEASH * FISH_LEASH) {
            fish.getNavigation().moveTo(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5, 1.2);
        }
    }

    private static void decorate(ServerLevel sl, RandomSource rand, int cx, int cz) {
        // Keep every offset within this chunk; sampling a neighbour force-loads it -> chunk-gen cascade.
        BlockState hubGround = (rand.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState();
        buildHub(sl, rand, cx, cz, hubGround);
        dirtPaths(sl, rand, cx, cz, 4 + rand.nextInt(7));
        shortGrass(sl, rand, cx, cz, 3 + rand.nextInt(8));
        if (rand.nextInt(2) == 0) {
            groundPatch(sl, rand, cx, cz,
                (rand.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState(), 1 + rand.nextInt(2));
        }
        if (rand.nextInt(2) == 0) {
            flowers(sl, rand, cx, cz, 2 + rand.nextInt(4));
        }
    }

    private static void buildHub(ServerLevel sl, RandomSource rand, int cx, int cz, BlockState ground) {
        int radius = rand.nextInt(3) == 0 ? 3 : 2;
        int hx = cx, hz = cz, hy = groundSurfaceY(sl, cx, cz);
        int fbx = hx, fbz = hz, fby = hy;
        boolean flat = false;
        boolean solid = false;
        for (int r = 0; r <= 3 && !solid; r++) {
            for (int dx = -r; dx <= r && !solid; dx++) {
                for (int dz = -r; dz <= r && !solid; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    int y = groundSurfaceY(sl, x, z);
                    if (!isFlatArea(sl, x, z, y, 2)) continue;
                    if (!flat) { fbx = x; fbz = z; fby = y; flat = true; }
                    if (hasSolidBaseFor2x2(sl, x, z, y)) { hx = x; hz = z; hy = y; solid = true; }
                }
            }
        }
        if (!solid && flat) { hx = fbx; hz = fbz; hy = fby; }

        int rr = radius * radius + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rr) continue;
                int x = hx + dx;
                int z = hz + dz;
                BlockPos p = new BlockPos(x, groundSurfaceY(sl, x, z), z);
                if (isReplaceableGround(sl.getBlockState(p))) sl.setBlock(p, ground, 3);
            }
        }
        if (!hasWaterInChunk(sl, cx, cz)) digDrinkingPool(sl, hx, hz, hy, ground);
    }

    private static boolean hasSolidBaseFor2x2(ServerLevel sl, int hx, int hz, int y) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                if (!sl.getBlockState(new BlockPos(hx + dx, y - 1, hz + dz)).blocksMotion()) return false;
                if (!sl.getBlockState(new BlockPos(hx + dx, y - 2, hz + dz)).blocksMotion()) return false;
            }
        }
        return true;
    }

    private static boolean hasWaterInChunk(ServerLevel sl, int cx, int cz) {
        for (int dx = -6; dx <= 6; dx += 3) {
            for (int dz = -6; dz <= 6; dz += 3) {
                int gy = groundSurfaceY(sl, cx + dx, cz + dz);
                if (!sl.getBlockState(new BlockPos(cx + dx, gy + 1, cz + dz)).getFluidState().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void digDrinkingPool(ServerLevel sl, int hx, int hz, int y, BlockState rim) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                BlockPos at = new BlockPos(hx + dx, y, hz + dz);
                boolean inner = dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
                for (int d = 1; d <= 6; d++) {
                    BlockPos fp = new BlockPos(hx + dx, y - d, hz + dz);
                    if (sl.getBlockState(fp).blocksMotion()) break;
                    sl.setBlock(fp, rim, 3);
                }
                if (!inner) {
                    BlockState s = sl.getBlockState(at);
                    if (!s.blocksMotion() || !s.getFluidState().isEmpty()) sl.setBlock(at, rim, 3);
                }
            }
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos at = new BlockPos(hx + dx, y, hz + dz);
                sl.setBlock(at, Blocks.WATER.defaultBlockState(), 3);
                if (sl.getBlockState(at.above()).blocksMotion()) {
                    sl.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static final net.minecraft.world.level.block.Block[] FLOWERS = {
        Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};

    private static void flowers(ServerLevel sl, RandomSource rand, int cx, int cz, int count) {
        net.minecraft.world.level.block.Block flower = FLOWERS[rand.nextInt(FLOWERS.length)];
        for (int i = 0; i < count; i++) {
            int x = cx + rand.nextInt(13) - 6;
            int z = cz + rand.nextInt(13) - 6;
            BlockPos ground = new BlockPos(x, groundSurfaceY(sl, x, z), z);
            BlockPos above = ground.above();
            if (sl.getBlockState(above).isAir() && sl.getBlockState(ground).is(Blocks.GRASS_BLOCK)) {
                sl.setBlock(above, flower.defaultBlockState(), 3);
            }
        }
    }

    private static boolean isFlatArea(ServerLevel sl, int x, int z, int y, int half) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (groundSurfaceY(sl, x + dx, z + dz) != y) return false;
                BlockState s = sl.getBlockState(new BlockPos(x + dx, y, z + dz));
                if (!(s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
                    || s.is(Blocks.DIRT_PATH) || s.is(Blocks.SAND) || s.is(Blocks.SNOW_BLOCK))) {
                    return false;
                }
                if (sl.getBlockState(new BlockPos(x + dx, y + 1, z + dz)).blocksMotion()) return false;
            }
        }
        return true;
    }

    private static void dirtPaths(ServerLevel sl, RandomSource rand, int cx, int cz, int count) {
        for (int i = 0; i < count; i++) {
            int x = cx + rand.nextInt(13) - 6;
            int z = cz + rand.nextInt(13) - 6;
            BlockPos p = new BlockPos(x, groundSurfaceY(sl, x, z), z);
            BlockState s = sl.getBlockState(p);
            if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)) {
                sl.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), 3);
            }
        }
    }

    private static void groundPatch(ServerLevel sl, RandomSource rand, int cx, int cz, BlockState block,
                                    int radius) {
        int px = cx + rand.nextInt(7) - 3;
        int pz = cz + rand.nextInt(7) - 3;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) continue;
                if (dx * dx + dz * dz >= radius * radius && rand.nextBoolean()) continue;
                int x = px + dx;
                int z = pz + dz;
                BlockPos p = new BlockPos(x, groundSurfaceY(sl, x, z), z);
                if (isReplaceableGround(sl.getBlockState(p))) sl.setBlock(p, block, 3);
            }
        }
    }

    private static void shortGrass(ServerLevel sl, RandomSource rand, int cx, int cz, int count) {
        for (int i = 0; i < count; i++) {
            int x = cx + rand.nextInt(13) - 6;
            int z = cz + rand.nextInt(13) - 6;
            BlockPos ground = new BlockPos(x, groundSurfaceY(sl, x, z), z);
            BlockPos above = ground.above();
            if (sl.getBlockState(above).isAir() && sl.getBlockState(ground).is(Blocks.GRASS_BLOCK)) {
                sl.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 3);
            }
        }
    }

    private static boolean isReplaceableGround(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.DIRT_PATH) || s.is(Blocks.MUD) || s.is(Blocks.PODZOL);
    }
}
