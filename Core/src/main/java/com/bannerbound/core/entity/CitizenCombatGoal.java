package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Self-defence AI. Fires whenever the citizen has a non-null {@link CitizenEntity#getTarget}
 * (set by the NearestAttackableTargetGoal in their target selector, or by vanilla's
 * lastHurtByMob pathway when something hits them): equips the settlement's current tool-age
 * sword in the main hand (stashing whatever was there so {@link #stop} restores it), pathfinds
 * to the target at chase speed, and swings for weaponDamage half-hearts every
 * 20/weaponAttackSpeed ticks (floored at 5, so no future attack-speed value can melt mobs in a
 * tick) while within MELEE_REACH_SQ (~2 blocks). stop() restores the stashed mainhand so
 * off-duty citizens don't brandish a sword forever.
 *
 * <p>Damage/attack-speed source, in order: the constructor overrides (used for settlement-less
 * fighters, e.g. barbarians driven by a BarbarianCapability; a value &gt; 0 overrides, &lt;= 0
 * means "use the settlement"), then a {@link CombatantCitizen}'s capability values, then the
 * settlement tool age, else bare-hand defaults (1 dmg, 1/s). A CombatantCitizen bowman swaps to
 * its melee weapon in equipWeapon and only melees when cornered (~3 blocks), else holds range
 * for its ranged goal; barbarians also get a per-member chase-speed variance (&lt;1) so they
 * don't all sprint in at one pace.
 *
 * <p>Priority 0 -- strictly below {@link net.minecraft.world.entity.ai.goal.PanicGoal} at 1 so
 * combat preempts panic-from-pain, and below every work/sleep/conversation/patrol goal so a
 * citizen attacked mid-chop drops the tool and fights. Shares priority 0 with FloatGoal (no flag
 * conflict: Float claims JUMP, this claims MOVE+LOOK) and with a guard's GuardCombatGoal -- a
 * guard fights ONLY with the smart goal, so this yields for guard-jobbed citizens; likewise a
 * trade courier never fights. Both yields remove an insertion-order slot race.
 */
@ApiStatus.Internal
public class CitizenCombatGoal extends Goal {
    private static final double DEFAULT_BARE_HAND_DAMAGE = 1.0;
    private static final double DEFAULT_BARE_HAND_ATTACK_SPEED = 1.0;
    private static final double MELEE_REACH_SQ = 4.0;
    private static final int REPATH_INTERVAL = 10;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private final double damageOverride;
    private final double attackSpeedOverride;

    @Nullable private LivingEntity currentTarget;
    @Nullable private ItemStack stashedMainHand;
    private int attackCooldown;
    private int repathCooldown;

    public CitizenCombatGoal(CitizenEntity citizen, double speedModifier) {
        this(citizen, speedModifier, -1.0, -1.0);
    }

    public CitizenCombatGoal(CitizenEntity citizen, double speedModifier, double damageOverride,
                             double attackSpeedOverride) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.damageOverride = damageOverride;
        this.attackSpeedOverride = attackSpeedOverride;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (citizen.isOnTradeJourney()) return false;
        if (GuardWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())) return false;
        LivingEntity t = citizen.getTarget();
        if (t == null || !t.isAlive()) return false;
        if (citizen instanceof CombatantCitizen b && b.prefersRanged()
                && citizen.distanceToSqr(t) > 9.0) return false;
        if (citizen.distanceToSqr(t) > 32 * 32) return false;
        currentTarget = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (currentTarget == null || !currentTarget.isAlive()) return false;
        if (citizen.distanceToSqr(currentTarget) > 24 * 24) return false;
        // Release MOVE+LOOK the same tick AvoidEntityGoal needs them, or the citizen slap-fights a
        // primed creeper and eats the blast.
        if (currentTarget instanceof net.minecraft.world.entity.monster.Creeper c
            && c.getSwellDir() > 0) {
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        if (currentTarget == null) return;
        attackCooldown = 0;
        repathCooldown = 0;
        equipWeapon();
        citizen.getNavigation().moveTo(currentTarget, chaseSpeed());
        citizen.setTarget(currentTarget);
    }

    private double chaseSpeed() {
        return citizen instanceof CombatantCitizen b ? speedModifier * b.combatSpeed() : speedModifier;
    }

    @Override
    public void tick() {
        if (currentTarget == null) return;
        citizen.getLookControl().setLookAt(currentTarget, 30.0f, 30.0f);

        double dSq = citizen.distanceToSqr(currentTarget);
        if (dSq <= MELEE_REACH_SQ) {
            citizen.getNavigation().stop();
            if (attackCooldown <= 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                double damage;
                if (damageOverride > 0) {
                    damage = damageOverride;
                } else if (citizen instanceof CombatantCitizen b && b.combatDamage() > 0) {
                    damage = b.combatDamage();
                } else {
                    Settlement s = citizen.getSettlement();
                    damage = s == null ? DEFAULT_BARE_HAND_DAMAGE
                        : s.getWeaponDamageOrDefault(DEFAULT_BARE_HAND_DAMAGE);
                }
                currentTarget.hurt(citizen.damageSources().mobAttack(citizen), (float) damage);
                attackCooldown = attackCooldownTicks();
            }
        } else {
            if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(currentTarget, chaseSpeed());
                repathCooldown = REPATH_INTERVAL;
            }
        }
        if (attackCooldown > 0) attackCooldown--;
    }

    @Override
    public void stop() {
        currentTarget = null;
        citizen.setTarget(null);
        citizen.getNavigation().stop();
        if (stashedMainHand != null) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stashedMainHand);
            stashedMainHand = null;
        }
    }

    private void equipWeapon() {
        stashedMainHand = citizen.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).copy();
        Item melee = citizen instanceof CombatantCitizen b ? b.meleeItem() : null;
        if (melee != null && melee != Items.AIR) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(melee));
            return;
        }
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        Item sword = s.getToolForRole("sword");
        if (sword == Items.AIR) return;
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(sword));
    }

    private int attackCooldownTicks() {
        double atkSpeed;
        if (attackSpeedOverride > 0) {
            atkSpeed = attackSpeedOverride;
        } else if (citizen instanceof CombatantCitizen b && b.combatAttackSpeed() > 0) {
            atkSpeed = b.combatAttackSpeed();
        } else {
            Settlement s = citizen.getSettlement();
            atkSpeed = s == null ? DEFAULT_BARE_HAND_ATTACK_SPEED
                : s.getWeaponAttackSpeedOrDefault(DEFAULT_BARE_HAND_ATTACK_SPEED);
        }
        if (atkSpeed <= 0.0) return 20;
        return Math.max(5, (int) Math.round(20.0 / atkSpeed));
    }
}
