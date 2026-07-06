package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.ThrownRock;
import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * A slingshot - the crude, pre-Archery ranged weapon, fletched before the bow. Draws like a bow
 * (hold to wind up, release to fire; the BOW use-anim plus the pull/pulling item-model predicates
 * drive the draw-frame sprites) and flings a {@link ThrownRock} using any {@link ThrownRockBlockItem}
 * in the inventory (stone / sandstone / red sandstone rock) as ammo; the fired stack is carried on
 * the projectile so it renders and shatters as that stone (creative players with no ammo default to
 * a stone rock). A slung rock hits far harder than a hand throw ({@link #ROCK_DAMAGE} vs 1) and
 * trades the hand-throw's stun for that lethality, but it is plainly worse than a bow: launch speed
 * ramps 1.4->2.4 over a ~1.1s charge (vanilla BowItem's accelerating power curve; a hand throw is
 * 1.5, an arrow far faster) and spread tightens 6->2 degrees toward full draw where a bow holds ~1.
 * Releasing under {@link #MIN_FIRE_POWER} of a full draw is a dud (no shot). Quality (rolled at the
 * fletching station) steadies the shot and scales durability; it takes one point of wear per shot,
 * like a bow.
 */
public class SlingshotItem extends Item {
    private static final int FULL_DRAW_TICKS = 22;
    private static final float MIN_SPEED = 1.4F;
    private static final float MAX_SPEED = 2.4F;
    private static final float MIN_INACCURACY = 2.0F;
    private static final float MAX_INACCURACY = 6.0F;
    private static final float ROCK_DAMAGE = 4.0F;
    private static final float MIN_FIRE_POWER = 0.12F;

    public SlingshotItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack slingshot = player.getItemInHand(hand);
        if (findRock(player).isEmpty() && !player.getAbilities().instabuild) {
            return InteractionResultHolder.fail(slingshot);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            BannerboundAntiquity.SLING_PULL.get(), SoundSource.PLAYERS, 0.8F, 1.0F);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(slingshot);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public void releaseUsing(ItemStack slingshot, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return;
        }
        int charge = getUseDuration(slingshot, entity) - timeLeft;
        float power = drawPower(charge);
        if (power < MIN_FIRE_POWER) {
            return;
        }
        boolean creative = player.getAbilities().instabuild;
        ItemStack rock = findRock(player);
        if (rock.isEmpty() && !creative) {
            return;
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            BannerboundAntiquity.SLING_SHOT.get(), SoundSource.PLAYERS, 0.9F, 1.0F + power * 0.3F);

        if (!level.isClientSide) {
            ThrownRock thrown = new ThrownRock(level, player);
            ItemStack ammo = rock.isEmpty() ? new ItemStack(BannerboundAntiquity.STONE_ROCK_ITEM.get()) : rock;
            thrown.setItem(ammo.copyWithCount(1));
            thrown.setImpactDamage(ROCK_DAMAGE);
            thrown.setStun(false);
            float speed = Mth.lerp(power, MIN_SPEED, MAX_SPEED);
            float inaccuracy = Mth.lerp(power, MAX_INACCURACY, MIN_INACCURACY)
                * (2.0F - QualityTier.of(slingshot).statMultiplier());
            thrown.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, speed, inaccuracy);
            level.addFreshEntity(thrown);
        }

        if (!creative && !rock.isEmpty()) {
            rock.shrink(1);
        }
        slingshot.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        player.getCooldowns().addCooldown(this, 10);
    }

    private static float drawPower(int charge) {
        float f = charge / (float) FULL_DRAW_TICKS;
        f = (f * f + f * 2.0F) / 3.0F;
        return Mth.clamp(f, 0.0F, 1.0F);
    }

    private static ItemStack findRock(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof ThrownRockBlockItem) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }
}
