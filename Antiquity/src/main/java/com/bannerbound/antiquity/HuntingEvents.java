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

/**
 * Antiquity's immersive-hunting event hub: prey behaviour, predator hostility, kill drops, embedded
 * spears, and spear-fishing. All handlers are server-authoritative on the game bus and gated by the
 * hunting config; tamed livestock ({@link HuntingFear#isTamed}) opt out of every prey mechanic.
 *
 * <p>PREY ({@link #onAnimalTick}, {@link #onPreyHurt}, {@link #onMiningNoise}, {@link #spook}):
 * stamina drains while a spooked animal actually flees and regens when calm -- below the tired
 * threshold FleeFromPlayerGoal slows it, so a tiring animal can be run down (persistence hunting).
 * A hit or nearby mining spooks prey (players and Hunter citizens both count as threats) and, on the
 * calm->scared edge, alarms the herd and elects a boar charger for pigs. Bleeding counts down each
 * tick, dealing damage-over-time plus blood particles and a ground-clamped decal trail; footprints
 * drop at a speed-stretched spacing for species that ship a per-species footprint texture.
 *
 * <p>AI INJECTION ({@link #onEntityJoin}, {@link #onPredatorEntityJoin}): flee/herd goals (and the
 * pig boar-charge) are added to vanilla animals as they spawn/load, deduped so chunk reloads never
 * stack goals; wild (untamed/unowned) wolves and ocelots are made hostile to players and citizens
 * (player goal at the higher priority) and buffed with transient (non-persisted) speed/damage
 * modifiers that revert when hunting is disabled. Vanilla's ocelot "flee the player" avoid goal is
 * stripped first so it can attack.
 *
 * <p>DROPS ({@link #onAnimalDrops}): adults drop bones (the bone-tool bootstrap) and, for the five
 * hide species, a HideQuality-stamped raw hide graded by weapon-vs-preference (wild) or living
 * conditions (hand-slaughtered domestic; the herder auto-cull adds hides separately via HerderHooks).
 * Runs at default priority -- BEFORE DropGatingEvents (LOW, strips unknown items) and HunterKillEvents
 * (LOWEST, reroutes to the hunter's depot). Wolfsbane is the hunting poison, so its hide grades
 * normally unless the killing blow was the poison itself; any other active poison taints the hide
 * (POOR) and laces the dropped meat.
 *
 * <p>STUCK SPEARS ({@link #onDropStuckSpears}, {@link #onPullStuckSpear}, {@link #onPruneExpiredSpears}):
 * spears embedded via the STUCK_SPEARS attachment death-drop their exact stored stack (then the list is
 * cleared so a lingering corpse can't re-drop) or can be shift-clicked back out. NPC hunter spears are
 * cosmetic (!recoverable): they never drop, can't be pulled (no tool dup), and are pruned once expired.
 *
 * <p>SPEAR FISHING ({@link #onFishSpeared}, {@link #onEmptyHandReel}): a SpearProjectile kill on a fish
 * replaces the loose drops with one floating SpearedFishEntity bundling exactly those items; a rope
 * tether is handed off so the catch stays reelable. An empty-hand shift-right-click on a block reels a
 * spent throw back in (RightClickEmpty misses the looking-at-water case).
 *
 * Open: finish per-fish variant rendering (SpearedFishEntity is currently constructed with variant 0).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class HuntingEvents {

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
            return;
        }
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
                    }
                }

                animal.hurt(animal.damageSources().source(BannerboundAntiquity.BLEEDING_DAMAGE, owner),
                    (float) (double) Config.BLEED_DAMAGE_PER_TICK.get());
                level.sendParticles(BannerboundAntiquity.BLOOD_DROP.get(),
                    animal.getX(), animal.getY() + animal.getBbHeight() * 0.6, animal.getZ(),
                    16, 0.15, 0.1, 0.15, 0.15);
                if (Config.BLOOD_SPLAT_ENABLED.get()
                        && animal.getRandom().nextDouble() < Config.BLOOD_SPLAT_CHANCE.get()) {
                    GroundDecalEntity.spawnBlood(level, animal.getX(), animal.getY(), animal.getZ(),
                        animal.getRandom().nextInt(10000), animal);
                }
            }

            if (remaining == 0) {
                animal.removeData(BannerboundAntiquity.BLEED_BY.get());
            }
        }
        dropFootprints(level, animal);
    }

    private static void dropFootprints(ServerLevel level, Animal animal) {
        if (!Config.FOOTPRINTS_ENABLED.get() || !animal.onGround()) {
            return;
        }
        String species = BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath();
        if (!Config.FOOTPRINT_SPECIES.get().contains(species)) {
            return;
        }
        double moved = Math.hypot(animal.getX() - animal.xOld, animal.getZ() - animal.zOld);
        if (moved < 0.01) {
            return;
        }
        float dist = animal.getData(BannerboundAntiquity.FOOTPRINT_DIST.get()) + (float) moved;
        double spacing = Config.FOOTPRINT_SPACING.get() * (1.0 + Config.FOOTPRINT_SPEED_STRETCH.get() * moved);
        if (dist < spacing) {
            animal.setData(BannerboundAntiquity.FOOTPRINT_DIST.get(), dist);
            return;
        }
        animal.setData(BannerboundAntiquity.FOOTPRINT_DIST.get(), (float) (dist - spacing));
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
            return;
        }
        if (!(victim.level() instanceof ServerLevel)) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        boolean playerThreat = attacker instanceof Player player
            && !player.isCreative() && !player.isSpectator();
        boolean hunterThreat = attacker instanceof com.bannerbound.core.entity.CitizenEntity;
        if (!playerThreat && !hunterThreat) {
            return;
        }
        spook(animal, Config.SCARED_DURATION_TICKS.get());
    }

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

    private static void spook(Animal animal, int ticks) {
        if (HuntingFear.isTamed(animal)) {
            return;
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

    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !Config.HUNTING_ENABLED.get()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Wolf || entity instanceof Ocelot) {
            return;
        }
        if (entity instanceof Animal animal) {
            double[] speed = speedFor(animal);
            // Boar charge at priority 0 so the elected charger preempts the flee goal (same priority -> flee grabs MOVE first and holds it).
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

    static void addGoalOnce(GoalSelector selector, int priority, Goal goal, Class<? extends Goal> type) {
        if (selector.getAvailableGoals().stream().noneMatch(w -> type.isInstance(w.getGoal()))) {
            selector.addGoal(priority, goal);
        }
    }

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
                return;
            }
            HuntingEvents.addGoalOnce(wolf.targetSelector, 2,
                new PlayerHostilityTargetGoal(wolf), PlayerHostilityTargetGoal.class);
            HuntingEvents.addGoalOnce(wolf.targetSelector, 3,
                new CitizenHostilityTargetGoal(wolf), CitizenHostilityTargetGoal.class);
            buffPredator(wolf);
        } else if (entity instanceof Ocelot ocelot && Config.OCELOTS_HOSTILE.get()) {
            removeOcelotAvoidPlayer(ocelot);
            HuntingEvents.addGoalOnce(ocelot.targetSelector, 1,
                new PlayerHostilityTargetGoal(ocelot), PlayerHostilityTargetGoal.class);
            HuntingEvents.addGoalOnce(ocelot.targetSelector, 2,
                new CitizenHostilityTargetGoal(ocelot), CitizenHostilityTargetGoal.class);
            buffPredator(ocelot);
        }
    }

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

    private static void removeOcelotAvoidPlayer(Ocelot ocelot) {
        for (WrappedGoal wrapped : List.copyOf(ocelot.goalSelector.getAvailableGoals())) {
            if (wrapped.getGoal() instanceof AvoidEntityGoal<?> avoid && avoidsPlayers(avoid)) {
                ocelot.goalSelector.removeGoal(wrapped.getGoal());
            }
        }
    }

    private static boolean avoidsPlayers(AvoidEntityGoal<?> avoid) {
        try {
            if (!avoidClassFieldResolved) {
                // Reflective field name: NeoForge runs Mojang mappings in prod so "avoidClass" is stable; on failure fall back to true.
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

        PoisonType poison = poisonKill(entity, event.getSource());
        addHide(event, level, entity, poison);
        if (poison != null) {
            laceMeat(event, poison);
        }
    }

    private static PoisonType poisonKill(LivingEntity entity, DamageSource source) {
        PoisonState state = Poisons.getPoison(entity);
        if (!state.active()) return null;
        PoisonType type = state.type();
        if (type == PoisonType.WOLFSBANE) {
            return (source != null && source.is(BannerboundAntiquity.POISON_DAMAGE)) ? type : null;
        }
        return type;
    }

    private static void laceMeat(LivingDropsEvent event, PoisonType poison) {
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.has(DataComponents.FOOD)) {
                stack.set(BannerboundAntiquity.POISONED_FOOD.get(), new PoisonedFoodData(poison.id(), 1, ""));
            }
        }
    }

    private static void addHide(LivingDropsEvent event, ServerLevel level, LivingEntity entity, PoisonType poison) {
        Item hide = Hides.hideFor(entity.getType());
        if (hide == null) return;

        HideQuality quality;
        if (poison != null) {
            quality = HideQuality.POOR;
        } else if (HuntingFear.isTamed(entity)) {
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
            return 2 + rng.nextInt(3);
        }
        if (entity instanceof Cow || entity instanceof Sheep
                || entity instanceof Goat || entity instanceof Pig) {
            return 1 + rng.nextInt(3);
        }
        if (entity instanceof Wolf || entity instanceof Ocelot) {
            return 1 + rng.nextInt(2);
        }
        return 0;
    }

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
                continue;
            }
            ItemEntity drop = new ItemEntity(level, entity.getX(), entity.getY() + 0.2, entity.getZ(),
                spear.stack().copy());
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }
        // Clear so the spears can't drop twice if the corpse isn't immediately removed.
        entity.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.of());
    }

    @SubscribeEvent
    static void onPullStuckSpear(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return; // EntityInteract fires per hand -> only act once
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !(event.getTarget() instanceof LivingEntity mob)) {
            return;
        }
        List<StuckSpear> spears = mob.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        List<StuckSpear> pullable = spears == null ? List.of()
            : spears.stream().filter(StuckSpear::recoverable).toList();
        if (pullable.isEmpty()) {
            return;
        }
        Level level = event.getLevel();
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide) {
            return;
        }
        StuckSpear pulled = pullable.get(mob.getRandom().nextInt(pullable.size()));
        List<StuckSpear> remaining = new ArrayList<>(spears);
        remaining.remove(pulled);
        mob.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.copyOf(remaining));
        ItemStack stack = pulled.stack().copy();
        if (!player.addItem(stack)) {
            player.drop(stack, false);
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            BannerboundAntiquity.SPEAR_REEL_SOUND.get(), SoundSource.PLAYERS,
            0.8F, 0.8F + mob.getRandom().nextFloat() * 0.2F);
    }

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

    @SubscribeEvent
    static void onFishSpeared(LivingDropsEvent event) {
        if (!Config.SPEAR_FISHING_ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof AbstractFish fish)) {
            return;
        }
        // SpearProjectile.onHitEntity skips its own spear drop for fish, so bundling the spear here doesn't duplicate it.
        if (!(event.getSource().getDirectEntity() instanceof SpearProjectile spear)) {
            return;
        }
        if (!(fish.level() instanceof ServerLevel level)) {
            return;
        }

        List<ItemStack> drops = new ArrayList<>();
        for (ItemEntity itemEntity : event.getDrops()) {
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty()) {
                drops.add(stack.copy());
            }
        }
        event.getDrops().clear();

        String fishType = BuiltInRegistries.ENTITY_TYPE.getKey(fish.getType()).toString();
        SpearedFishEntity catchEntity = new SpearedFishEntity(level,
            fish.getX(), fish.getY() + fish.getBbHeight() * 0.5, fish.getZ(),
            spear.getSpearItem(), fishType, 0, drops);
        catchEntity.setPierce(spear.getYRot(), spear.getXRot());
        if (spear.isRopeTethered() && spear.getOwner() != null) {
            catchEntity.setTether(spear.getOwner());
        }
        level.addFreshEntity(catchEntity);
    }

    @SubscribeEvent
    static void onEmptyHandReel(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.getLevel().isClientSide) {
            return;
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !event.getItemStack().isEmpty()) {
            return;
        }
        if (SpearFishing.startReel(player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
