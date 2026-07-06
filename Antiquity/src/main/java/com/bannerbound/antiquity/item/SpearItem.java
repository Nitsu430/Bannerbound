package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.SpearFishing;
import com.bannerbound.antiquity.entity.SpearProjectile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

/**
 * A primitive spear - a melee weapon that reaches ~2 blocks farther than a sword (an
 * ENTITY_INTERACTION_RANGE modifier: attack range only, block reach untouched) and can be
 * <b>thrown</b>. Hold right-click to wind up (bow-style SPEAR pose; releases under
 * {@link #MIN_CHARGE_TICKS} are ignored so a stray tap can't fling the spear), release to launch
 * a {@link SpearProjectile} along your look. The projectile is the <i>same</i> spear (its full
 * stack/NBT is carried), so it sticks in mobs / blocks and is recovered unchanged on pickup; the
 * thrown hit deals this spear's melee {@link #damage}. Throwing consumes the held spear (free in
 * creative) and, like a melee hit via {@link #hurtEnemy}, costs 1 durability - if that point
 * wears the spear out it shatters on the throw (sound plays, nothing lands). Throw power 1.5 is
 * deliberately under an arrow's full-draw 3.0 and a trident's 2.5 so the heavy spear arcs down
 * sooner and falls short of a bow. With a fiber rope in the OTHER hand and Spear Fishing
 * researched, the throw is rope-tethered: 1 rope is consumed (it becomes the rendered rope) and
 * the spear is reelable.
 *
 * <p>Tiers are configured at registration (see {@code BannerboundAntiquity}): wood/bone 4 dmg,
 * stone 5.5; all 1.2 attack speed; durabilities 59 / 48 / 131.
 */
public class SpearItem extends Item {
    private static final ResourceLocation REACH_MODIFIER_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "spear_reach");
    private static final double REACH_BONUS = 2.0;

    private static final float THROW_POWER = 1.5F;
    private static final float THROW_INACCURACY = 1.0F;
    private static final int MIN_CHARGE_TICKS = 5;

    private final double damage;

    public SpearItem(Properties properties, int durability, double attackDamage, double attackSpeed) {
        super(properties.durability(durability).attributes(spearAttributes(attackDamage, attackSpeed)));
        this.damage = attackDamage;
    }

    // damage/speed are TOTAL displayed values; the player's base (1 dmg, 4 speed) is subtracted here.
    private static ItemAttributeModifiers spearAttributes(double damage, double speed) {
        return ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE,
                new AttributeModifier(BASE_ATTACK_DAMAGE_ID, damage - 1.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED,
                new AttributeModifier(BASE_ATTACK_SPEED_ID, speed - 4.0, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ENTITY_INTERACTION_RANGE,
                new AttributeModifier(REACH_MODIFIER_ID, REACH_BONUS, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.MAINHAND)
            .build();
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
        return true;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        if (!level.isClientSide) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                BannerboundAntiquity.SPEAR_HOLD_SOUND.get(), SoundSource.PLAYERS, 0.8F, throwPitch(level));
        }
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    private static float throwPitch(Level level) {
        return 0.8F + level.getRandom().nextFloat() * 0.4F;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return;
        }
        int charge = this.getUseDuration(stack, entity) - timeLeft;
        if (charge < MIN_CHARGE_TICKS) {
            return;
        }
        if (!level.isClientSide) {
            boolean creative = player.hasInfiniteMaterials();
            ItemStack thrown = stack.copy();
            thrown.setCount(1);
            if (!creative && thrown.isDamageableItem()) {
                thrown.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
            boolean tethered = false;
            if (!thrown.isEmpty()) {
                SpearProjectile spear = new SpearProjectile(level, player, thrown, this.damage);
                spear.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
                    THROW_POWER, THROW_INACCURACY);
                if (creative) {
                    spear.setCreativeOnlyPickup();
                }
                InteractionHand other = player.getUsedItemHand() == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                ItemStack ropeStack = player.getItemInHand(other);
                if (ropeStack.is(BannerboundAntiquity.FIBER_ROPE.get()) && SpearFishing.unlocked(player)) {
                    spear.setRopeTethered(true);
                    tethered = true;
                    if (!creative) {
                        ropeStack.shrink(1);
                    }
                }
                level.addFreshEntity(spear);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                (tethered ? BannerboundAntiquity.SPEAR_THROW_ROPE_SOUND : BannerboundAntiquity.SPEAR_THROW_SOUND).get(),
                SoundSource.PLAYERS, 0.9F, throwPitch(level));
            if (!creative) {
                stack.shrink(1);
            }
        }
        player.awardStat(Stats.ITEM_USED.get(this));
    }
}
