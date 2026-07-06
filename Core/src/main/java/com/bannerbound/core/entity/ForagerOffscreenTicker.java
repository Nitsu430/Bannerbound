package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.forager.ForageCategory;
import com.bannerbound.core.api.forager.ForagerHooks;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.data.FoodValueLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The forager's "nobody is watching" behaviours, polled from CitizenEntity.aiStep every 20 ticks
 * (forager-job citizens, server side) -- the direct counterpart to HunterOffscreenTicker.
 *
 * <p>Passive yield: when no player is near (isAiActive() is false -- the activation tier that idles
 * the real roam/gather AI), the forager keeps producing as if it were out in the band. On a
 * randomized cadence (PASSIVE_INTERVAL_BASE_TICKS + rand(BASE), deliberately conservative so a long
 * absence doesn't flood the drop-off) it picks one enabled-and-unlocked ForageCategory and produces
 * that category's canonical yield -- berries, a random flower/mushroom from SMALL_FLOWERS/TALL_FLOWERS,
 * or the scavenging fiber + wheat-seed raws via ForagerHooks -- filters it through the settlement
 * known-set like every worker, and inserts it into the drop-off. No block is actually broken: the
 * same simulation-over-simulation trade HunterOffscreenTicker makes. Unlike the hunter (biome-weighted
 * prey), there is no flora-spawn API, so yields are canonical-per-category, not biome-faithful. The
 * first idle tick schedules the first yield a full interval out (no instant loot when the player walks
 * away); each yield costs one stamina like a real harvest. Deposited forage feeds the town via the
 * larder, not a live status bonus (COOKING_PLAN.md Part 1). When a player wanders close isAiActive()
 * flips and the real ForagerWorkGoal takes over, leaving this ticker dormant.
 *
 * <p>Yield rolls run only during work hours (dawn -> the pre-dusk social window at WORK_END_DAYTIME
 * 10100), matching HunterOffscreenTicker. Dusk teleport home: foragers roam up to a 64-block leash
 * from their drop-off, so a day's gathering can end far from bed. During the dusk window (10100 until
 * citizens are abed at 13000), if the forager is beyond FAR_FROM_HOME_SQ from the town hall AND still
 * outside all claims, it is teleported to the chunk adjacent to the town hall on the side it was
 * returning from, rather than trudge home (or stay stranded overnight while inactive).
 */
@ApiStatus.Internal
public final class ForagerOffscreenTicker {
    private static final int PASSIVE_INTERVAL_BASE_TICKS = 600;
    private static final long WORK_END_DAYTIME = 10_100L;
    private static final long DUSK_TELEPORT_FROM = 10_100L;
    private static final long DUSK_TELEPORT_UNTIL = 13_000L;
    private static final double FAR_FROM_HOME_SQ = 64.0 * 64.0;

    private static final String NEXT_YIELD_TAG = "ForagerPassiveNext";

    private static final Item[] SMALL_FLOWERS = {
        Items.DANDELION, Items.POPPY, Items.AZURE_BLUET, Items.OXEYE_DAISY, Items.CORNFLOWER };
    private static final Item[] TALL_FLOWERS = {
        Items.SUNFLOWER, Items.LILAC, Items.ROSE_BUSH, Items.PEONY };

    private ForagerOffscreenTicker() {
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
        if (!citizen.isForagerReady()) return;
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

        ForageCategory cat = pickActiveCategory(citizen);
        data.putLong(NEXT_YIELD_TAG, now + rollInterval(citizen));
        if (cat == null) return;

        List<ItemStack> drops = passiveYieldFor(cat, sl, citizen.getRandom());
        if (drops.isEmpty()) return;
        SettlementDropFilter.filterStacks(citizen.getSettlement(), null, drops);

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.consumeStamina(1);
    }

    private static List<ItemStack> passiveYieldFor(ForageCategory cat, ServerLevel sl, RandomSource rng) {
        List<ItemStack> out = new ArrayList<>(2);
        switch (cat) {
            case BERRIES -> out.add(new ItemStack(Items.SWEET_BERRIES, 1 + rng.nextInt(2)));
            case SMALL_FLOWERS -> out.add(new ItemStack(SMALL_FLOWERS[rng.nextInt(SMALL_FLOWERS.length)]));
            case TALL_FLOWERS -> out.add(new ItemStack(TALL_FLOWERS[rng.nextInt(TALL_FLOWERS.length)]));
            case MUSHROOMS -> out.add(new ItemStack(rng.nextBoolean() ? Items.RED_MUSHROOM : Items.BROWN_MUSHROOM));
            case VINES -> out.add(new ItemStack(Items.VINE));
            case GRASS -> out.add(new ItemStack(Items.SHORT_GRASS));
            case LEAVES -> out.add(new ItemStack(Items.OAK_LEAVES));
            case STICKS_FIBERS -> {
                BlockState rep = rng.nextFloat() < 0.7f
                    ? Blocks.SHORT_GRASS.defaultBlockState()
                    : Blocks.OAK_LEAVES.defaultBlockState();
                out.addAll(ForagerHooks.scavenge(sl, rep, rng));
            }
        }
        return out;
    }

    private static ForageCategory pickActiveCategory(CitizenEntity citizen) {
        Settlement settlement = citizen.getSettlement();
        int enabled = citizen.getForageTargetBits();
        List<ForageCategory> active = new ArrayList<>();
        for (ForageCategory c : ForageCategory.values()) {
            if ((enabled & c.bit()) != 0 && c.isUnlocked(settlement)) active.add(c);
        }
        if (active.isEmpty()) return null;
        return active.get(citizen.getRandom().nextInt(active.size()));
    }

    private static boolean insertedSome(ItemStack original, ItemStack remainder) {
        if (original.isEmpty()) return false;
        return remainder.isEmpty() || remainder.getCount() < original.getCount();
    }

    private static long rollInterval(CitizenEntity citizen) {
        return PASSIVE_INTERVAL_BASE_TICKS + citizen.getRandom().nextInt(PASSIVE_INTERVAL_BASE_TICKS);
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
        CitizenEntity.tagDeliberateTeleport(citizen); // else a rope near the landing bounces her back
        citizen.setYRot(Mth.wrapDegrees((float) Math.toDegrees(
            Math.atan2(th.getZ() + 0.5 - z, th.getX() + 0.5 - x)) - 90.0F));
    }
}
