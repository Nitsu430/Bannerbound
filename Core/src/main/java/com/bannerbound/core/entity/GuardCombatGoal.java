package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.hunter.HunterHooks;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * The guard's combat AI (GUARD_PLAN.md) -- the "much better than self-defence" fight. A plain Goal
 * (not a WorkGoal) registered at priority 0 for every citizen but only usable while the citizen holds
 * the guard job, so CitizenCombatGoal (which yields for guards) never competes for the slot; being a
 * plain Goal it keeps fighting when the work brain is throttled (Village+ ambient brain / no player
 * nearby). GuardTargetingGoal owns the target field and picks who to fight; this goal does the moving
 * and swinging on whatever target that set.
 *
 * <p>What makes it better than the baseline chase-and-swing:
 * - Leash to home: a guard only engages hostiles inside the settlement's claims or within
 *   DEFENSE_BAND_CHUNKS (2) chunks of them, so it never suicide-chases a fleeing raider into the wild
 *   to be swarmed. The one exemption is the live retaliation target (isGuardRetaliationTarget):
 *   whoever is actively damaging this guard is fair game even from outside the band, so the watch can't
 *   be plinked from past the border. withinDefenseBand is shared with GuardTargetingGoal so acquisition
 *   and the fight agree on "home ground"; it fails open for an unclaimed settlement.
 * - The weapon in hand is REAL: melee damage and swing speed are read off the actual job-tool
 *   ItemStack's attribute modifiers (a bronze sword hits harder than a bone club because the item says
 *   so), scaled by craftsmanship QualityTier and the guard's guards_post mastery (novice x1.0 -> master
 *   x1.5). No weapon -> fists (1.0 damage, slow); stocking the armory is a real logistics decision. Each
 *   landed hit grants 0.1 job XP so the tank earns too; the kill itself pays 1.0 via GuardCombatEvents.
 * - Ranged guards: a guard drawing a bow (#bannerbound:hunter_bows) or sling (#bannerbound:guard_slings)
 *   fights at range -- windup telegraph, planted shot, back-pedal when the enemy closes, advance when
 *   out of range or sight, and sidestep when a same-settlement citizen stands in the fire lane (the
 *   projectile-immunity net in GuardCombatEvents makes a stray shot harmless; the sidestep is what the
 *   player SEES). Arrows are Pickup.DISALLOWED so guards can't be arrow-farmed; sling rocks fire through
 *   HunterHooks.shootSling (Antiquity spawns the ThrownRock). An arrow's real hit is baseDamage x
 *   velocity, so the base is derived from the intended hit at fire time.
 * - Melee footwork: while a swing recovers a guard GIVES GROUND (short fencing step) instead of trading
 *   free hits, then steps back in the moment its cooldown is ready -- the fight oscillates in place (net
 *   drift ~0) rather than migrating. Inverted against a RANGED enemy: crowd it, never hand an archer its
 *   preferred range.
 *
 * <p>It keeps CitizenCombatGoal's creeper-swell yield, but only when this guard is close enough to eat
 * the blast (CREEPER_YIELD_SQ) -- an archer keeps shooting a lit creeper from safety while a melee guard
 * flees (the priority-0 AvoidEntityGoal takes over). Couriers on a trade journey never fight (killable
 * cargo). Windup/cooldown constants match BarbarianRangedGoal's bowman.
 */
@ApiStatus.Internal
public class GuardCombatGoal extends Goal {
    private static final double BARE_HAND_DAMAGE = 1.0;
    private static final int BARE_HAND_COOLDOWN_TICKS = 20;
    private static final double MELEE_REACH_SQ = 4.0;
    private static final int REPATH_INTERVAL = 10;
    private static final int DEFENSE_BAND_CHUNKS = 2;
    private static final double SKILL_DAMAGE_BONUS = 0.5;
    private static final double CREEPER_YIELD_SQ = 49.0;
    private static final double FENCING_SPACE_SQ = 3.0 * 3.0;
    private static final double FENCING_STEP_BLOCKS = 3.5;

    private static final double RANGED_TOO_CLOSE_SQ = 36.0;
    private static final double RANGED_KITE_STEP_BLOCKS = 8.0;
    private static final double BOW_RANGE_SQ = 24.0 * 24.0;
    private static final double SLING_RANGE_SQ = 16.0 * 16.0;
    private static final int BOW_WINDUP_TICKS = 14;
    private static final int SLING_WINDUP_TICKS = 12;
    private static final int BOW_SHOT_COOLDOWN = 30;
    private static final int SLING_SHOT_COOLDOWN = 25;
    private static final float ARROW_VELOCITY = 1.6F;
    private static final float ARROW_INACCURACY = 4.0F;
    private static final double BOW_HIT_DAMAGE = 6.0;
    private static final double SLING_HIT_DAMAGE = 4.0;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private LivingEntity target;
    @Nullable private ItemStack stashedMainHand;
    private int attackCooldown;
    private int repathCooldown;
    private int windupTicks;

    public GuardCombatGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (citizen.isOnTradeJourney()) return false;
        if (!citizen.isGuard()) return false;
        LivingEntity t = citizen.getTarget();
        if (t == null || !t.isAlive()) return false;
        if (shouldYieldToCreeper(t)) return false;
        if (!withinDefenseBand(t) && !citizen.isGuardRetaliationTarget(t)) return false;
        target = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!citizen.isGuard()) return false;
        if (target == null || !target.isAlive()) return false;
        if (shouldYieldToCreeper(target)) return false;
        return withinDefenseBand(target) || citizen.isGuardRetaliationTarget(target);
    }

    private boolean shouldYieldToCreeper(LivingEntity t) {
        return t instanceof Creeper c && c.getSwellDir() > 0
            && citizen.distanceToSqr(c) < CREEPER_YIELD_SQ;
    }

    @Override
    public void start() {
        attackCooldown = 0;
        repathCooldown = 0;
        windupTicks = 0;
        equipWeapon();
        if (target != null) citizen.getNavigation().moveTo(target, speedModifier);
    }

    @Override
    public void tick() {
        if (target == null) return;
        citizen.getLookControl().setLookAt(target, 30.0f, 30.0f);
        if (attackCooldown > 0) attackCooldown--;
        ItemStack weapon = GuardWorkGoal.currentWeapon(citizen);
        if (citizen.level() instanceof ServerLevel sl && GuardWorkGoal.isRangedWeapon(weapon)) {
            rangedTick(sl, weapon);
        } else {
            meleeTick(weapon);
        }
    }

    @Override
    public void stop() {
        target = null;
        windupTicks = 0;
        if (citizen.isUsingItem()) citizen.stopUsingItem();
        citizen.getNavigation().stop();
        // Do NOT clear citizen.getTarget() here: GuardTargetingGoal owns that field and re-picks the next raider.
        if (stashedMainHand != null) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, stashedMainHand);
            stashedMainHand = null;
        }
    }

    private void meleeTick(ItemStack weapon) {
        double dSq = citizen.distanceToSqr(target);
        if (attackCooldown <= 0) {
            if (dSq <= MELEE_REACH_SQ) {
                citizen.getNavigation().stop();
                citizen.swing(InteractionHand.MAIN_HAND);
                target.hurt(citizen.damageSources().mobAttack(citizen), (float) meleeDamage(weapon));
                attackCooldown = meleeCooldownTicks(weapon);
                citizen.grantJobXp(GuardWorkGoal.JOB_TYPE_ID, 0.1f, "guard");
            } else if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(target, speedModifier);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        if (targetShootsBack()) {
            if (dSq > MELEE_REACH_SQ && (--repathCooldown <= 0 || citizen.getNavigation().isDone())) {
                citizen.getNavigation().moveTo(target, speedModifier);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        if (dSq < FENCING_SPACE_SQ) {
            backAwayFrom(target, FENCING_STEP_BLOCKS);
        } else {
            citizen.getNavigation().stop();
        }
    }

    private boolean targetShootsBack() {
        if (target instanceof CombatantCitizen cc && cc.prefersRanged()) return true;
        return target.getMainHandItem().getItem()
            instanceof net.minecraft.world.item.ProjectileWeaponItem;
    }

    private double meleeDamage(ItemStack weapon) {
        if (weapon.isEmpty()) return BARE_HAND_DAMAGE * skillMultiplier();
        return GuardWorkGoal.weaponAttackDamage(weapon)
            * QualityTier.of(weapon).statMultiplier()
            * skillMultiplier();
    }

    private int meleeCooldownTicks(ItemStack weapon) {
        if (weapon.isEmpty()) return BARE_HAND_COOLDOWN_TICKS;
        double atkSpeed = GuardWorkGoal.weaponAttackSpeed(weapon);
        if (atkSpeed <= 0.0) return BARE_HAND_COOLDOWN_TICKS;
        return Math.max(5, (int) Math.round(20.0 / atkSpeed));
    }

    private void rangedTick(ServerLevel sl, ItemStack weapon) {
        boolean sling = GuardWorkGoal.isSlingWeapon(weapon);
        double dSq = citizen.distanceToSqr(target);
        boolean los = citizen.getSensing().hasLineOfSight(target);

        if (windupTicks > 0) {
            if (--windupTicks == 0) {
                if (los && target.isAlive()) fire(sl, weapon, sling);
                if (citizen.isUsingItem()) citizen.stopUsingItem();
                attackCooldown = sling ? SLING_SHOT_COOLDOWN : BOW_SHOT_COOLDOWN;
            }
            return;
        }
        if (dSq < RANGED_TOO_CLOSE_SQ) {
            backAwayFrom(target, RANGED_KITE_STEP_BLOCKS);
        } else if (dSq > (sling ? SLING_RANGE_SQ : BOW_RANGE_SQ) || !los) {
            if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(target, speedModifier * 0.9);
                repathCooldown = REPATH_INTERVAL;
            }
        } else if (attackCooldown <= 0) {
            if (friendlyInLane(sl)) {
                sidestep();
                attackCooldown = 8;
                return;
            }
            citizen.getNavigation().stop();
            windupTicks = sling ? SLING_WINDUP_TICKS : BOW_WINDUP_TICKS;
            citizen.startUsingItem(InteractionHand.MAIN_HAND);
        } else {
            citizen.getNavigation().stop();
        }
    }

    private boolean friendlyInLane(ServerLevel sl) {
        Vec3 from = citizen.getEyePosition();
        Vec3 to = new Vec3(target.getX(), target.getY(0.5), target.getZ());
        java.util.UUID home = citizen.getSettlementId();
        if (home == null) return false;
        for (CitizenEntity c : sl.getEntitiesOfClass(CitizenEntity.class,
                new net.minecraft.world.phys.AABB(from, to).inflate(1.0))) {
            if (c == citizen || c == target) continue;
            if (!home.equals(c.getSettlementId())) continue;
            if (c.getBoundingBox().inflate(0.4).clip(from, to).isPresent()) return true;
        }
        return false;
    }

    private void sidestep() {
        Vec3 toTarget = target.position().subtract(citizen.position());
        Vec3 side = new Vec3(-toTarget.z, 0, toTarget.x).normalize()
            .scale(citizen.getRandom().nextBoolean() ? 3.0 : -3.0);
        Vec3 dest = citizen.position().add(side);
        citizen.getNavigation().moveTo(dest.x, dest.y, dest.z, speedModifier);
    }

    private void fire(ServerLevel sl, ItemStack weapon, boolean sling) {
        double scaled = QualityTier.of(weapon).statMultiplier() * skillMultiplier();
        citizen.swing(InteractionHand.MAIN_HAND);
        if (sling) {
            HunterHooks.get().shootSling(citizen, target, weapon, SLING_HIT_DAMAGE * scaled);
            return;
        }
        AbstractArrow arrow = HunterHooks.get().createArrow(citizen, weapon);
        if (arrow == null) {
            arrow = new Arrow(sl, citizen, new ItemStack(Items.ARROW), citizen.getMainHandItem());
        }
        arrow.setBaseDamage(Math.max(2.0, BOW_HIT_DAMAGE * scaled / ARROW_VELOCITY));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        float velocity = ARROW_VELOCITY * HunterHooks.get().bowVelocityFactor(weapon);
        double dx = target.getX() - citizen.getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - citizen.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, velocity, ARROW_INACCURACY);
        sl.addFreshEntity(arrow);
        sl.playSound(null, citizen.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL,
            1.0F, 1.0F / (citizen.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    private void backAwayFrom(LivingEntity t, double blocks) {
        Vec3 away = citizen.position().subtract(t.position());
        if (away.lengthSqr() < 1.0e-3) away = new Vec3(1, 0, 0);
        Vec3 dest = citizen.position().add(away.normalize().scale(blocks));
        citizen.getNavigation().moveTo(dest.x, dest.y, dest.z, speedModifier);
    }

    private void equipWeapon() {
        stashedMainHand = citizen.getItemBySlot(EquipmentSlot.MAINHAND).copy();
        ItemStack weapon = GuardWorkGoal.currentWeapon(citizen);
        if (!weapon.isEmpty()) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());
        }
    }

    private double skillMultiplier() {
        return 1.0 + SKILL_DAMAGE_BONUS * jobSkill();
    }

    private float jobSkill() {
        float xp = citizen.getJobXp(GuardWorkGoal.JOB_TYPE_ID);
        return xp / (xp + QualityMath.NPC_XP_HALF);
    }

    private boolean withinDefenseBand(LivingEntity e) {
        Settlement s = citizen.getSettlement();
        return citizen.level() instanceof ServerLevel && s != null
            && withinDefenseBand(s, e.blockPosition());
    }

    static boolean withinDefenseBand(Settlement s, BlockPos pos) {
        Set<Long> claimed = s.claimedChunks();
        if (claimed.isEmpty()) return true;
        ChunkPos tc = new ChunkPos(pos);
        for (int dx = -DEFENSE_BAND_CHUNKS; dx <= DEFENSE_BAND_CHUNKS; dx++) {
            for (int dz = -DEFENSE_BAND_CHUNKS; dz <= DEFENSE_BAND_CHUNKS; dz++) {
                if (claimed.contains(ChunkPos.asLong(tc.x + dx, tc.z + dz))) return true;
            }
        }
        return false;
    }
}
