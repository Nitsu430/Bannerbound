package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.bannerbound.core.building.PenEnclosure;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

/**
 * Breeding is a roll, not a guarantee - for ALL animal breeding (player-fed or herder-fed). Hooks
 * BabyEntitySpawnEvent: on success the baby spawns as normal (hearts); on failure the event is
 * cancelled (no baby, smoke instead) while the parents still take their breeding cooldown (setAge +
 * resetLove) so a failed attempt isn't free.
 *
 * The chance starts at Config.HERDER_BASE_BREED_CHANCE, adjusted by the ground the pair stands ON and
 * nearby water (same rule everywhere, so a grassy watered spot breeds best and a dry sandy/gravelly one
 * worst), and docked per nearby manure block so an unmucked pen breeds badly until a herder clears it.
 * The herder pen is not special-cased: an infertile pen just breeds poorly. Fertile/infertile ground
 * and manure are recognised by tag (fertile_breeding_ground / infertile_breeding_ground / manure) so
 * the lists are data-tunable and addons can contribute; Core ships the manure tag empty and Antiquity
 * populates it. breedChance/penBreedQuality/groundModifier are public and side-agnostic so the herder
 * pen UI and floating marker can preview a spot's odds client-side. All block scans are chunk-guarded
 * (hasChunkAt) so they never force-load a chunk.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class BreedingEvents {
    private static final TagKey<Block> FERTILE_GROUND = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "fertile_breeding_ground"));
    private static final TagKey<Block> INFERTILE_GROUND = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "infertile_breeding_ground"));
    public static final TagKey<Block> MANURE = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "manure"));

    private BreedingEvents() {
    }

    @SubscribeEvent
    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        if (!(event.getParentA() instanceof Animal a) || event.getChild() == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        Animal b = event.getParentB() instanceof Animal pb ? pb : null;

        if (sl.getRandom().nextDouble() < breedChance(sl, a.blockPosition())) return;

        event.setCanceled(true);
        a.setAge(6000);
        a.resetLove();
        if (b != null) { b.setAge(6000); b.resetLove(); }
        puffSmoke(sl, a);
        if (b != null) puffSmoke(sl, b);
    }

    public static double breedChance(Level level, BlockPos breedPos) {
        double chance = Config.HERDER_BASE_BREED_CHANCE.get()
            + groundModifier(level.getBlockState(breedPos.below()));
        if (waterNear(level, breedPos, Config.BREED_WATER_RADIUS.get())) {
            chance += Config.BREED_WATER_BONUS.get();
        }
        chance -= manureCount(level, breedPos, Config.BREED_MANURE_RADIUS.get())
            * Config.BREED_MANURE_PENALTY.get();
        return clamp(chance);
    }

    public static double penBreedQuality(Level level, PenEnclosure.Result r) {
        int land = 0;
        int manure = 0;
        double groundSum = 0.0;
        boolean water = false;
        for (BlockPos c : r.interior()) {
            BlockState s = level.getBlockState(c);
            if (s.getFluidState().is(FluidTags.WATER)) { water = true; continue; }
            if (s.blocksMotion() && !level.getBlockState(c.above()).blocksMotion()) {
                land++;
                groundSum += groundModifier(s);
            }
            if (level.getBlockState(c.above()).is(MANURE)) manure++;
        }
        double avgGround = land > 0 ? groundSum / land : 0.0;
        if (!water) {
            BlockPos centre = new BlockPos((r.min().getX() + r.max().getX()) / 2, r.min().getY(),
                (r.min().getZ() + r.max().getZ()) / 2);
            water = waterNear(level, centre, Config.BREED_WATER_RADIUS.get());
        }
        return clamp(Config.HERDER_BASE_BREED_CHANCE.get() + avgGround
            + (water ? Config.BREED_WATER_BONUS.get() : 0.0)
            - manure * Config.BREED_MANURE_PENALTY.get());
    }

    public static int manureCount(Level level, BlockPos pos, int radius) {
        if (radius <= 0) return 0;
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.hasChunkAt(p)) continue;   // chunk-guard: never force-load to scan
                    if (level.getBlockState(p).is(MANURE)) count++;
                }
            }
        }
        return count;
    }

    public static double groundModifier(BlockState floor) {
        if (floor.is(FERTILE_GROUND)) return Config.BREED_GRASS_BONUS.get();
        if (floor.is(INFERTILE_GROUND)) return -Config.BREED_INFERTILE_PENALTY.get();
        return 0.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static boolean waterNear(Level level, BlockPos pos, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.hasChunkAt(p)) continue;   // chunk-guard: never force-load to scan
                    if (level.getFluidState(p).is(FluidTags.WATER)) return true;
                }
            }
        }
        return false;
    }

    private static void puffSmoke(ServerLevel sl, Animal animal) {
        sl.sendParticles(ParticleTypes.SMOKE,
            animal.getX(), animal.getY() + animal.getBbHeight() * 0.5, animal.getZ(),
            10, 0.3, 0.3, 0.3, 0.02);
    }
}
