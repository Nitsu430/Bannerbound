package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * The hunter's two "nobody is watching" behaviours, polled from CitizenEntity.aiStep every 20
 * ticks for hunter-job citizens (server side).
 *
 * <p>Passive yield: while no player is near (isAiActive() false -- the activation tier that idles
 * the real stalk/chase AI), the hunter keeps producing as if it were out hunting. On a randomized
 * cadence (BASE + rand(BASE), roughly one to two minutes to mirror a real hunt's kill pace) during
 * work hours (dawn to the pre-dusk social window at 10100) it picks a huntable species weighted by
 * what actually spawns in the wild band's biome (a hunter beside a jungle yields jungle game),
 * rolls that animal's death loot table against a transient never-added carcass, filters it through
 * the settlement known-set, and inserts it into the drop-off. No animal is killed -- it's the same
 * simulation-over-simulation trade the caravan system makes. Deposited meat feeds the town via the
 * larder, not a live status bonus (COOKING_PLAN.md Part 1). The moment a player wanders close
 * isAiActive() flips and the real HunterWorkGoal takes over seamlessly (this ticker goes dormant).
 *
 * <p>Dusk teleport home: hunters trip farther out than any other worker, so a day's hunt can end a
 * long walk from bed. During the dusk window (the social-window cutoff at 10100 until citizens are
 * in bed at 13000) a hunter still far outside the claims is teleported to the chunk adjacent to the
 * town hall's, on the side it was returning from -- reads as emerging from the wilds, close enough
 * to stroll in, socialize and sleep without an hours-long trudge or a night stranded outdoors.
 * Only rescues hunters stranded OUTSIDE the claims; inside them the normal walk home is short.
 */
@ApiStatus.Internal
public final class HunterOffscreenTicker {
    private static final int PASSIVE_INTERVAL_BASE_TICKS = 1600;
    private static final int BAND_SAMPLE_RADIUS = 80;
    private static final long WORK_END_DAYTIME = 10_100L;
    private static final long DUSK_TELEPORT_FROM = 10_100L;
    private static final long DUSK_TELEPORT_UNTIL = 13_000L;
    private static final double FAR_FROM_HOME_SQ = 64.0 * 64.0;

    private static final String NEXT_YIELD_TAG = "HunterPassiveNext";

    private HunterOffscreenTicker() {
    }

    public static void tick(CitizenEntity citizen, ServerLevel sl) {
        long dayTime = sl.getDayTime() % 24_000L;
        if (dayTime >= DUSK_TELEPORT_FROM && dayTime < DUSK_TELEPORT_UNTIL) {
            maybeTeleportHome(citizen, sl);
            return;
        }
        if (dayTime < WORK_END_DAYTIME) {
            maybePassiveYield(citizen, sl);
        }
    }

    private static void maybePassiveYield(CitizenEntity citizen, ServerLevel sl) {
        if (citizen.isAiActive()) return;
        if (!citizen.isGatherJobReady(HunterWorkGoal.JOB_TYPE_ID)) return;
        if (citizen.isStaminaExhausted() || citizen.isPregnant() || citizen.isChild()) return;

        long now = sl.getGameTime();
        var data = citizen.getPersistentData();
        if (!data.contains(NEXT_YIELD_TAG)) {
            data.putLong(NEXT_YIELD_TAG, now + rollInterval(citizen));
            return;
        }
        if (now < data.getLong(NEXT_YIELD_TAG)) return;

        Container depot = DropOffContainers.resolveJobDepot(citizen);
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) return;

        EntityType<?> prey = pickBiomePrey(citizen, sl);
        data.putLong(NEXT_YIELD_TAG, now + rollInterval(citizen));
        if (prey == null) return;

        List<ItemStack> drops = rollLoot(sl, citizen, prey);
        SettlementDropFilter.filterStacks(citizen.getSettlement(),
            BuiltInRegistries.ENTITY_TYPE.getKey(prey), drops);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            sl.getServer(), citizen.getSettlement(), "kill_entity",
            com.bannerbound.core.api.research.InsightManager.matcherFor(prey), 1);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.consumeStamina(8);   // matches a real kill: HunterWorkGoal.STAMINA_PER_KILL
    }

    private static boolean insertedSome(ItemStack original, ItemStack remainder) {
        if (original.isEmpty()) return false;
        return remainder.isEmpty() || remainder.getCount() < original.getCount();
    }

    private static long rollInterval(CitizenEntity citizen) {
        return PASSIVE_INTERVAL_BASE_TICKS + citizen.getRandom().nextInt(PASSIVE_INTERVAL_BASE_TICKS);
    }

    private static EntityType<?> pickBiomePrey(CitizenEntity citizen, ServerLevel sl) {
        BlockPos sample = sampleBandPoint(citizen, sl);
        if (sample == null) sample = citizen.blockPosition();
        List<MobSpawnSettings.SpawnerData> candidates = new ArrayList<>();
        int totalWeight = 0;
        for (MobSpawnSettings.SpawnerData d
                : sl.getBiome(sample).value().getMobSettings().getMobs(MobCategory.CREATURE).unwrap()) {
            if (!d.type.is(HunterWorkGoal.HUNTABLE_TAG)) continue;
            if (!citizen.isHunterPreyEnabled(d.type)) continue;
            candidates.add(d);
            totalWeight += d.getWeight().asInt();
        }
        if (candidates.isEmpty() || totalWeight <= 0) return null;
        int roll = citizen.getRandom().nextInt(totalWeight);
        for (MobSpawnSettings.SpawnerData d : candidates) {
            roll -= d.getWeight().asInt();
            if (roll < 0) return d.type;
        }
        return candidates.get(candidates.size() - 1).type;
    }

    private static BlockPos sampleBandPoint(CitizenEntity citizen, ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        BlockPos anchor = citizen.getDropOff() != null ? citizen.getDropOff() : citizen.blockPosition();
        if (settlement == null) return null;
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = anchor.getX() + citizen.getRandom().nextInt(BAND_SAMPLE_RADIUS * 2 + 1) - BAND_SAMPLE_RADIUS;
            int z = anchor.getZ() + citizen.getRandom().nextInt(BAND_SAMPLE_RADIUS * 2 + 1) - BAND_SAMPLE_RADIUS;
            if (!sl.isLoaded(new BlockPos(x, anchor.getY(), z))) continue;   // never getHeight/getBiome an unloaded chunk -> force-load
            if (SettlementData.get(sl).getByChunk(ChunkPos.asLong(x >> 4, z >> 4)) != null) continue;
            return new BlockPos(x, sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        }
        return null;
    }

    private static List<ItemStack> rollLoot(ServerLevel sl, CitizenEntity citizen, EntityType<?> prey) {
        List<ItemStack> out = new ArrayList<>();
        Entity carcass = prey.create(sl);
        if (carcass == null) return out;
        try {
            carcass.moveTo(citizen.getX(), citizen.getY(), citizen.getZ(), 0.0F, 0.0F);
            ResourceKey<LootTable> key = prey.getDefaultLootTable();
            LootTable table = sl.getServer().reloadableRegistries().getLootTable(key);
            LootParams params = new LootParams.Builder(sl)
                .withParameter(LootContextParams.THIS_ENTITY, carcass)
                .withParameter(LootContextParams.ORIGIN, carcass.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, sl.damageSources().generic())
                .create(LootContextParamSets.ENTITY);
            out.addAll(table.getRandomItems(params));
        } finally {
            carcass.discard();
        }
        return out;
    }

    private static void maybeTeleportHome(CitizenEntity citizen, ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !settlement.hasTownHall()) return;
        BlockPos th = settlement.townHallPos();
        if (citizen.distanceToSqr(th.getX() + 0.5, th.getY(), th.getZ() + 0.5) <= FAR_FROM_HOME_SQ) return;
        if (SettlementData.get(sl).getByChunk(new ChunkPos(citizen.blockPosition()).toLong()) != null) return;

        ChunkPos home = new ChunkPos(th);
        int dx = Integer.signum(citizen.blockPosition().getX() - th.getX());
        int dz = Integer.signum(citizen.blockPosition().getZ() - th.getZ());
        if (dx == 0 && dz == 0) dx = 1;
        ChunkPos target = new ChunkPos(home.x + dx, home.z + dz);
        int x = target.getMiddleBlockX();
        int z = target.getMiddleBlockZ();
        int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        citizen.getNavigation().stop();
        citizen.teleportTo(x + 0.5, y, z + 0.5);
        CitizenEntity.tagDeliberateTeleport(citizen);   // else a rope near the landing bounces him back
        citizen.setYRot(Mth.wrapDegrees((float) Math.toDegrees(
            Math.atan2(th.getZ() + 0.5 - z, th.getX() + 0.5 - x)) - 90.0F));
    }
}
