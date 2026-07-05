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
 * Herding-related event handlers (merged from HerdingEvents, HerdingEvents, HerdingEvents).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class HerdingEvents {
    private HerdingEvents() {}

    /*
     * Feeding an untameable livestock animal (cow, sheep, pig, chicken, …) its favourite food once
     * <b>tames</b> it: from then on it reverts to vanilla behaviour — it no longer flees the player, drops
     * no footprints, and is exempt from the hunting fear/bleed (see {@link HuntingFear#isTamed}). The
     * {@code TAMED_LIVESTOCK} flag is serialized, so it sticks for that animal forever.
     *
     * <p>Pets/mounts that have their own vanilla taming (wolves, cats, horses) and predators (ocelots)
     * are left to their own rules. Runs at {@link EventPriority#HIGHEST} so taming still happens even when
     * Core's {@code VanillaGates} later cancels the breeding interaction for an un-researched player
     * — calming an animal is separate from breeding it.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onFeed(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getTarget() instanceof Animal animal)
                || animal instanceof TamableAnimal || animal instanceof AbstractHorse
                || animal instanceof Ocelot) {
            return; // only untameable livestock; pets/mounts/predators keep their own rules
        }
        if (HuntingFear.isTamed(animal)) {
            return; // already tamed — don't re-burst hearts
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !animal.isFood(stack)) {
            return; // must be its favourite food
        }
        HuntingFear.setTamed(animal);
        if (animal.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART,
                animal.getX(), animal.getY() + animal.getBbHeight() * 0.6, animal.getZ(),
                7, animal.getBbWidth(), 0.5, animal.getBbWidth(), 0.0);
        }
    }

    /*
     * Every so often a domesticated animal in a herder pen drops a {@link BannerboundAntiquity#MANURE manure}
     * pat on the floor near it. Manure fouls the pen's fertility (Core's {@code BreedingEvents}) until it's
     * cleared — by a herder mucking out (pen upkeep) or the player (yielding {@code dung}). A pen self-limits
     * to a cap so it never carpets in manure if left untended.
     *
     * <p>Mirrors {@link com.bannerbound.core.entity.HerderFoodBonus}'s pen walk (settlements → herder pen
     * markers → {@link PenEnclosure} → animals inside), but on a slow {@link ServerTickEvent.Post} cadence and
     * chunk-guarded so it never force-loads a far pen.
     */
    /** How often the pass runs (server ticks). 600 = every 30 s — manure is meant to be occasional. */
    private static final int INTERVAL_TICKS = 600;
    /** Per-animal chance, each pass, to drop a pat. ~0.7 over the 30 s cadence → a cow fouls its spot
     *  roughly every ~40 s (kept in line with the old 5 s × 0.12 rate, just checked less often). */
    private static final double POOP_CHANCE = 0.7;
    /** How close to the animal a pat lands (interior floor cells within this horizontal distance). */
    private static final double DROP_RADIUS = 2.5;

    private static int tickCounter;

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % INTERVAL_TICKS != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();   // settlements (and their pens) live in the overworld
        BlockSelectionRegistry reg = BlockSelectionRegistry.get(level);
        for (Settlement s : SettlementData.get(level).all()) {
            // Don't foul a DORMANT settlement's pens: force-loaded claims keep this pass running
            // while every member is offline (mirrors FoodSpoilageEvents' dormancy guard).
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
        // Self-limit: a pen tops out at ~1 pat per 8 floor cells so an untended pen doesn't carpet over.
        int cap = Math.max(1, r.interior().size() / 8);
        if (countManure(level, r) >= cap) return;

        for (Animal a : level.getEntitiesOfClass(Animal.class, r.bounds().inflate(1.0, 2.0, 1.0),
                a -> a.isAlive() && a.getType() == type
                    && a.getPersistentData().getBoolean(HerderWorkGoal.DOMESTICATED_TAG))) {
            if (rng.nextDouble() >= POOP_CHANCE) continue;
            BlockPos floor = pickDropFloor(level, r, a, rng);
            if (floor == null) continue;
            level.setBlockAndUpdate(floor.above(), BannerboundAntiquity.MANURE.get().defaultBlockState());
            if (countManure(level, r) >= cap) return;   // respect the cap as the pass deposits
        }
    }

    /** An interior floor cell near {@code a} whose air cell is free for a pat (solid floor, empty above,
     *  no water, not already manure). Null if none qualify. */
    private static BlockPos pickDropFloor(ServerLevel level, PenEnclosure.Result r, Animal a, RandomSource rng) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos c : r.interior()) {
            double dx = (c.getX() + 0.5) - a.getX();
            double dz = (c.getZ() + 0.5) - a.getZ();
            if (dx * dx + dz * dz > DROP_RADIUS * DROP_RADIUS) continue;
            BlockState floor = level.getBlockState(c);
            if (!floor.blocksMotion() || !floor.getFluidState().isEmpty()) continue;   // need a dry solid floor
            BlockState above = level.getBlockState(c.above());
            if (!above.isAir()) continue;   // occupied (water, another pat, a block) → skip
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

    /*
     * Leashing animals with a fiber rope — exactly the vanilla lead, just with our cordage and (via
     * {@code MobRendererMixin} + {@link com.bannerbound.antiquity.client.RopeRenderEvents}) the green
     * rope colour. A plain (non-shift) {@code #bannerbound:herder_rope} click on a leashable {@link Animal}
     * calls vanilla {@code Mob.setLeashedTo} — so vanilla's own {@code tickLeash} handles the follow/pull,
     * the elastic snap, the too-far break, and save/load persistence for free. Re-clicking your own animal
     * drops the leash; clicking a fence post while leading ties them to a knot (see {@link #tieLedAnimalsToFence}).
     *
     * <p>Shift is left to {@code FiberRopeItem}'s spear-reel, and curare-unconscious targets are left to the
     * {@link PoisonEvents} kidnap drag — so the three rope interactions never collide.</p>
     */
    /** Research flag (granted by Animal Husbandry, alongside {@code allow_animal_breeding}) that lets a
     *  settlement leash animals — domestication is the point where you learn to lead a beast. */
    public static final String FLAG = "bannerbound.allow_leashing";
    /** How far from a fence a led animal can be and still get tied to it (vanilla lead uses ~7). */
    private static final double TIE_RANGE = 7.0;

    /** Grab/release: a plain rope click on a leashable animal leashes it to you (or, if it's already
     *  yours, lets it go). Mirrors vanilla lead-on-mob. */
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
                    animal.dropLeash(true, false); // re-click my own → let go (rope never left the hand)
                    player.swing(event.getHand());
                } else {
                    return; // held by someone else / tied to a fence — leave it
                }
            } else if (!leashingUnlocked(player)) {
                // Leashing is gated behind Animal Husbandry — tell them why, like the breeding gate.
                if (player instanceof ServerPlayer sp) {
                    sp.sendSystemMessage(Component.translatable("bannerbound.feature.cant_do_yet")
                        .withStyle(ChatFormatting.RED));
                }
            } else if (animal.canBeLeashed()) {
                animal.setLeashedTo(player, true);
                player.swing(event.getHand());
            } else {
                return; // this mob refuses a leash (vanilla rule)
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    /** Whether {@code player}'s settlement has researched leashing (the {@link #FLAG}, from Animal
     *  Husbandry). Server-only; mirrors {@code SpearFishing.unlocked} / {@code VanillaGates}. */
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
            return false; // no settlement / not loaded → treat as not unlocked
        }
    }

    /** True if the player is currently leading at least one animal within tying range of {@code pos}
     *  — lets the rope-post interaction prefer "tie my animals here" over starting a post-to-post tie
     *  (works on both sides, since the leash holder is synced to the client). */
    public static boolean hasLedAnimalsNear(Player player, BlockPos pos) {
        return !ledAnimalsNear(player, pos).isEmpty();
    }

    /** Tie every animal the player is leading to a fence knot at {@code pos} (vanilla lead-to-fence).
     *  Server-only; returns true if at least one was tied. */
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
