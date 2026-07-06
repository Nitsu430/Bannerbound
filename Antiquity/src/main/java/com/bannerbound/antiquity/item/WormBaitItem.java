package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.entity.WormBaitEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class WormBaitItem extends Item {
    public WormBaitItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack itemstack = player.getItemInHand(usedHand);

        if (!level.isClientSide()) {
            WormBaitEntity entity = new WormBaitEntity(level);
            entity.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());

            Vec3 lookDirection = player.getLookAngle();
            double force = 0.6;
            Vec3 playerVelocity = player.getDeltaMovement();

            double vx = lookDirection.x * force + playerVelocity.x;
            double vy = lookDirection.y * force + 0.15 + playerVelocity.y;
            double vz = lookDirection.z * force + playerVelocity.z;

            entity.setDeltaMovement(vx, vy, vz);

            double horizontalDistance = Math.sqrt(vx * vx + vz * vz);
            if (horizontalDistance > 0.08D) {
                float yaw = (float) (Math.atan2(vz, vx) * (180.0D / Math.PI)) - 90.0F;
                yaw = net.minecraft.util.Mth.wrapDegrees(yaw);

                float pitch = (float) -(Math.atan2(vy, horizontalDistance) * (180.0D / Math.PI));
                pitch = net.minecraft.util.Mth.wrapDegrees(pitch);

                entity.setYRot(yaw);
                entity.setXRot(pitch);

                entity.yRotO = yaw;
                entity.xRotO = pitch;
            }

            level.addFreshEntity(entity);
        }

        if (!player.isCreative()) {
            itemstack.shrink(1);
        }

        return InteractionResultHolder.sidedSuccess(itemstack, level.isClientSide());
    }
}
