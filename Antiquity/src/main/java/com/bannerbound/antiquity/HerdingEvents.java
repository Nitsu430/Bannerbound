package com.bannerbound.antiquity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.HuntingFear;
import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.entity.HerderWorkGoal;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Herding event handlers: taming, pen manure, and fiber-rope leashing for Antiquity livestock.
 *
 * <p>TAMING ({@link #onFeed}): feeding an untameable livestock animal (cow/sheep/pig/chicken/...) its
 * favourite food tames it, after which it reverts to vanilla behaviour -- no fleeing, no footprints,
 * exempt from the hunting fear/bleed (see {@link HuntingFear#isTamed}). The TAMED_LIVESTOCK flag is
 * serialized, so it sticks forever. Pets/mounts with their own vanilla taming (wolves, cats, horses)
 * and predators (ocelots) keep their own rules. Runs at HIGHEST so taming still happens when Core's
 * VanillaGates later cancels the breeding interaction for an un-researched player -- calming an animal
 * is separate from breeding it.
 *
 * <p>MANURE ({@link #onServerTick} -> {@link #foulPen}): every 30s a domesticated animal in a herder
 * pen may drop a MANURE pat nearby, which fouls the pen's fertility (Core's BreedingEvents) until it
 * is cleared by a herder mucking out or the player (yielding dung). A pen self-limits to ~1 pat per 8
 * floor cells so an untended pen never carpets over. Mirrors HerderFoodBonus's pen walk (settlements
 * -> herder markers -> {@link PenEnclosure} -> animals inside) on a slow ServerTickEvent.Post cadence,
 * chunk-guarded so it never force-loads a far pen.
 *
 * <p>LEASHING ({@link #onLeash}, {@link #tieLedAnimalsToFence}): a plain (non-shift) herder_rope click
 * on a leashable Animal is exactly the vanilla lead with our cordage and (via MobRendererMixin +
 * RopeRenderEvents) a green rope colour -- it calls vanilla Mob.setLeashedTo, so vanilla's tickLeash
 * handles the follow/pull, elastic snap, too-far break, and save/load for free. Re-clicking your own
 * animal drops the leash; clicking a fence post while leading ties them to a knot. Gated behind the
 * {@link #FLAG} research (Animal Husbandry, alongside allow_animal_breeding). Shift is FiberRopeItem's
 * spear-reel and curare-unconscious targets are the PoisonEvents kidnap drag, so the three rope
 * interactions never collide.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class HerdingEvents {
    private HerdingEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onFeed(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getTarget() instanceof Animal animal)
                || animal instanceof TamableAnimal || animal instanceof AbstractHorse
                || animal instanceof Ocelot) {
            return;
        }
        if (HuntingFear.isTamed(animal)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !animal.isFood(stack)) {
            return;
        }
        HuntingFear.setTamed(animal);
        if (animal.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART,
                animal.getX(), animal.getY() + animal.getBbHeight() * 0.6, animal.getZ(),
                7, animal.getBbWidth(), 0.5, animal.getBbWidth(), 0.0);
        }
    }

    private static final int INTERVAL_TICKS = 600;
    private static final double POOP_CHANCE = 0.7;
    private static final double DROP_RADIUS = 2.5;

    private static int tickCounter;

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % INTERVAL_TICKS != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        BlockSelectionRegistry reg = BlockSelectionRegistry.get(level);
        for (Settlement s : SettlementData.get(level).all()) {
            // Skip dormant settlements: their claims stay force-loaded while every member is offline (mirrors FoodSpoilageEvents' dormancy guard).
            if (s.isDormant()) continue;
            for (BlockSelection sel : reg.getForSettlement(s.id())) {
                if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
                if (!HerderWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
                EntityType<? extends Animal> type = HerderWorkGoal.animalFromMarker(sel);
                if (type == null) continue;
                BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
                if (!level.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;
                PenEnclosure.Result r = PenEnclosure.scan(level, anchor);
                if (!r.valid()) continue;
                foulPen(level, r, type);
            }
        }
    }

    private static void foulPen(ServerLevel level, PenEnclosure.Result r, EntityType<? extends Animal> type) {
        RandomSource rng = level.getRandom();
        int cap = Math.max(1, r.interior().size() / 8);
        if (countManure(level, r) >= cap) return;

        for (Animal a : level.getEntitiesOfClass(Animal.class, r.bounds().inflate(1.0, 2.0, 1.0),
                a -> a.isAlive() && a.getType() == type
                    && a.getPersistentData().getBoolean(HerderWorkGoal.DOMESTICATED_TAG))) {
            if (rng.nextDouble() >= POOP_CHANCE) continue;
            BlockPos floor = pickDropFloor(level, r, a, rng);
            if (floor == null) continue;
            level.setBlockAndUpdate(floor.above(), BannerboundAntiquity.MANURE.get().defaultBlockState());
            if (countManure(level, r) >= cap) return;
        }
    }

    private static BlockPos pickDropFloor(ServerLevel level, PenEnclosure.Result r, Animal a, RandomSource rng) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos c : r.interior()) {
            double dx = (c.getX() + 0.5) - a.getX();
            double dz = (c.getZ() + 0.5) - a.getZ();
            if (dx * dx + dz * dz > DROP_RADIUS * DROP_RADIUS) continue;
            BlockState floor = level.getBlockState(c);
            if (!floor.blocksMotion() || !floor.getFluidState().isEmpty()) continue;
            BlockState above = level.getBlockState(c.above());
            if (!above.isAir()) continue;
            candidates.add(c.immutable());
        }
        return candidates.isEmpty() ? null : candidates.get(rng.nextInt(candidates.size()));
    }

    private static int countManure(ServerLevel level, PenEnclosure.Result r) {
        int n = 0;
        for (BlockPos c : r.interior()) {
            if (level.getBlockState(c.above()).is(com.bannerbound.core.entity.BreedingEvents.MANURE)) n++;
        }
        return n;
    }

    public static final String FLAG = "bannerbound.allow_leashing";
    private static final double TIE_RANGE = 7.0;

    @SubscribeEvent
    static void onLeash(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.isShiftKeyDown()
            || !HerderWorkGoal.isRope(event.getItemStack())
            || !(event.getTarget() instanceof Animal animal)) {
            return;
        }
        // Curare-unconscious targets belong to the kidnap drag, not leashing.
        if (Poisons.isCurareUnconscious(animal, animal.level().getGameTime())) {
            return;
        }
        if (!player.level().isClientSide) {
            if (animal.isLeashed()) {
                if (animal.getLeashHolder() == player) {
                    animal.dropLeash(true, false);
                    player.swing(event.getHand());
                } else {
                    return;
                }
            } else if (!leashingUnlocked(player)) {
                if (player instanceof ServerPlayer sp) {
                    sp.sendSystemMessage(Component.translatable("bannerbound.feature.cant_do_yet")
                        .withStyle(ChatFormatting.RED));
                }
            } else if (animal.canBeLeashed()) {
                animal.setLeashedTo(player, true);
                player.swing(event.getHand());
            } else {
                return;
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    public static boolean leashingUnlocked(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return false;
        }
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(serverPlayer.getUUID());
            return ResearchManager.hasFlag(settlement, FLAG);
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean hasLedAnimalsNear(Player player, BlockPos pos) {
        return !ledAnimalsNear(player, pos).isEmpty();
    }

    public static boolean tieLedAnimalsToFence(Player player, Level level, BlockPos pos) {
        if (level.isClientSide) {
            return false;
        }
        List<Animal> led = ledAnimalsNear(player, pos);
        if (led.isEmpty()) {
            return false;
        }
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(level, pos);
        for (Animal animal : led) {
            animal.setLeashedTo(knot, true);
        }
        return true;
    }

    private static List<Animal> ledAnimalsNear(Player player, BlockPos pos) {
        return player.level().getEntitiesOfClass(Animal.class,
            new AABB(pos).inflate(TIE_RANGE),
            a -> a.isLeashed() && a.getLeashHolder() == player);
    }
}
