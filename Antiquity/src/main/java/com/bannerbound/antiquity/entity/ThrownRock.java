package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown rock - chucked by hand like a snowball, or flung far harder from a {@code SlingshotItem}.
 * On impact it shatters into the matching stone's break particles (stone / sandstone / red sandstone,
 * chosen from the carried rock item, which is preserved so it both renders and shatters as the
 * specific rock thrown) and plays the {@code rock_impact} sound. Impact behaviour is configurable so
 * one projectile covers both launch paths: a hand throw deals minimal damage (1) and applies a brief
 * rooting stun (2s of amplifier-6 slowness), while a slingshot sets a heavier
 * {@link #setImpactDamage(float)} (about 4) with the stun usually off - a sling is a killing weapon,
 * not a stunner. Damage and stun settings persist to NBT.
 */
public class ThrownRock extends ThrowableItemProjectile {
    private static final int STUN_TICKS = 40;
    private static final int STUN_AMPLIFIER = 6;
    private static final float DEFAULT_DAMAGE = 1.0F;

    private float impactDamage = DEFAULT_DAMAGE;
    private boolean stun = true;

    public ThrownRock(EntityType<? extends ThrownRock> type, Level level) {
        super(type, level);
    }

    public ThrownRock(Level level, LivingEntity shooter) {
        super(BannerboundAntiquity.THROWN_ROCK.get(), shooter, level);
    }

    public void setImpactDamage(float damage) {
        this.impactDamage = damage;
    }

    public void setStun(boolean stun) {
        this.stun = stun;
    }

    @Override
    protected Item getDefaultItem() {
        return BannerboundAntiquity.STONE_ROCK_ITEM.get();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        Entity target = result.getEntity();
        Entity owner = getOwner();
        target.hurt(damageSources().thrown(this, owner), impactDamage);
        if (!level().isClientSide && stun && target instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SLOWDOWN, STUN_TICKS, STUN_AMPLIFIER, false, true));
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!level().isClientSide) {
            Vec3 at = result.getLocation();
            if (level() instanceof ServerLevel server) {
                server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, impactState()),
                    at.x, at.y, at.z, 12, 0.15, 0.15, 0.15, 0.05);
            }
            level().playSound(null, at.x, at.y, at.z, BannerboundAntiquity.ROCK_IMPACT.get(),
                SoundSource.PLAYERS, 0.7F, 0.9F + level().getRandom().nextFloat() * 0.2F);
            discard();
        }
    }

    private BlockState impactState() {
        Item item = getItem().getItem();
        if (item == BannerboundAntiquity.SANDSTONE_ROCK_ITEM.get()) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (item == BannerboundAntiquity.RED_SANDSTONE_ROCK_ITEM.get()) {
            return Blocks.RED_SANDSTONE.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("ImpactDamage", impactDamage);
        tag.putBoolean("Stun", stun);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("ImpactDamage")) {
            impactDamage = tag.getFloat("ImpactDamage");
        }
        if (tag.contains("Stun")) {
            stun = tag.getBoolean("Stun");
        }
    }
}
