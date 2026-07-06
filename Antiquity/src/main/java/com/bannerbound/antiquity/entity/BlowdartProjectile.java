package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * A thrown blowdart. Flies like an arrow but deals almost no impact damage; its job is to deliver a
 * {@link PoisonType} on hit (the real threat is the poison, not the prick, and the owner is passed
 * to Poisons.applyPoison so the eventual poison kill credits the shooter). Always consumed, never
 * recoverable (Pickup.DISALLOWED, discarded on impact) like the no-pickup hunting arrows. One
 * projectile serves every poison: the carried type decides the coating, and it is mirrored into
 * SYNCED entity data because the {@code poison} field is only populated server-side -- the client
 * renderer's tinted tip and the tinted particle trail (client-only in tick(); steady in flight, slow
 * drip once stuck, like a tipped arrow) must read {@link #getPoison()} from that synced data.
 */
public class BlowdartProjectile extends AbstractArrow {
    private static final EntityDataAccessor<String> DATA_POISON =
        SynchedEntityData.defineId(BlowdartProjectile.class, EntityDataSerializers.STRING);

    private PoisonType poison = PoisonType.WOLFSBANE;

    public BlowdartProjectile(EntityType<? extends BlowdartProjectile> type, Level level) {
        super(type, level);
    }

    public BlowdartProjectile(Level level, LivingEntity shooter, PoisonType poison) {
        super(BannerboundAntiquity.BLOWDART_PROJECTILE.get(), shooter, level,
            new ItemStack(BannerboundAntiquity.WOLFSBANE_DART.get()), null);
        this.poison = poison;
        this.entityData.set(DATA_POISON, poison.id());
        this.pickup = Pickup.DISALLOWED;
        this.setBaseDamage(1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_POISON, PoisonType.WOLFSBANE.id());
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return getDartItem();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            if (this.inGround) {
                if (this.inGroundTime % 5 == 0) {
                    spawnPoisonTrail(1);
                }
            } else {
                spawnPoisonTrail(2);
            }
        }
    }

    private void spawnPoisonTrail(int count) {
        int c = getPoison().tintColor();
        float r = (c >> 16 & 0xFF) / 255.0F;
        float g = (c >> 8 & 0xFF) / 255.0F;
        float b = (c & 0xFF) / 255.0F;
        for (int i = 0; i < count; i++) {
            this.level().addParticle(
                ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, r, g, b),
                this.getRandomX(0.5), this.getRandomY(), this.getRandomZ(0.5), 0.0, 0.0, 0.0);
        }
    }

    public PoisonType getPoison() {
        PoisonType t = PoisonType.fromId(this.entityData.get(DATA_POISON));
        return t == null ? PoisonType.WOLFSBANE : t;
    }

    public ItemStack getDartItem() {
        // AbstractArrow's super-ctor calls this BEFORE 'poison' is assigned; == tolerates null, a switch NPEs.
        return new ItemStack(poison == PoisonType.BELLADONNA
            ? BannerboundAntiquity.NIGHTSHADE_DART.get()
            : poison == PoisonType.CURARE
            ? BannerboundAntiquity.CURARE_DART.get()
            : poison == PoisonType.OLEANDER
            ? BannerboundAntiquity.OLEANDER_DART.get()
            : BannerboundAntiquity.WOLFSBANE_DART.get());
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity target = result.getEntity();
        Entity owner = this.getOwner();
        DamageSource source = this.damageSources().arrow(this, owner == null ? this : owner);
        target.hurt(source, (float) this.getBaseDamage());
        if (!this.level().isClientSide && target instanceof LivingEntity living && living.isAlive()) {
            Poisons.applyPoison(living, poison, owner);
        }
        this.discard();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Poison", poison.id());
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        PoisonType t = PoisonType.fromId(tag.getString("Poison"));
        if (t != null) {
            poison = t;
            this.entityData.set(DATA_POISON, poison.id());
        }
    }
}
