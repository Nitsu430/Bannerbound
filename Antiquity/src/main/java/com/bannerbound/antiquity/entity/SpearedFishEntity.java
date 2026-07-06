package com.bannerbound.antiquity.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.bannerbound.antiquity.event.HuntingEvents;

/**
 * The "catch" left behind when a thrown spear kills a fish (spawned by {@code HuntingEvents}): a
 * standalone entity that renders the spear angled in the water with the fish impaled on its tip
 * ({@code SpearedFishEntityRenderer}), bobs at the surface, and on walk-over pickup grants the
 * server-only {@link #payload} - a copy of the spear plus everything the fish would have dropped -
 * in one go. Unlike a spear stuck in a LIVING mob (which must be a render layer on the body), a
 * dedicated entity is correct here because the fish is dead and follows nothing. The pierce
 * yaw/pitch are synced so the renderer angles the catch the way the spear actually struck.
 *
 * <p>Float physics mirror {@code FisherBobber}: buoyant rise while submerged, then settle
 * {@code SURFACE_SINK} (0.12) below the water block's top so {@code blockPosition()} stays inside
 * the water block - resting exactly at the surface would flip the in-water test each tick and
 * re-fire the splash sound. Out of water it falls under gravity and rests like a dropped item.
 * Catches self-discard after {@code Config.SPEAR_CATCH_LIFETIME_TICKS}.
 *
 * <p>Tethering/reeling: the thrower (a player, or a spear-fisher citizen NPC reusing this entity)
 * is synced BOTH as a UUID (server reel + NBT) and as a live entity id, because the client can only
 * resolve players by UUID - the entity id is what lets the client find a citizen owner so
 * {@code RopeRenderer} can draw the rope back to its hand. Reeling is synced and homed on BOTH
 * sides so the pull-in is visible (the client would otherwise keep floating the catch); only the
 * server grants it on arrival. A player owner receives the whole payload to inventory; a citizen
 * owner deposits only the fish drops into its job drop-off and credits the settlement's "fishing"
 * food production, so the Starvation crisis's spear-fishing path and the Town Hall food stats see
 * spear-caught fish as income (mirrors the rod FisherWorkGoal).
 */
public class SpearedFishEntity extends Entity {
    private static final float GRAVITY = 0.04F;
    private static final int DEFAULT_PICKUP_DELAY = 40;
    private static final double SURFACE_SINK = 0.12;

    private static final EntityDataAccessor<ItemStack> DATA_SPEAR =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<String> DATA_FISH_TYPE =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_FISH_VARIANT =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_TETHERED =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> DATA_OWNER_ENTITY_ID =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_REELING =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_PIERCE_YAW =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_PIERCE_PITCH =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.FLOAT);

    private static final double REEL_SPEED = 1.2;

    private final List<ItemStack> payload = new ArrayList<>();
    private int pickupDelay;
    private boolean snappedToSurface;

    public SpearedFishEntity(EntityType<? extends SpearedFishEntity> type, Level level) {
        super(type, level);
    }

    public SpearedFishEntity(Level level, double x, double y, double z, ItemStack spear,
                             String fishType, int fishVariant, List<ItemStack> drops) {
        this(BannerboundAntiquity.SPEARED_FISH.get(), level);
        this.setPos(x, y, z);
        this.entityData.set(DATA_SPEAR, spear.copy());
        this.entityData.set(DATA_FISH_TYPE, fishType);
        this.entityData.set(DATA_FISH_VARIANT, fishVariant);
        this.payload.add(spear.copy());
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                this.payload.add(drop.copy());
            }
        }
        this.pickupDelay = DEFAULT_PICKUP_DELAY;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SPEAR, new ItemStack(BannerboundAntiquity.WOODEN_SPEAR.get()));
        builder.define(DATA_FISH_TYPE, "minecraft:cod");
        builder.define(DATA_FISH_VARIANT, 0);
        builder.define(DATA_TETHERED, false);
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_OWNER_ENTITY_ID, -1);
        builder.define(DATA_REELING, false);
        builder.define(DATA_PIERCE_YAW, 0.0F);
        builder.define(DATA_PIERCE_PITCH, 0.0F);
    }

    public void setPierce(float yaw, float pitch) {
        this.entityData.set(DATA_PIERCE_YAW, yaw);
        this.entityData.set(DATA_PIERCE_PITCH, pitch);
        this.setYRot(yaw);
    }

    public float getPierceYaw() {
        return this.entityData.get(DATA_PIERCE_YAW);
    }

    public float getPiercePitch() {
        return this.entityData.get(DATA_PIERCE_PITCH);
    }

    public boolean isReeling() {
        return this.entityData.get(DATA_REELING);
    }

    public void setTether(Entity owner) {
        this.entityData.set(DATA_TETHERED, true);
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner == null ? null : owner.getUUID()));
        this.entityData.set(DATA_OWNER_ENTITY_ID, owner == null ? -1 : owner.getId());
    }

    public boolean isTethered() {
        return this.entityData.get(DATA_TETHERED);
    }

    public Optional<UUID> getOwnerUUID() {
        return this.entityData.get(DATA_OWNER);
    }

    public boolean isTetheredTo(Player player) {
        return isTethered() && getOwnerUUID().map(id -> id.equals(player.getUUID())).orElse(false);
    }

    public void startReeling() {
        this.entityData.set(DATA_REELING, true);
    }

    private Entity getOwnerEntity() {
        // Entity id first (resolves citizen owners on the client; UUID cannot) - UUID is only a reload fallback.
        int eid = this.entityData.get(DATA_OWNER_ENTITY_ID);
        if (eid != -1) {
            Entity e = this.level().getEntity(eid);
            if (e != null && e.isAlive()) return e;
        }
        Optional<UUID> id = getOwnerUUID();
        if (id.isEmpty()) return null;
        if (this.level() instanceof ServerLevel sl) return sl.getEntity(id.get());
        return this.level().getPlayerByUUID(id.get());
    }

    public Entity getTetherOwner() {
        return getOwnerEntity();
    }

    public ItemStack getSpearItem() {
        return this.entityData.get(DATA_SPEAR);
    }

    public String getFishType() {
        return this.entityData.get(DATA_FISH_TYPE);
    }

    public int getFishVariant() {
        return this.entityData.get(DATA_FISH_VARIANT);
    }

    @Override
    public void tick() {
        super.tick();
        // Capture last-tick position BEFORE moving so the client interpolates smoothly.
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        if (this.pickupDelay > 0) {
            this.pickupDelay--;
        }

        if (isReeling() && reelTowardOwner()) {
            return;
        }

        Vec3 velocity = this.getDeltaMovement();
        BlockPos pos = this.blockPosition();
        boolean inWater = this.level().getFluidState(pos).is(FluidTags.WATER);
        if (inWater) {
            boolean submerged = this.level().getFluidState(pos.above()).is(FluidTags.WATER);
            if (submerged) {
                this.snappedToSurface = false;
                this.setDeltaMovement(velocity.x * 0.85, 0.08, velocity.z * 0.85);
            } else {
                // Rest UNDER the waterline (SURFACE_SINK) or the bob re-triggers the splash sound.
                double surfaceY = Math.floor(this.getY()) + 1.0;
                double restY = surfaceY - SURFACE_SINK;
                if (!this.snappedToSurface) {
                    this.snappedToSurface = true;
                    if (!this.level().isClientSide) {
                        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.3F,
                            1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
                    }
                }
                double spring = (restY - this.getY()) * 0.2;
                double bob = Math.sin(this.tickCount * 0.1) * 0.008;
                this.setDeltaMovement(velocity.x * 0.6, spring + bob, velocity.z * 0.6);
                if (this.level() instanceof ServerLevel serverLevel) {
                    spawnSurfaceBubbles(serverLevel, surfaceY);
                }
            }
        } else {
            this.snappedToSurface = false;
            this.setDeltaMovement(velocity.x * 0.98, velocity.y - GRAVITY, velocity.z * 0.98);
        }
        this.move(MoverType.SELF, this.getDeltaMovement());
        if (this.onGround()) {
            Vec3 grounded = this.getDeltaMovement();
            this.setDeltaMovement(grounded.x * 0.6, grounded.y, grounded.z * 0.6);
        }

        if (!this.level().isClientSide) {
            if (this.pickupDelay == 0) {
                tryPickup();
            }
            if (this.tickCount >= Config.SPEAR_CATCH_LIFETIME_TICKS.get()) {
                this.discard();
            }
        }
    }

    private boolean reelTowardOwner() {
        Entity owner = getOwnerEntity();
        if (owner == null || !owner.isAlive()) {
            if (!this.level().isClientSide) {
                this.entityData.set(DATA_REELING, false);
            }
            return false;
        }
        Vec3 toOwner = owner.position().add(0.0, owner.getBbHeight() * 0.5, 0.0).subtract(this.position());
        if (!this.level().isClientSide) {
            this.pickupDelay = 0;
            if (toOwner.length() < 1.2) {
                if (owner instanceof Player player) {
                    grantTo(player);
                } else if (owner instanceof com.bannerbound.core.entity.CitizenEntity citizen) {
                    grantToDepot(citizen);
                } else {
                    this.entityData.set(DATA_REELING, false);
                    return false;
                }
                return true;
            }
        }
        Vec3 velocity = toOwner.normalize().scale(REEL_SPEED);
        this.setDeltaMovement(velocity);
        this.move(MoverType.SELF, velocity);
        return true;
    }

    private void spawnSurfaceBubbles(ServerLevel level, double surfaceY) {
        if (this.tickCount % 5 != 0) {
            return;
        }
        level.sendParticles(ParticleTypes.BUBBLE, this.getX(), surfaceY, this.getZ(),
            2, 0.15, 0.0, 0.15, 0.0);
        if (this.random.nextFloat() < 0.3F) {
            level.sendParticles(ParticleTypes.SPLASH, this.getX(), surfaceY, this.getZ(),
                2, 0.15, 0.0, 0.15, 0.1);
        }
    }

    private void tryPickup() {
        for (Player player : this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(0.6, 0.4, 0.6))) {
            if (player.isAlive() && !player.isSpectator()) {
                grantTo(player);
                return;
            }
        }
    }

    private void grantTo(Player player) {
        for (ItemStack stack : this.payload) {
            ItemStack give = stack.copy();
            if (!player.getInventory().add(give)) {
                player.drop(give, false);
            }
        }
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
            ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        this.discard();
    }

    private void grantToDepot(com.bannerbound.core.entity.CitizenEntity citizen) {
        net.minecraft.world.Container depot = com.bannerbound.core.entity.DropOffContainers.resolveJobDepot(citizen);
        int fishCount = 0;
        for (int i = 1; i < this.payload.size(); i++) {   // i=0 is the spear: depositing it would dupe the NPC's reusable tool each catch
            ItemStack give = this.payload.get(i).copy();
            if (give.isEmpty()) continue;
            fishCount += give.getCount();
            ItemStack leftover = depot == null ? give
                : com.bannerbound.core.entity.DropOffContainers.insert(depot, give);
            if (!leftover.isEmpty()) this.spawnAtLocation(leftover);
        }
        com.bannerbound.core.api.settlement.Settlement settlement = citizen.getSettlement();
        if (settlement != null && fishCount > 0) {
            settlement.addFoodProduced("fishing", fishCount);
        }
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
            ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        this.discard();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_SPEAR, loadStack(tag, "Spear"));
        this.entityData.set(DATA_FISH_TYPE, tag.getString("FishType"));
        this.entityData.set(DATA_FISH_VARIANT, tag.getInt("FishVariant"));
        this.entityData.set(DATA_TETHERED, tag.getBoolean("Tethered"));
        this.entityData.set(DATA_OWNER,
            tag.hasUUID("Owner") ? Optional.of(tag.getUUID("Owner")) : Optional.empty());
        this.pickupDelay = tag.getInt("PickupDelay");
        this.payload.clear();
        ListTag list = tag.getList("Payload", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack.parse(this.registryAccess(), list.getCompound(i)).ifPresent(this.payload::add);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ItemStack spear = getSpearItem();
        if (!spear.isEmpty()) {
            tag.put("Spear", spear.save(this.registryAccess(), new CompoundTag()));
        }
        tag.putString("FishType", getFishType());
        tag.putInt("FishVariant", getFishVariant());
        tag.putBoolean("Tethered", isTethered());
        getOwnerUUID().ifPresent(id -> tag.putUUID("Owner", id));
        tag.putInt("PickupDelay", this.pickupDelay);
        ListTag list = new ListTag();
        for (ItemStack stack : this.payload) {
            if (!stack.isEmpty()) {
                list.add(stack.save(this.registryAccess(), new CompoundTag()));
            }
        }
        tag.put("Payload", list);
    }

    private ItemStack loadStack(CompoundTag tag, String key) {
        if (tag.contains(key)) {
            return ItemStack.parse(this.registryAccess(), tag.getCompound(key)).orElse(ItemStack.EMPTY);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }
}
