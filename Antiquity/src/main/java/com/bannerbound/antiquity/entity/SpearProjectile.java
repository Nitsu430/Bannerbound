package com.bannerbound.antiquity.entity;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import com.bannerbound.antiquity.client.SpearProjectileRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import com.bannerbound.antiquity.event.HuntingEvents;

/**
 * Thrown spear projectile (player and NPC), an {@link AbstractArrow} that flies, sticks in blocks as
 * a recoverable pickup, and glides through water at trident-like 0.99 inertia (instead of an arrow's
 * 0.6) so it can actually reach a fish. The concrete spear ItemStack is synced (DATA_SPEAR) to drive
 * the client model and to serve as the pickup / death-drop item. On hitting a mob it deals damage,
 * applies bleed if enabled, then vanilla-arrow style records itself as a {@link StuckSpear} on the
 * victim's BannerboundAntiquity.STUCK_SPEARS attachment and discards the entity; a render layer draws
 * the stuck spear as part of the mob (no follow-entity, so it tracks smoothly) and
 * StuckSpearDropEvents drops the stored stack on death. addStuckSpear stores the hit point in the
 * renderer's MODEL-SPACE frame by inverting LivingEntityRenderer's transform (mulPose(YP,
 * 180 - yBodyRot), then scale(-1,-1,1), then translate(0,-1.501,0)) with body-relative yaw so the
 * spear keeps pointing the struck direction as the mob turns; the impact point is the precise
 * ray-trace location (the projectile itself has already moved past the body), pulled toward the mob
 * centre by BURY_FRACTION and lifted by STUCK_LIFT so the head sinks into the body instead of
 * floating. On a killing blow the death-drop has already fired before the embed, so recoverable
 * spears are dropped at the impact instead; a killed FISH is special-cased - HuntingEvents bundles
 * the spear into the floating catch during the hurt call.
 *
 * <p>Rope-tethered throws (DATA_ROPE_TETHERED, rope drawn by RopeRenderer) can be reeled back:
 * DATA_REELING is synced because BOTH sides must home the spear while skipping the normal arrow tick
 * (the client otherwise recomputes rotation from the homing velocity and stutter-spins; freezing
 * rotation is what makes the glide smooth), while only the server returns the item and discards on
 * arrival - players get the stack back in inventory, NPC owners get nothing because their projectile
 * was only a copy of a reusable equipped tool. markNoRecovery flags such NPC copies: no block pickup,
 * no death-drop, no stuck-spear pull - the embed is cosmetic-only and expires after
 * NPC_STUCK_DESPAWN_TICKS (~60 s, pruned by HuntingEvents) - since any recovery path would duplicate
 * the citizen's tool. The culling box is inflated because {@link SpearProjectileRenderer} anchors the
 * model's TIP at the entity origin, so the ~2.6-block shaft reaches far beyond the tiny arrow hitbox
 * that vanilla frustum-culls on - without the inflation a stuck spear whose hitbox sits just
 * off-screen blanks out while still pick-up-able. canHitEntity skips GroundDecalEntity: those
 * cosmetic decals are isPickable() for cursor clicks, which also makes the arrow ray-trace treat them
 * as solid targets that would eat the spear mid-flight.
 */
public class SpearProjectile extends AbstractArrow {
    private static final int MAX_STUCK = 8;   // must match the wire cap in StuckSpear
    private static final double BURY_FRACTION = 0.5;
    private static final double STUCK_LIFT = 0.45;

    private static final EntityDataAccessor<ItemStack> DATA_SPEAR =
        SynchedEntityData.defineId(SpearProjectile.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> DATA_ROPE_TETHERED =
        SynchedEntityData.defineId(SpearProjectile.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_REELING =
        SynchedEntityData.defineId(SpearProjectile.class, EntityDataSerializers.BOOLEAN);

    private static final double REEL_SPEED = 1.4;

    public SpearProjectile(EntityType<? extends SpearProjectile> type, Level level) {
        super(type, level);
    }

    public SpearProjectile(Level level, LivingEntity shooter, ItemStack spear, double damage) {
        super(BannerboundAntiquity.SPEAR_PROJECTILE.get(), shooter, level, spear.copy(), null);
        this.entityData.set(DATA_SPEAR, spear.copy());

        this.setBaseDamage(damage);
        this.pickup = AbstractArrow.Pickup.ALLOWED;
    }

    public void setCreativeOnlyPickup() {
        this.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
    }

    private boolean dropsRecoverable = true;

    private static final int NPC_STUCK_DESPAWN_TICKS = 1200;

    public void markNoRecovery() {
        this.dropsRecoverable = false;
        this.pickup = AbstractArrow.Pickup.DISALLOWED;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SPEAR, new ItemStack(BannerboundAntiquity.WOODEN_SPEAR.get()));
        builder.define(DATA_ROPE_TETHERED, false);
        builder.define(DATA_REELING, false);
    }

    public boolean isReeling() {
        return this.entityData.get(DATA_REELING);
    }

    public ItemStack getSpearItem() {
        return this.entityData.get(DATA_SPEAR);
    }

    public boolean isRopeTethered() {
        return this.entityData.get(DATA_ROPE_TETHERED);
    }

    public void setRopeTethered(boolean tethered) {
        this.entityData.set(DATA_ROPE_TETHERED, tethered);
    }

    public void startReeling() {
        this.entityData.set(DATA_REELING, true);
    }

    @Override
    public void tick() {
        if (isReeling()) {
            reelTowardOwner();
            return;   // BOTH sides home; the skipped arrow tick would re-spin the client from velocity
        }
        super.tick();
    }

    private void reelTowardOwner() {
        if (!(this.getOwner() instanceof LivingEntity owner) || !owner.isAlive()) {
            if (!this.level().isClientSide) {
                this.entityData.set(DATA_REELING, false);
            }
            return;
        }
        this.inGround = false;
        Vec3 toOwner = owner.position().add(0.0, owner.getBbHeight() * 0.5, 0.0).subtract(this.position());
        if (!this.level().isClientSide && toOwner.length() < 1.2) {
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                ItemStack give = getSpearItem().copy();
                if (!player.getInventory().add(give)) {
                    player.drop(give, false);
                }
            }
            this.discard();
            return;
        }
        Vec3 velocity = toOwner.normalize().scale(REEL_SPEED);
        this.setDeltaMovement(velocity);
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(BannerboundAntiquity.WOODEN_SPEAR.get());
    }

    @Override
    protected float getWaterInertia() {
        return 0.99F;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDefaultHitGroundSoundEvent() {
        return BannerboundAntiquity.SPEAR_HIT_BLOCK_SOUND.get();
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(3.0);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && !(target instanceof GroundDecalEntity);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity target = result.getEntity();
        Entity owner = this.getOwner();
        DamageSource source = this.damageSources().arrow(this, owner == null ? this : owner);
        boolean hurt = target.hurt(source, (float) this.getBaseDamage());
        if (!this.level().isClientSide) {
            if (hurt && target instanceof LivingEntity living) {
                Vec3 fleshHit = result.getLocation();
                this.level().playSound(null, fleshHit.x, fleshHit.y, fleshHit.z,
                    BannerboundAntiquity.SPEAR_HIT_FLESH_SOUND.get(), net.minecraft.sounds.SoundSource.PLAYERS,
                    0.9F, 0.8F + this.random.nextFloat() * 0.4F);
                if (Config.BLEED_ENABLED.get()) {
                    HuntingFear.applyBleed(living, Config.BLEED_DURATION_TICKS.get(), owner);
                }
                if (living instanceof AbstractFish && this.level() instanceof ServerLevel serverLevel) {
                    Vec3 hit = result.getLocation();
                    serverLevel.sendParticles(BannerboundAntiquity.BLOOD_DROP.get(),
                        hit.x, hit.y, hit.z, 16, 0.15, 0.1, 0.15, 0.15);
                }
                if (living.isAlive()) {
                    addStuckSpear(living, result.getLocation());
                } else if (living instanceof AbstractFish && Config.SPEAR_FISHING_ENABLED.get()) {
                    // Spear already bundled into the floating catch by HuntingEvents - dropping it here would dupe.
                    living.discard();
                } else if (dropsRecoverable) {
                    this.spawnAtLocation(getSpearItem());
                }
            } else if (dropsRecoverable) {
                this.spawnAtLocation(getSpearItem());
            }
        }
        this.discard();
    }

    private void addStuckSpear(LivingEntity host, Vec3 hitPos) {
        List<StuckSpear> current = host.getData(BannerboundAntiquity.STUCK_SPEARS.get());
        if (current.size() >= MAX_STUCK) {
            return;
        }
        Vec3 center = host.position().add(0.0, host.getBbHeight() * 0.5, 0.0);
        Vec3 buried = hitPos.lerp(center, BURY_FRACTION).add(0.0, STUCK_LIFT, 0.0);
        Vec3 world = buried.subtract(host.position());
        double rad = Math.toRadians(host.yBodyRot);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        // X/Z negation = the renderer's 180-deg Y flip; without it the spear lands on the OPPOSITE side struck.
        float localX = (float) -(world.x * cos + world.z * sin);
        float localY = (float) (1.501 - world.y);
        float localZ = (float) -(world.x * sin - world.z * cos);
        float bodyYaw = this.getYRot() - host.yBodyRot;
        long expireAt = dropsRecoverable ? -1L
            : this.level().getGameTime() + NPC_STUCK_DESPAWN_TICKS;
        List<StuckSpear> next = new ArrayList<>(current);
        next.add(new StuckSpear(getSpearItem().copy(), localX, localY, localZ, bodyYaw, this.getXRot(),
            dropsRecoverable, expireAt));
        // A NEW immutable list via setData is what triggers the client sync - never mutate the list in place.
        host.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.copyOf(next));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("Spear", getSpearItem().save(this.registryAccess(), new CompoundTag()));
        tag.putBoolean("RopeTethered", isRopeTethered());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Spear")) {
            ItemStack.parse(this.registryAccess(), tag.getCompound("Spear"))
                .ifPresent(s -> this.entityData.set(DATA_SPEAR, s));
        }
        this.entityData.set(DATA_ROPE_TETHERED, tag.getBoolean("RopeTethered"));
    }
}
