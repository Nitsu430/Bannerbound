package com.bannerbound.antiquity.item;

import java.util.List;

import javax.annotation.Nullable;

import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import com.bannerbound.antiquity.craft.Fletching;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * The hand-fletched Primitive Bow. A {@link BowItem} whose arrow velocity scales with the bow's
 * craftsmanship quality on top of a base handicap: at {@link QualityTier#STANDARD} it shoots
 * {@link #BASE_VELOCITY_FACTOR} of a vanilla bow's speed (a primitive bow is simply worse wood
 * by design), a Crude one noticeably less, and a Fine one approaches vanilla. A composite
 * arrow's back/fletching part additionally scales shot spread (feather flies tight, raw fiber
 * scatters). Durability is scaled separately at craft time via the MAX_DAMAGE component (see
 * {@code Fletching.complete}). The creative-ammo override makes creative players with no ammo
 * draw a basic composite arrow rather than the vanilla-arrow fallback hardcoded in
 * {@code Player.getProjectile}.
 */
public class PrimitiveBowItem extends BowItem {
    public static final float BASE_VELOCITY_FACTOR = 0.9F;

    public PrimitiveBowItem(Properties properties) {
        super(properties);
    }

    @Override
    protected void shoot(ServerLevel level, LivingEntity shooter, InteractionHand hand,
                         ItemStack weapon, List<ItemStack> projectileItems, float velocity,
                         float inaccuracy, boolean isCrit, @Nullable LivingEntity target) {
        float scaled = velocity * BASE_VELOCITY_FACTOR * QualityTier.of(weapon).statMultiplier();
        float spread = inaccuracy;
        if (!projectileItems.isEmpty()
                && projectileItems.get(0).getItem()
                    instanceof com.bannerbound.antiquity.item.CompositeArrowItem) {
            spread *= com.bannerbound.antiquity.item.ArrowParts.inaccuracyMultiplier(projectileItems.get(0));
        }
        super.shoot(level, shooter, hand, weapon, projectileItems, scaled, spread, isCrit, target);
    }

    @Override
    public ItemStack getDefaultCreativeAmmo(@Nullable net.minecraft.world.entity.player.Player player,
                                            ItemStack weapon) {
        return new ItemStack(com.bannerbound.antiquity.BannerboundAntiquity.ARROW.get());
    }
}
