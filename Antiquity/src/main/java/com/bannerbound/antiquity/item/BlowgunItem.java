package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * A bamboo blowgun - fires a {@link PoisonDart} from the inventory as ammo (any poison's dart
 * works), and draws like a bow: hold to build breath, release to puff. Power ramps over
 * FULL_DRAW_TICKS (~1.2s) on the bow's accelerating curve; a longer draw flings the dart faster,
 * straighter (inaccuracy shrinks to zero at full draw) and further - far beyond a hand throw,
 * which is the weak desperation option. Reusable; one dart per shot; a sub-0.12-power release is
 * a wasted breath, not a shot. Uses the TOOT_HORN "raised to the mouth" pose: vanilla renders it
 * natively in third person, but its first-person switch has no TOOT_HORN case, so
 * FirstPersonTootHornMixin supplies that, after which the blowgun_draw model's firstperson
 * display transforms place the tube at the mouth.
 */
public class BlowgunItem extends Item {
    private static final int FULL_DRAW_TICKS = 24;
    private static final float MIN_SPEED = 1.6F;
    private static final float MAX_SPEED = 3.4F;

    public BlowgunItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack blowgun = player.getItemInHand(hand);
        if (findDart(player).isEmpty() && !player.getAbilities().instabuild) {
            return InteractionResultHolder.fail(blowgun);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(blowgun);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // TOOT_HORN pairs with FirstPersonTootHornMixin - vanilla's first-person switch has no TOOT_HORN case.
        return UseAnim.TOOT_HORN;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public void releaseUsing(ItemStack blowgun, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return;
        }
        int charge = getUseDuration(blowgun, entity) - timeLeft;
        float power = drawPower(charge);
        if (power < 0.12F) {
            return;
        }
        boolean creative = player.getAbilities().instabuild;
        ItemStack dart = findDart(player);
        if (dart.isEmpty() && !creative) {
            return;
        }
        PoisonType poison = (dart.getItem() instanceof PoisonDart pd) ? pd.poison() : PoisonType.WOLFSBANE;
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            com.bannerbound.antiquity.BannerboundAntiquity.BLOWGUN_SHOOT.get(),
            SoundSource.PLAYERS, 0.8F, 0.85F + power * 0.4F);
        if (!level.isClientSide) {
            BlowdartProjectile d = new BlowdartProjectile(level, player, poison);
            float speed = Mth.lerp(power, MIN_SPEED, MAX_SPEED);
            d.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
                speed, (1.0F - power) * 1.5F);
            level.addFreshEntity(d);
        }
        if (!creative && !dart.isEmpty()) {
            dart.shrink(1);
        }
        player.getCooldowns().addCooldown(this, 8);
    }

    private static float drawPower(int charge) {
        float f = charge / (float) FULL_DRAW_TICKS;
        f = (f * f + f * 2.0F) / 3.0F;
        return Mth.clamp(f, 0.0F, 1.0F);
    }

    private static ItemStack findDart(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof PoisonDart) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }
}
