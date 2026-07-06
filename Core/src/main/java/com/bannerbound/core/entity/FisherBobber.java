package com.bannerbound.core.entity;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Custom fishing bobber projectile owned by a {@link CitizenEntity} during the CAST/WAIT phase of
 * the fisher work goal. Server-side it tracks position plus an "in water" state and runs a
 * vanilla-style lure/approach/bite simulation; {@link com.bannerbound.core.client.FisherBobberRenderer}
 * reads position to draw either a floating bob or a falling sprite, and reads the synced owner id
 * ({@link #OWNER_ID}) to draw the fishing line back to the citizen's hand. {@link FisherWorkGoal}
 * polls {@link #isHooked()} and reels in during the bite window.
 * <p>
 * We do not reuse vanilla {@link net.minecraft.world.entity.projectile.FishingHook}: it is tightly
 * coupled to {@code Player} (constructor, owner field, retract sync). The lure sequence mirrors
 * {@code FishingHook.catchingFish}: timeUntilLured (idle wait, occasional far interest splashes)
 * -> timeUntilHooked (a FISHING V-wake homes toward the float) -> bite (splash burst + downward
 * yank that opens the nibble window). The whole sim is server-side; clients see it via position +
 * particle sync. On first water contact the float snaps once to the surface (snapping every tick
 * would cancel the bob oscillation).
 * <p>
 * Tuning: base bite rates are deliberately halved from vanilla (NPC fishing was too fast), and
 * deep water halves them again via depthBiteFactor (up to 2x faster at DEEP_WATER=4 blocks below
 * the float), so a fisher who reaches a deep lake out-fishes the shore. The bobber is transient
 * and never persisted ({@link #shouldBeSaved()} is false): synced data does not round-trip a save
 * and the citizen's runtime entity id changes on reload, so a saved bobber would reload as an
 * orphan with no line; instead the fisher goal recreates it on the next cast. The tick loop also
 * discards the bobber if its owner citizen is gone (despawn/exile/death/reload).
 */
public class FisherBobber extends Projectile {
    private static final EntityDataAccessor<Integer> OWNER_ID =
        SynchedEntityData.defineId(FisherBobber.class, EntityDataSerializers.INT);

    private static final float GRAVITY = 0.03f;
    private boolean snappedToSurface;
    private int timeUntilLured;
    private int timeUntilHooked;
    private int nibble;
    private float fishAngle;

    public FisherBobber(EntityType<? extends FisherBobber> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    public FisherBobber(Level level, CitizenEntity owner, Vec3 initialVelocity) {
        super(BannerboundCore.FISHER_BOBBER.get(), level);
        this.setOwner(owner);
        this.entityData.set(OWNER_ID, owner.getId());
        Vec3 origin = new Vec3(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
        this.setPos(origin.x, origin.y, origin.z);
        this.setDeltaMovement(initialVelocity);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(OWNER_ID, -1);
    }

    public int getOwnerCitizenId() {
        return this.entityData.get(OWNER_ID);
    }

    public boolean isHooked() {
        return this.nibble > 0;
    }

    public CitizenEntity getOwnerCitizen(Level level) {
        Entity e = level.getEntity(getOwnerCitizenId());
        return e instanceof CitizenEntity c ? c : null;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && getOwnerCitizen(this.level()) == null) {
            this.discard();
            return;
        }
        Vec3 velocity = this.getDeltaMovement();

        boolean inWater = this.level().getFluidState(this.blockPosition()).is(FluidTags.WATER);
        if (inWater) {
            if (!snappedToSurface) {
                this.setPos(this.getX(), Math.floor(this.getY()) + 1.0, this.getZ());
                snappedToSurface = true;
                if (!this.level().isClientSide) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        net.minecraft.sounds.SoundEvents.FISHING_BOBBER_SPLASH,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 0.25f,
                        1.0f + (this.level().getRandom().nextFloat()
                              - this.level().getRandom().nextFloat()) * 0.4f);
                }
            }
            if (this.level() instanceof ServerLevel sl) {
                catchingFish(sl);
            }
            velocity = this.getDeltaMovement();
            if (nibble > 0) {
                this.setDeltaMovement(velocity.x * 0.4, velocity.y, velocity.z * 0.4);
            } else {
                double bob = Math.sin(this.tickCount * 0.1) * 0.02;
                this.setDeltaMovement(velocity.x * 0.4, bob, velocity.z * 0.4);
            }
        } else {
            this.setDeltaMovement(velocity.x * 0.98, velocity.y - GRAVITY, velocity.z * 0.98);
        }

        this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());
    }

    private void catchingFish(ServerLevel sl) {
        if (nibble > 0) {
            nibble--;
            return;
        }
        if (timeUntilHooked > 0) {
            timeUntilHooked--;
            if (timeUntilHooked > 0) {
                fishAngle += (float) random.triangle(0.0, 9.188);
                float rad = fishAngle * ((float) Math.PI / 180.0F);
                float sin = Mth.sin(rad);
                float cos = Mth.cos(rad);
                double px = this.getX() + sin * timeUntilHooked * 0.1F;
                double py = Math.floor(this.getY()) + 1.0;
                double pz = this.getZ() + cos * timeUntilHooked * 0.1F;
                if (sl.getBlockState(BlockPos.containing(px, py - 1.0, pz)).is(Blocks.WATER)) {
                    if (random.nextFloat() < 0.15F) {
                        sl.sendParticles(ParticleTypes.BUBBLE, px, py - 0.1, pz, 1, sin, 0.1, cos, 0.0);
                    }
                    float fx = sin * 0.04F;
                    float fz = cos * 0.04F;
                    sl.sendParticles(ParticleTypes.FISHING, px, py, pz, 0, fz, 0.01, -fx, 1.0);
                    sl.sendParticles(ParticleTypes.FISHING, px, py, pz, 0, -fz, 0.01, fx, 1.0);
                }
            } else {
                this.setDeltaMovement(this.getDeltaMovement().x,
                    -0.4F * Mth.nextFloat(random, 0.6F, 1.0F), this.getDeltaMovement().z);
                this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25F,
                    1.0F + (random.nextFloat() - random.nextFloat()) * 0.4F);
                double py = this.getY() + 0.5;
                sl.sendParticles(ParticleTypes.BUBBLE, this.getX(), py, this.getZ(), 8, 0.2, 0.0, 0.2, 0.2);
                sl.sendParticles(ParticleTypes.FISHING, this.getX(), py, this.getZ(), 6, 0.2, 0.0, 0.2, 0.2);
                nibble = Mth.nextInt(random, 20, 40);
            }
        } else if (timeUntilLured > 0) {
            timeUntilLured--;
            float chance = 0.15F;
            if (timeUntilLured < 20) chance += (20 - timeUntilLured) * 0.05F;
            else if (timeUntilLured < 40) chance += (40 - timeUntilLured) * 0.02F;
            else if (timeUntilLured < 60) chance += (60 - timeUntilLured) * 0.01F;
            if (random.nextFloat() < chance) {
                float a = Mth.nextFloat(random, 0.0F, 360.0F) * ((float) Math.PI / 180.0F);
                float dist = Mth.nextFloat(random, 25.0F, 60.0F);
                double px = this.getX() + Mth.sin(a) * dist * 0.1;
                double pz = this.getZ() + Mth.cos(a) * dist * 0.1;
                if (sl.getBlockState(BlockPos.containing(px, Math.floor(this.getY()), pz)).is(Blocks.WATER)) {
                    sl.sendParticles(ParticleTypes.SPLASH, px, Math.floor(this.getY()) + 1.0, pz,
                        2 + random.nextInt(2), 0.1, 0.0, 0.1, 0.0);
                }
            }
            if (timeUntilLured <= 0) {
                fishAngle = Mth.nextFloat(random, 0.0F, 360.0F);
                timeUntilHooked = Math.max(1, (int) (Mth.nextInt(random, 40, 160) * depthBiteFactor(sl)));
            }
        } else {
            timeUntilLured = Math.max(1, (int) (Mth.nextInt(random, 120, 400) * depthBiteFactor(sl)));
        }
    }

    private double depthBiteFactor(ServerLevel sl) {
        int depth = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos().set(this.blockPosition());
        while (depth < DEEP_WATER && sl.getFluidState(p).is(FluidTags.WATER)) {
            depth++;
            p.move(0, -1, 0);
        }
        float t = Mth.clamp((depth - 1) / (float) (DEEP_WATER - 1), 0.0F, 1.0F);
        return Mth.lerp(t, 1.0, 0.5);
    }

    private static final int DEEP_WATER = 4;

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSq) {
        return distSq < 4096.0;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket(
            net.minecraft.server.level.ServerEntity serverEntity) {
        return new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(this, serverEntity);
    }

    @Override
    public void recreateFromPacket(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
    }
}
