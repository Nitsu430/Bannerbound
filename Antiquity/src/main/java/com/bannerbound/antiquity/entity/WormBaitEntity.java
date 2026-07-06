package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class WormBaitEntity extends Entity {
    public enum Phase {
        FALLING(0),
        WATER_CHAOS(1),
        FLEE(2);

        private final int code;

        private static final Map<Integer, Phase> BY_CODE = new HashMap<>();

        static {
            for (Phase status : values()) {
                BY_CODE.put(status.code, status);
            }
        }

        Phase(int code) {
            this.code = code;
        }

        public int getCode() {
            return this.code;
        }

        public static Phase fromCode(int code) {
            return BY_CODE.get(code);
        }
    }

    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(WormBaitEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_FLEE_TICKS =
            SynchedEntityData.defineId(WormBaitEntity.class, EntityDataSerializers.INT);

    private static final double GRAVITY = 0.04D;
    private static final double AIR_DRAG = 0.98D;
    private static final double GROUND_FRICTION = 0.6D;

    public WormBaitEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    public WormBaitEntity(Level level) {
        this(BannerboundAntiquity.WORM_BAIT.get(), level);
    }

    @Override
    public void tick() {
        super.tick();

        switch (getBaitPhase()) {
            case Phase.FALLING:
                tickFalling();
                return;

            case Phase.WATER_CHAOS:
                tickWaterChaos();
                return;

            case Phase.FLEE:
                tickFlee();
                return;
        }
    }

    private void tickFalling() {
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();

        Vec3 velocity = this.getDeltaMovement();

        if (!this.onGround()) {
            if (this.isInWater()) {
                setBaitPhase(Phase.WATER_CHAOS);
                return;
            }

            this.setDeltaMovement(
                    velocity.x * AIR_DRAG,
                    (velocity.y - GRAVITY) * AIR_DRAG,
                    velocity.z * AIR_DRAG
            );

            double horizontalDistance = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            if (horizontalDistance > 0.08D) {
                float yaw = (float) (Math.atan2(velocity.z, velocity.x) * (180.0D / Math.PI)) - 90.0F;
                yaw = net.minecraft.util.Mth.wrapDegrees(yaw);

                float pitch = (float) -(Math.atan2(velocity.y, horizontalDistance) * (180.0D / Math.PI));
                pitch = net.minecraft.util.Mth.wrapDegrees(pitch);

                this.setYRot(yaw);
                this.setXRot(pitch);
            }
        } else {
            this.setDeltaMovement(
                    velocity.x * GROUND_FRICTION,
                    velocity.y,
                    velocity.z * GROUND_FRICTION
            );

            if (velocity.length() < 0.01) {
                this.setBaitPhase(Phase.FLEE);
            }
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
    }

    private void tickWaterChaos() {
        int ticks = this.getFleeTicks();

        if (this.level().isClientSide()) {
            if (ticks % 40 == 39 && ticks != 199) {
                this.level().addParticle(
                        net.minecraft.core.particles.ParticleTypes.BUBBLE,
                        this.getX(), this.getY(), this.getZ(),
                        0.0, 0.05, 0.0
                );

                this.level().playLocalSound(
                        this.getX(), this.getY(), this.getZ(),
                        SoundEvents.VILLAGER_TRADE,
                        net.minecraft.sounds.SoundSource.NEUTRAL,
                        0.3F, 1.0F, false
                );
            }
        }

        if (ticks < 200) {
            this.setFleeTicks(ticks + 1);
        } else {
            if (this.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        ParticleTypes.HEART,
                        this.getX(), this.getY() + 0.3, this.getZ(),
                        8,
                        0.2, 0.2, 0.2,
                        0.02
                );

                serverLevel.playSound(
                        null,
                        this.getX(), this.getY(), this.getZ(),
                        SoundEvents.VILLAGER_CELEBRATE,
                        SoundSource.NEUTRAL,
                        1.0F,
                        1.0F
                );

                EntityType<? extends AbstractFish> fishType;

                int selected_fish = (int) Mth.randomBetween(RandomSource.create(), 0, 10); // more chance for it to be salmon

                fishType = switch (selected_fish) {
                    case 1 -> EntityType.TROPICAL_FISH;
                    case 2 -> EntityType.COD;
                    case 3 -> EntityType.PUFFERFISH;
                    default -> EntityType.SALMON;
                };

                AbstractFish fish = fishType.create(this.level());
                if (fish == null) {
                    this.discard();
                    return;
                }

                fish.moveTo(
                        this.getX(),
                        this.getY(),
                        this.getZ(),
                        this.random.nextFloat() * 360.0F,
                        0.0F
                );

                fish.finalizeSpawn(
                        serverLevel,
                        serverLevel.getCurrentDifficultyAt(fish.blockPosition()),
                        net.minecraft.world.entity.MobSpawnType.MOB_SUMMONED,
                        null
                );
                this.level().addFreshEntity(fish);
            }

            this.discard();
        }
    }

    private void tickFlee() {
        int ticks = this.getFleeTicks();

        if (ticks % 5 == 0 && ticks < 60) {
            if (this.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                    new net.minecraft.core.particles.BlockParticleOption(
                            net.minecraft.core.particles.ParticleTypes.BLOCK,
                            serverLevel.getBlockState(new BlockPos(this.getBlockX(), this.getBlockY() - 1, this.getBlockZ()))
                    ),
                    this.getX(), this.getY(), this.getZ(),
                    4,
                    0.05, 0.05, 0.05,
                    0.01
                );
            }

        }

        if (ticks < 120) {
            this.setFleeTicks(ticks + 1);
        } else {
            this.discard();
        }
    }


    public void setBaitPhase(Phase phase) {
        this.entityData.set(DATA_PHASE, phase.getCode());
    }

    public Phase getBaitPhase() {
        return Phase.fromCode(this.entityData.get(DATA_PHASE));
    }

    public int getFleeTicks() {
        return this.entityData.get(DATA_FLEE_TICKS);
    }

    public void setFleeTicks(int ticks) {
        this.entityData.set(DATA_FLEE_TICKS, ticks);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.@NotNull Builder builder) {
        builder.define(DATA_PHASE, Phase.FALLING.getCode());
        builder.define(DATA_FLEE_TICKS, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("BaitPhase")) {
            setBaitPhase(Phase.fromCode(tag.getInt("BaitPhase")));
        }
        if (tag.contains("FleeTicks")) {
            setFleeTicks(tag.getInt("FleeTicks"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("BaitPhase", getBaitPhase().getCode());
        tag.putInt("FleeTicks", getFleeTicks());
    }
}
