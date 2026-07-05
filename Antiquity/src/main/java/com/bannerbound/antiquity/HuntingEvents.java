package com.bannerbound.antiquity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.BoarChargeGoal;
import com.bannerbound.antiquity.entity.CitizenHostilityTargetGoal;
import com.bannerbound.antiquity.entity.FleeFromPlayerGoal;
import com.bannerbound.antiquity.entity.GroundDecalEntity;
import com.bannerbound.antiquity.entity.HerdFleeGoal;
import com.bannerbound.antiquity.entity.HuntingFear;
import com.bannerbound.antiquity.entity.PlayerHostilityTargetGoal;
import com.bannerbound.antiquity.entity.SpearProjectile;
import com.bannerbound.antiquity.entity.SpearedFishEntity;
import com.bannerbound.antiquity.entity.StuckSpear;
import com.bannerbound.antiquity.item.HideQuality;
import com.bannerbound.antiquity.item.PoisonedFoodData;
import com.bannerbound.antiquity.poison.PoisonState;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.antiquity.tannery.HideGrading;
import com.bannerbound.antiquity.tannery.Hides;
import com.bannerbound.antiquity.tannery.WeaponCategory;
import com.bannerbound.core.entity.BreedingEvents;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Merged hunting, animal-drops, and spear event subscribers (doc consolidation pass pending). */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class HuntingEvents {

    // ------------------------------------------------------------------
    // From HuntingEvents
    // ------------------------------------------------------------------

    /*
     * Per-tick + on-hurt hunting logic for vanilla prey animals:
     * <ul>
     *   <li><b>Stamina</b> (persistence hunting): drains while the animal is actively fleeing, regens
     *       when calm; below the tired threshold the flee goal slows it (see {@code FleeFromPlayerGoal}),
     *       so you can run a tiring animal down.</li>
     *   <li><b>Hurt → fear</b>: a player hitting a prey animal spooks it and alarms its herd (and elects
     *       a boar-charger for pigs) — so wounding one scatters the rest.</li>
     *   <li>(Bleeding DoT is added here in Part 7.)</li>
     * </ul>
     */
    private HuntingEvents() {}

    @SubscribeEvent
    static void onAnimalTick(EntityTickEvent.Post event) {
        if (!Config.HUNTING_ENABLED.get() || !(event.getEntity() instanceof Animal animal)) {
            return;
        }
        if (!(animal.level() instanceof ServerLevel level)) {
            return;
        }
        if (HuntingFear.isTamed(animal)) {
            return; // tamed livestock are vanilla again — no stamina, bleed, or footprints
        }
        // Stamina: drain while exerting (spooked + actually moving), regenerate otherwise.
        float max = (float) (double) Config.STAMINA_MAX.get();
        float stamina = HuntingFear.getStamina(animal);
        boolean exerting = HuntingFear.isScared(animal) && !animal.getNavigation().isDone();
        if (exerting) {
            if (stamina > 0.0F) {
                HuntingFear.setStamina(animal, stamina - (float) (double) Config.STAMINA_DRAIN_PER_TICK.get());
            }
        } else if (stamina < max) {
            HuntingFear.setStamina(animal, stamina + (float) (double) Config.STAMINA_REGEN_PER_TICK.get());
        }
        // Bleeding: count down; on each interval pulse, deal damage-over-time + spit red blood.
        int bleed = animal.getData(BannerboundAntiquity.BLEED_TICKS.get());
        if (Config.BLEED_ENABLED.get() && bleed > 0) {
            int remaining = bleed - 1;
            animal.setData(BannerboundAntiquity.BLEED_TICKS.get(), remaining);
            if (remaining % Config.BLEED_INTERVAL_TICKS.get() == 0) {
                Entity owner = null;

                String causedBy = animal.getData(BannerboundAntiquity.BLEED_BY.get());

                if (!causedBy.isEmpty()) {
                    try {
                        owner = level.getEntity(java.util.UUID.fromString(causedBy));
                    } catch (IllegalArgumentException e) {
                        // Not a valid UUID; Idk when this would happen but ig yh
                    }
                }

                animal.hurt(animal.damageSources().source(BannerboundAntiquity.BLEEDING_DAMAGE, owner),
                    (float) (double) Config.BLEED_DAMAGE_PER_TICK.get());
                level.sendParticles(BannerboundAntiquity.BLOOD_DROP.get(),
                    animal.getX(), animal.getY() + animal.getBbHeight() * 0.6, animal.getZ(),
                    16, 0.15, 0.1, 0.15, 0.15);
                // Leave a fading blood decal where it's bleeding (the visible blood trail).
                if (Config.BLOOD_SPLAT_ENABLED.get()
                        && animal.getRandom().nextDouble() < Config.BLOOD_SPLAT_CHANCE.get()) {
                    // spawnBlood clamps down to the ground in this column, so it never floats.
                    GroundDecalEntity.spawnBlood(level, animal.getX(), animal.getY(), animal.getZ(),
                        animal.getRandom().nextInt(10000), animal);
                }
            }

            if (remaining == 0) {
                // clear up
                animal.removeData(BannerboundAntiquity.BLEED_BY.get());
            }
        }
        dropFootprints(level, animal);
    }

    /** Walking animals of configured species leave footprint tracks at a regular spacing. */
    private static void dropFootprints(ServerLevel level, Animal animal) {
        if (!Config.FOOTPRINTS_ENABLED.get() || !animal.onGround()) {
            return;
        }
        String species = BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath();
        if (!Config.FOOTPRINT_SPECIES.get().contains(species)) {
            return; // only animals that have a <species>_footprint.png
        }
        double moved = Math.hypot(animal.getX() - animal.xOld, animal.getZ() - animal.zOld);
        if (moved < 0.01) {
            return; // standing still
        }
        float dist = animal.getData(BannerboundAntiquity.FOOTPRINT_DIST.get()) + (float) moved;
        // Longer strides at speed → space tracks out, so a sprinting animal leaves far fewer.
        double spacing = Config.FOOTPRINT_SPACING.get() * (1.0 + Config.FOOTPRINT_SPEED_STRETCH.get() * moved);
        if (dist < spacing) {
            animal.setData(BannerboundAntiquity.FOOTPRINT_DIST.get(), dist);
            return;
        }
        animal.setData(BannerboundAntiquity.FOOTPRINT_DIST.get(), (float) (dist - spacing)); // keep remainder
        if (animal.getRandom().nextDouble() < Config.FOOTPRINT_CHANCE.get()) {
            GroundDecalEntity.spawnTrack(level, animal.getX(), animal.getY(), animal.getZ(),
                species, animal);
        }
    }

    @SubscribeEvent
    static void onPreyHurt(LivingIncomingDamageEvent event) {
        if (!Config.HUNTING_ENABLED.get()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (!(victim instanceof Animal animal) || animal instanceof Wolf || animal instanceof Ocelot) {
            return; // prey only — predators keep attacking when hit
        }
        if (!(victim.level() instanceof ServerLevel)) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        // Players and citizens both count as a hunting threat — a Hunter NPC's melee blow or
        // arrow/spear (the projectile's owner) spooks the victim and scatters its herd, exactly
        // like a player's hit. Other damage (cactus, predators) doesn't read as hunting.
        boolean playerThreat = attacker instanceof Player player
            && !player.isCreative() && !player.isSpectator();
        boolean hunterThreat = attacker instanceof com.bannerbound.core.entity.CitizenEntity;
        if (!playerThreat && !hunterThreat) {
            return;
        }
        spook(animal, Config.SCARED_DURATION_TICKS.get());
    }

    /** Breaking blocks is noisy — spook nearby prey (digging gives you away). */
    @SubscribeEvent
    static void onMiningNoise(BlockEvent.BreakEvent event) {
        if (!Config.HUNTING_ENABLED.get()) {
            return;
        }
        int radius = Config.MINING_NOISE_RADIUS.get();
        Player player = event.getPlayer();
        if (radius <= 0 || player == null || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        int ticks = Config.SCARED_DURATION_TICKS.get();
        AABB area = new AABB(event.getPos()).inflate(radius);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, area,
                a -> a.isAlive() && !(a instanceof Wolf) && !(a instanceof Ocelot))) {
            spook(animal, ticks);
        }
    }

    /** Spook an animal and, on the not-scared→scared edge, alarm its herd (+ elect a boar charger). */
    private static void spook(Animal animal, int ticks) {
        if (HuntingFear.isTamed(animal)) {
            return; // domesticated livestock don't get spooked — they're vanilla now
        }
        boolean wasScared = HuntingFear.isScared(animal);
        HuntingFear.scare(animal, ticks);
        if (!wasScared) {
            HuntingFear.alarmHerd(animal, Config.HERD_ALARM_RADIUS.get(), ticks);
            if (animal instanceof Pig pig) {
                HuntingFear.electBoarCharger(pig, Config.HERD_ALARM_RADIUS.get(),
                    Config.BOAR_CHARGE_CLAIM_TICKS.get(), Config.BOAR_CHARGE_CHANCE.get(), animal.getRandom());
            }
        }
    }

    // ------------------------------------------------------------------
    // From HuntingEvents
    // ------------------------------------------------------------------

    /*
     * Injects immersive-hunting AI into vanilla animals as they spawn/load (the
     * {@code AntiquityEvents.onCitizenJoinLevel} pattern: server-only, dedup before adding so chunk
     * reloads don't stack goals). Passive animals get flee + herd goals (pigs also get the boar charge);
     * wild wolves and ocelots become hostile.
     */
    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !Config.HUNTING_ENABLED.get()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Wolf || entity instanceof Ocelot) {
            // Predators (hostility) are handled in HuntingEvents (Part 4).
            return;
        }
        if (entity instanceof Animal animal) {
            double[] speed = speedFor(animal);
            // Pigs: boar charge at priority 0 so the elected charger PREEMPTS the flee goal (same
            // priority wouldn't — flee grabs MOVE on the scared edge and holds it, so the pig would
            // just flee). Non-chargers have no claim, so the charge yields and they flee.
            if (animal instanceof Pig pig) {
                addGoalOnce(pig.goalSelector, 0, new BoarChargeGoal(pig), BoarChargeGoal.class);
            }
            addGoalOnce(animal.goalSelector, 1,
                new FleeFromPlayerGoal(animal, speed[0], speed[1]), FleeFromPlayerGoal.class);
            addGoalOnce(animal.goalSelector, 1,
                new com.bannerbound.antiquity.entity.FleeFromHunterGoal(animal, speed[0], speed[1]),
                com.bannerbound.antiquity.entity.FleeFromHunterGoal.class);
            addGoalOnce(animal.goalSelector, 1,
                new HerdFleeGoal(animal, speed[1]), HerdFleeGoal.class);
        }
    }

    /** {walk, sprint} flee multipliers per species — cow slow (catchable), others outrun the player. */
    private static double[] speedFor(Animal animal) {
        if (animal instanceof Cow) {
            return new double[] {Config.COW_WALK_SPEED.get(), Config.COW_SPRINT_SPEED.get()};
        }
        if (animal instanceof AbstractHorse) {
            return new double[] {Config.HORSE_WALK_SPEED.get(), Config.HORSE_SPRINT_SPEED.get()};
        }
        if (animal instanceof Rabbit || animal instanceof Fox) {
            return new double[] {Config.FAST_WALK_SPEED.get(), Config.FAST_SPRINT_SPEED.get()};
        }
        return new double[] {Config.PREY_WALK_SPEED.get(), Config.PREY_SPRINT_SPEED.get()};
    }

    /** Add a goal only if one of its class isn't already present (idempotent across reloads). */
    static void addGoalOnce(GoalSelector selector, int priority, Goal goal, Class<? extends Goal> type) {
        if (selector.getAvailableGoals().stream().noneMatch(w -> type.isInstance(w.getGoal()))) {
            selector.addGoal(priority, goal);
        }
    }

    // ------------------------------------------------------------------
    // From HuntingEvents
    // ------------------------------------------------------------------

    /*
     * Makes wild wolves and ocelots hostile to the player. Wolves: skip tamed/owned ones. Ocelots are
     * always wild, but vanilla makes them flee the player — so we strip that avoid goal before adding
     * the attack target. Both rely on their existing melee goals once a target is set.
     */
    private static final ResourceLocation HOSTILE_SPEED_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "hostile_speed");
    private static final ResourceLocation HOSTILE_ATTACK_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "hostile_attack");

    private static Field avoidClassField;
    private static boolean avoidClassFieldResolved;

    @SubscribeEvent
    static void onPredatorEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !Config.HUNTING_ENABLED.get()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Wolf wolf && Config.WILD_WOLVES_HOSTILE.get()) {
            if (wolf.isTame() || wolf.getOwnerUUID() != null) {
                return; // tamed/owned wolves stay friendly
            }
            // Wild wolves hunt both players and citizens. Player goal sits at the higher priority
            // (lower number) so a player in range is preferred; citizens are taken otherwise.
            HuntingEvents.addGoalOnce(wolf.targetSelector, 2,
                new PlayerHostilityTargetGoal(wolf), PlayerHostilityTargetGoal.class);
            HuntingEvents.addGoalOnce(wolf.targetSelector, 3,
                new CitizenHostilityTargetGoal(wolf), CitizenHostilityTargetGoal.class);
            buffPredator(wolf);
        } else if (entity instanceof Ocelot ocelot && Config.OCELOTS_HOSTILE.get()) {
            removeOcelotAvoidPlayer(ocelot);
            // Ocelots hunt both players and citizens — player at the higher priority (lower number)
            // so a player in range is preferred; citizens are taken otherwise.
            HuntingEvents.addGoalOnce(ocelot.targetSelector, 1,
                new PlayerHostilityTargetGoal(ocelot), PlayerHostilityTargetGoal.class);
            HuntingEvents.addGoalOnce(ocelot.targetSelector, 2,
                new CitizenHostilityTargetGoal(ocelot), CitizenHostilityTargetGoal.class);
            buffPredator(ocelot);
        }
    }

    /** Hostile predators hit harder and move faster. Transient modifiers (re-applied each join via
     *  the id-presence guard; not persisted, so disabling hunting and reloading reverts them). */
    private static void buffPredator(Mob mob) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(HOSTILE_SPEED_ID) == null) {
            speed.addTransientModifier(new AttributeModifier(HOSTILE_SPEED_ID,
                Config.HOSTILE_SPEED_MULT.get() - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        AttributeInstance attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null && attack.getModifier(HOSTILE_ATTACK_ID) == null) {
            attack.addTransientModifier(new AttributeModifier(HOSTILE_ATTACK_ID,
                Config.HOSTILE_ATTACK_BONUS.get(), AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** Strip the vanilla ocelot's "flee the player" avoid goal so it can attack instead. */
    private static void removeOcelotAvoidPlayer(Ocelot ocelot) {
        for (WrappedGoal wrapped : List.copyOf(ocelot.goalSelector.getAvailableGoals())) {
            if (wrapped.getGoal() instanceof AvoidEntityGoal<?> avoid && avoidsPlayers(avoid)) {
                ocelot.goalSelector.removeGoal(wrapped.getGoal());
            }
        }
    }

    /** True if the avoid goal targets players. Reads the protected {@code avoidClass} field
     *  reflectively (NeoForge runs Mojang mappings in production, so the name is stable); if that
     *  ever fails, fall back to true — the ocelot's only vanilla avoid goal is the player one. */
    private static boolean avoidsPlayers(AvoidEntityGoal<?> avoid) {
        try {
            if (!avoidClassFieldResolved) {
                avoidClassField = AvoidEntityGoal.class.getDeclaredField("avoidClass");
                avoidClassField.setAccessible(true);
                avoidClassFieldResolved = true;
            }
            if (avoidClassField != null) {
                return avoidClassField.get(avoid) == Player.class;
            }
        } catch (ReflectiveOperationException ignored) {
            avoidClassFieldResolved = true;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // From HuntingEvents
    // ------------------------------------------------------------------

    /*
     * Animals drop bones when killed — the bootstrap material for bone tools. Adults only (no baby
     * drops, matching vanilla). Chicken 50% → 1; cow/sheep/goat/pig 100% → 1–3; horse 100% → 2–4.
     * Added on top of the entity's normal loot.
     *
     * <p>This handler ALSO drops a quality-tagged raw HIDE (TANNERY plan): for the five hide species
     * (cow/sheep/pig/goat/horse) it adds a {@code <species>_hide} stamped with a {@link HideQuality} —
     * graded by the weapon-vs-preference for wild kills, or by living conditions for a player-slaughtered
     * domesticated animal. (The herder's auto-cull adds hides separately via {@code HerderHooks}, since
     * it bypasses this event.) Runs at default priority — before {@code DropGatingEvents} (LOW, strips
     * unknown items) and {@code HunterKillEvents} (LOWEST, reroutes to the hunter's depot).
     */
    @SubscribeEvent
    static void onAnimalDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.isBaby()) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        int bones = boneCountFor(entity, level.getRandom());
        if (bones > 0) {
            ItemEntity drop = new ItemEntity(level,
                entity.getX(), entity.getY() + 0.2, entity.getZ(),
                new ItemStack(Items.BONE, bones));
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }

        // A poisoned animal yields a ruined hide and tainted meat (see poisonKill for the wolfsbane
        // exception).
        PoisonType poison = poisonKill(entity, event.getSource());
        addHide(event, level, entity, poison);
        if (poison != null) {
            laceMeat(event, poison);
        }
    }

    /** The poison that should taint an animal's spoils, or {@code null}.
     *
     *  <p>Wolfsbane is the HUNTING poison: immobilise-then-spear is the intended kill, so its hide must
     *  still grade normally — only a wolfsbane animal that actually DIED FROM the poison (killing blow
     *  was POISON_DAMAGE) yields tainted spoils. Every OTHER poison taints as long as it's active in the
     *  animal at death, however it was killed. */
    private static PoisonType poisonKill(LivingEntity entity, DamageSource source) {
        PoisonState state = Poisons.getPoison(entity);
        if (!state.active()) return null;
        PoisonType type = state.type();
        if (type == PoisonType.WOLFSBANE) {
            return (source != null && source.is(BannerboundAntiquity.POISON_DAMAGE)) ? type : null;
        }
        return type;
    }

    /** Stamp the corresponding poison onto any FOOD the animal dropped (raw meat) — eat it and you're
     *  poisoned, the same as laced food. Wild kill → no poisoner (no settlement tooltip). */
    private static void laceMeat(LivingDropsEvent event, PoisonType poison) {
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.has(DataComponents.FOOD)) {
                stack.set(BannerboundAntiquity.POISONED_FOOD.get(), new PoisonedFoodData(poison.id(), 1, ""));
            }
        }
    }

    /** Adds a quality-tagged raw hide for the five hide species (no-op for others). A poison death
     *  ruins it to POOR regardless of weapon/conditions. */
    private static void addHide(LivingDropsEvent event, ServerLevel level, LivingEntity entity, PoisonType poison) {
        Item hide = Hides.hideFor(entity.getType());
        if (hide == null) return;

        HideQuality quality;
        if (poison != null) {
            quality = HideQuality.POOR; // tainted by poison — a ruined hide
        } else if (HuntingFear.isTamed(entity)) {
            // Domesticated animal slaughtered by hand: graded by local living conditions (no herder
            // skill on a player kill — the auto-cull path supplies the skill term via the hook).
            quality = HideGrading.gradeHerd(
                BreedingEvents.breedChance(level, entity.blockPosition()), 0);
        } else {
            quality = HideGrading.gradeHunt(entity.getType(), weaponCategory(event.getSource()));
        }

        ItemStack stack = new ItemStack(hide);
        stack.set(BannerboundAntiquity.HIDE_QUALITY.get(), quality);
        ItemEntity drop = new ItemEntity(level,
            entity.getX(), entity.getY() + 0.2, entity.getZ(), stack);
        drop.setDefaultPickUpDelay();
        event.getDrops().add(drop);
    }

    /** The weapon category of a kill, or {@code null} (improper kill → POOR). Projectiles decide by
     *  their type; melee/throw by the killer's held weapon (NPC job tool or player main hand). */
    private static WeaponCategory weaponCategory(DamageSource source) {
        if (source == null) return null;
        Entity direct = source.getDirectEntity();
        if (direct instanceof SpearProjectile) return WeaponCategory.SPEAR;
        if (direct instanceof AbstractArrow) return WeaponCategory.ARROW;
        Entity killer = source.getEntity();
        if (killer instanceof CitizenEntity c) return WeaponCategory.of(c.getJobTool());
        if (killer instanceof Player p) return WeaponCategory.of(p.getMainHandItem());
        return null;
    }

    private static int boneCountFor(LivingEntity entity, RandomSource rng) {
        if (entity instanceof Chicken) {
            return rng.nextFloat() < 0.5f ? 1 : 0;
        }
        if (entity instanceof Horse) {
            return 2 + rng.nextInt(3); // 2–4
        }
        if (entity instanceof Cow || entity instanceof Sheep
                || entity instanceof Goat || entity instanceof Pig) {
            return 1 + rng.nextInt(3); // 1–3
        }
        if (entity instanceof Wolf || entity instanceof Ocelot) {
            return 1 + rng.nextInt(2); // 1–2 — predators yield bone too
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // From HuntingEvents
    // ------------------------------------------------------------------

    /*
     * Lifecycle of spears embedded in a mob (the {@code STUCK_SPEARS} attachment):
     * <ul>
     *   <li><b>Drop on death</b> — each stored spear ItemStack is added to the loot, then the list is
     *       cleared so a non-removed corpse can't re-drop.</li>
     *   <li><b>Pull out by hand</b> — shift + right-click a mob that has a spear in it to yank one back
     *       out (RNG chance), returned as the exact stored spear (full NBT).</li>
     *   <li><b>NPC spears are cosmetic-only</b> — a hunter's thrown copy embeds for the visual but is
     *       {@code !recoverable()}: it never death-drops, can't be pulled (no tool duplication), and is
     *       pruned off the mob once its {@code expireGameTime} passes (arrow-style despawn).</li>
     * </ul>
     * All run on the game bus, server-authoritative. Mirrors {@link HuntingEvents}'s drop pattern.
     */
    @SubscribeEvent
    static void onDropStuckSpears(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        List<StuckSpear> spears = entity.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        if (spears == null || spears.isEmpty()) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        for (StuckSpear spear : spears) {
            if (spear.stack().isEmpty() || !spear.recoverable()) {
                continue;   // an NPC hunter's cosmetic spear never yields an item (no tool dup)
            }
            ItemEntity drop = new ItemEntity(level, entity.getX(), entity.getY() + 0.2, entity.getZ(),
                spear.stack().copy());
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }
        // Clear so the spears can't drop twice (e.g. a corpse that isn't immediately removed).
        entity.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.of());
    }

    /** Shift + right-click a speared mob to pull one spear back out — the exact stored spear, NBT
     *  and all. Always succeeds; when several are stuck, a random one comes out. */
    @SubscribeEvent
    static void onPullStuckSpear(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return; // EntityInteract fires per hand — only act once
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !(event.getTarget() instanceof LivingEntity mob)) {
            return;
        }
        List<StuckSpear> spears = mob.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        // Only a PLAYER'S spear can be pulled — an NPC hunter's cosmetic spear is untouchable (it
        // would duplicate the citizen's reusable tool), so with only those stuck the interaction
        // falls through as if nothing were embedded.
        List<StuckSpear> pullable = spears == null ? List.of()
            : spears.stream().filter(StuckSpear::recoverable).toList();
        if (pullable.isEmpty()) {
            return; // nothing pullable → leave the interaction alone (normal shift-click behaviour)
        }
        // A spear is stuck → this shift-right-click is a pull attempt; consume it so it doesn't also
        // start charging a throw / trigger vanilla entity interaction.
        Level level = event.getLevel();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide) {
            return; // server is authoritative for the attachment + item grant
        }
        // Pull a RANDOM one of the pullable spears, returning the exact stored stack.
        StuckSpear pulled = pullable.get(mob.getRandom().nextInt(pullable.size()));
        List<StuckSpear> remaining = new ArrayList<>(spears);
        remaining.remove(pulled);
        mob.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.copyOf(remaining));
        ItemStack stack = pulled.stack().copy();
        if (!player.addItem(stack)) {
            player.drop(stack, false); // inventory full → drop at the player so it's not lost
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            BannerboundAntiquity.SPEAR_REEL_SOUND.get(), SoundSource.PLAYERS,
            0.8F, 0.8F + mob.getRandom().nextFloat() * 0.2F);
    }

    /** Prune timed-out NPC spears off living mobs (arrow-style despawn). Throttled to every 2 s per
     *  entity; mobs with no attachment pay only a null check. */
    @SubscribeEvent
    static void onPruneExpiredSpears(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || mob.level().isClientSide()
                || mob.tickCount % 40 != 0) {
            return;
        }
        List<StuckSpear> spears = mob.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        if (spears == null || spears.isEmpty()) {
            return;
        }
        long now = mob.level().getGameTime();
        List<StuckSpear> kept = spears.stream().filter(s -> !s.isExpired(now)).toList();
        if (kept.size() != spears.size()) {
            mob.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.copyOf(kept));
        }
    }

    // ------------------------------------------------------------------
    // From HuntingEvents
    // ------------------------------------------------------------------

    /*
     * Spear fishing: when a thrown {@link SpearProjectile} kills a fish, replace the loose item drops
     * (the fish, its drops, and the spear) with a single floating {@link SpearedFishEntity} — the spear
     * with the fish impaled on its tip, bobbing at the surface. Walking over the catch grants everything
     * at once (see {@link SpearedFishEntity}); it's a purely visual/immersion change — the bundled items
     * are exactly what would otherwise have dropped.
     *
     * <p>Runs on the death-drop event, which fires <i>inside</i> {@code fish.hurt(...)} while the spear
     * projectile still exists — so the spear is read straight off the damage source. The killing-blow
     * branch in {@link SpearProjectile#onHitEntity} skips its own spear drop for fish, so the spear isn't
     * duplicated. Mirrors {@link HuntingEvents}'s {@code LivingDropsEvent} pattern.
     */
    @SubscribeEvent
    static void onFishSpeared(LivingDropsEvent event) {
        if (!Config.SPEAR_FISHING_ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractFish fish)) {
            return; // fish only
        }
        if (!(event.getSource().getDirectEntity() instanceof SpearProjectile spear)) {
            return; // only spear kills convert — melee / other deaths drop normally
        }
        if (!(fish.level() instanceof ServerLevel level)) {
            return;
        }

        // Capture what the fish would have dropped, then suppress the loose drops.
        List<ItemStack> drops = new ArrayList<>();
        for (ItemEntity itemEntity : event.getDrops()) {
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
        event.getDrops().clear();

        String fishType = BuiltInRegistries.ENTITY_TYPE.getKey(fish.getType()).toString();
        // Variant rendering is a follow-up; store 0 for now (renderer uses a representative look).
        SpearedFishEntity catchEntity = new SpearedFishEntity(level,
            fish.getX(), fish.getY() + fish.getBbHeight() * 0.5, fish.getZ(),
            spear.getSpearItem(), fishType, 0, drops);
        // Orient the catch the way the spear was travelling when it struck (not a fixed planted pose).
        catchEntity.setPierce(spear.getYRot(), spear.getXRot());
        // Rope-tethered kill: hand the tether off from the (now-discarded) spear to the floating
        // catch, so the green rope stays attached with no gap and the catch is reelable.
        if (spear.isRopeTethered() && spear.getOwner() != null) {
            catchEntity.setTether(spear.getOwner());
        }
        level.addFreshEntity(catchEntity);
    }

    /**
     * Empty-hand reel-in when shift-right-clicking while looking at a block (e.g. the seabed through
     * the water). {@code RightClickEmpty} only fires for clicks at <i>air</i>, so it misses the common
     * "looking down at the water" case — but a block right-click reaches the server, so we reel here.
     * Server-authoritative; the rope already spent on the throw is what you're pulling, so no item is
     * needed in hand. (Held-rope reel is handled by {@code FiberRopeItem.use}.)
     */
    @SubscribeEvent
    static void onEmptyHandReel(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getLevel().isClientSide) {
            return;
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !event.getItemStack().isEmpty()) {
            return; // empty-hand only — the rope-in-hand path is FiberRopeItem.use
        }
        if (SpearFishing.startReel(player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
