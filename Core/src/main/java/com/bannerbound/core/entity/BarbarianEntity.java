package com.bannerbound.core.entity;

import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.barbarian.BarbarianCamp;
import com.bannerbound.core.barbarian.BarbarianData;
import com.bannerbound.core.barbarian.BarbarianRangedGoal;
import com.bannerbound.core.barbarian.CampRelationState;
import com.bannerbound.core.barbarian.CampWanderGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A barbarian camp member - its OWN entity type, subclassing CitizenEntity so it reuses the citizen
 * model/skin/render and base behaviour but is a distinct class. Being a separate type means
 * HurtByTargetGoal.setAlertOthers() rallies only other barbarians (not the player's plain
 * CitizenEntity citizens), and player citizens target barbarians via a clean instanceof check. All
 * barbarian-specific state/AI lives here, off CitizenEntity.
 *
 * registerGoals installs a bespoke combat + hostile-targeting set and deliberately does NOT call
 * super.registerGoals(), so barbarians skip the citizen work/patrol/conversation goals and especially
 * PanicGoal - a struck barbarian fights instead of fleeing. Data-dependent goals (camp wander/leash,
 * ranged) are added later in markBarbarianMember, which must be called once after addFreshEntity to set
 * weapons, camp membership, and per-member chase-speed variance (centred well below the citizen base
 * because barbarians were closing far too fast). A kiting bowman holds range and only melees when
 * cornered. A messenger is a travelling diplomat sent to a SPECIFIC settlement: markMessenger strips all
 * combat/wander AI (MessengerManager drives its navigation) and right-click opens the parley barter
 * screen instead of a fight.
 */
public class BarbarianEntity extends CitizenEntity implements CombatantCitizen {
    private BlockPos campCenter = null;
    private UUID campId = null;
    private double damage = -1.0;
    private double attackSpeed = -1.0;
    private Item rangedItem = null;
    private Item meleeItem = null;
    private boolean kite = false;
    private double combatSpeed = 1.0;
    private boolean messenger = false;
    private UUID messengerSettlementId = null;

    public BarbarianEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return CitizenEntity.createAttributes();
    }

    @Override
    protected void registerGoals() {
        // Never call super.registerGoals() here: it would re-add PanicGoal and make a struck barbarian flee instead of fight.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new CitizenCombatGoal(this, 0.7));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class,
            10, true, false, (Predicate<LivingEntity>) this::isCampEnemy));
    }

    public void markBarbarianMember(BlockPos campCenter, UUID campId, double damage, double attackSpeed,
                                    Item weapon, boolean ranged, @Nullable ResourceLocation projectileId,
                                    Item meleeWeapon, boolean kite) {
        this.campCenter = campCenter;
        this.campId = campId;
        this.damage = damage;
        this.attackSpeed = attackSpeed;
        this.rangedItem = (ranged && weapon != null && weapon != Items.AIR) ? weapon : null;
        this.meleeItem = (meleeWeapon != null && meleeWeapon != Items.AIR) ? meleeWeapon : weapon;
        this.kite = kite && ranged;
        this.combatSpeed = 0.85 + getRandom().nextDouble() * 0.30;
        Item held = this.kite && rangedItem != null ? rangedItem : meleeItem;
        if (held != null && held != Items.AIR) {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(held));
            this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        }
        this.goalSelector.addGoal(5, new CampWanderGoal(this, campCenter, 10, 0.8));
        if (ranged && projectileId != null) {
            this.goalSelector.addGoal(3, new BarbarianRangedGoal(this, projectileId, damage, this.kite));
        }
    }

    public BlockPos campCenter() { return campCenter; }
    public UUID campId() { return campId; }
    public double combatDamage() { return damage; }
    public double combatAttackSpeed() { return attackSpeed; }
    public Item meleeItem() { return meleeItem; }
    public Item rangedItem() { return rangedItem; }

    public boolean prefersRanged() { return kite; }

    public double combatSpeed() { return combatSpeed; }

    public boolean isCommander() {
        if (campId == null || !(level() instanceof ServerLevel sl)) return false;
        BarbarianCamp camp = BarbarianData.get(sl).getById(campId);
        return camp != null && camp.commanderIds.contains(getUUID());
    }

    public boolean isMessenger() { return messenger; }
    public UUID messengerSettlementId() { return messengerSettlementId; }

    public void markMessenger(BlockPos campCenter, UUID campId, UUID settlementId) {
        this.campCenter = campCenter;
        this.campId = campId;
        this.messenger = true;
        this.messengerSettlementId = settlementId;
        clearAllGoals();
        setCustomNameVisible(true);
    }

    public void clearAllGoals() {
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);
    }

    @Override
    public net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                                             net.minecraft.world.InteractionHand hand) {
        if (messenger && !level().isClientSide
                && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.bannerbound.core.barbarian.MessengerManager.openBarter(sp, this);
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    private boolean isCampEnemy(LivingEntity e) {
        if (e == this || e == null || !e.isAlive()) return false;
        if (campId == null || !(level() instanceof ServerLevel sl)) return false;
        BarbarianCamp camp = BarbarianData.get(sl).getById(campId);
        if (camp == null) return false;
        if (e instanceof Player p) {
            return campHostileTo(camp, SettlementData.get(sl).getByPlayer(p.getUUID()));
        }
        if (e instanceof BarbarianEntity) return false;
        if (e instanceof CitizenEntity c) {
            return campHostileTo(camp, c.getSettlement());
        }
        return false;
    }

    public static boolean campHostileTo(BarbarianCamp camp, Settlement s) {
        if (camp.type.isAlwaysHostile()) return true;
        if (s == null) return camp.type.defaultRelation() == CampRelationState.HOSTILE;
        return camp.relationToward(s.id()) == CampRelationState.HOSTILE;
    }
}
