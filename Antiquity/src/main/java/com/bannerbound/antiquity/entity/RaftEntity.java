package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import com.bannerbound.antiquity.event.AntiquityEvents;
import com.bannerbound.antiquity.rope.RopeTies;
import com.bannerbound.antiquity.rope.RopeAnchor;

/**
 * A primitive raft. Extends vanilla {@link Boat} to inherit water physics, WASD steering, paddle
 * sounds, mount/dismount and leash plumbing for free (vanilla controllers key off
 * {@code instanceof Boat}). Rafts are never crafted: right-clicking a line of 3 thatch blocks with
 * an oar forms one (AntiquityEvents).
 *
 * Integrity: DATA_HEALTH (0..MAX_HEALTH) replaces vanilla's 40-damage destruction rule; each hit
 * removes raw damage x HIT_DAMAGE_SCALE (hurt() still sets vanilla's transient damage/hurtTime so
 * the hurt wobble plays), a thatch bundle restores REPAIR_AMOUNT (repair works beached; boarding
 * requires water), destruction drops 2-3 thatch (no whole-raft item). Integrity drives a speed
 * penalty (lerp toward WRECKED_SPEED_FACTOR) and the float height: {@link #renderFloatHeight()}
 * (1.7 full -> 1.2 wrecked, -0.35 on land) is read by BOTH RaftRenderer (model lift) and the deck
 * collision, so the visible deck and the walkable surface can never disagree.
 *
 * Multipart: a single square AABB cannot rotate, so DECK_PART_COUNT thin slab {@link RaftPart}s
 * tile the hull length (thin so their sides sit below feet -- taller boxes shoved riders sideways
 * each tick as they teleported after the bobbing raft) plus one bow-notch part that is the rope
 * tie target: clicking it IS the aim check, so tieRope() needs none. NOTCH_* offsets are
 * entity-local (+Z toward the bow; flip NOTCH_FORWARD's sign if the rope grabs at the stern). The
 * hull itself reports canBeCollidedWith() = false: only the deck parts are standing surface,
 * otherwise vanilla's taller hull box sat proud of the deck and made walking/dismount "stepped".
 *
 * Leash: Boat never ticks its own leash (only Mobs do), so tick() drives Leashable.tickLeash
 * server-side; past break distance the raft is hauled in (capped) instead of snapping free.
 * Bare-hand tug by the holder detaches. DATA_FIBER_LEASH picks the rope visual (fiber rope vs
 * lead), and reconcileRopedPost() force-holds a rope-fence post's "roped" blockstate while hitched
 * (the tie is a vanilla leash knot, so RopeTies doesn't drive it), reverting on untie/remove.
 *
 * Ghost rafts (DATA_GHOST) are a fisher NPC's conjured vessel: pure scenery -- undamageable, no
 * drops, unboardable by players and by stray citizens (the fisher boards via forced startRiding,
 * which bypasses canAddPassenger), and self-despawning after GHOST_EMPTY_DESPAWN_TICKS with no
 * passenger in case an owner cleanup path is missed. Ghost is written to NBT because synced data
 * alone is not persisted and a reload must not produce a real, repairable raft. Vanilla clears the
 * paddle state whenever the first passenger is not a player and never plays the AI paddle splash,
 * so the ghost branch in tick() derives paddle state from hull motion and plays the splash itself
 * at the vanilla ~16-tick stroke cadence.
 *
 * Buoyancy: vanilla treats "resting on a block under shallow water" as ON_LAND and never lifts the
 * boat (a raft formed in water sank), so applyBuoyancy() eases the hull up to the water surface
 * (up only -- inert on dry land). Physics run only on the controlling instance, matching Boat.
 * Citizens get a -0.7 seat offset: players carry their own seated vehicle-attachment offset, mobs
 * do not, so the same seat point leaves an NPC hovering above the deck.
 */
public class RaftEntity extends Boat {
    private static final EntityDataAccessor<Float> DATA_HEALTH =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_FIBER_LEASH =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_GHOST =
        SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.BOOLEAN);

    public static final float MAX_HEALTH = 100.0F;
    private static final float HIT_DAMAGE_SCALE = 6.0F;
    private static final float REPAIR_AMOUNT = 25.0F;
    private static final float WRECKED_SPEED_FACTOR = 0.86F;

    private static final double NOTCH_FORWARD = 3.0;
    private static final double NOTCH_UP = 0.6;
    private static final double NOTCH_SIDE = 0.0;

    private final RaftPart[] parts;
    private static final int DECK_PART_COUNT = 5;
    private static final float DECK_PART_SIZE = 1.1F;
    private static final float DECK_PART_HEIGHT = 0.2F;
    private static final double DECK_SPAN = 1.2;
    private static final double MODEL_DECK_Y = 1.369; // model's deck-floor height; walkable surface = renderFloatHeight() - this

    public float renderFloatHeight() {
        float f = Mth.lerp(this.getIntegrityFraction(), 1.2F, 1.7F);
        if (!this.isOnWater()) {
            f -= 0.35F;
        }
        return f;
    }

    private double deckSurfaceY() {
        return this.renderFloatHeight() - MODEL_DECK_Y;
    }

    private BlockPos ropedPostPos = null;
    private int ghostEmptyTicks;
    private static final int GHOST_EMPTY_DESPAWN_TICKS = 100;
    private int paddleSoundTicks;

    public RaftEntity(EntityType<? extends Boat> entityType, Level level) {
        super(entityType, level);
        RaftPart[] built = new RaftPart[DECK_PART_COUNT + 1];
        for (int i = 0; i < DECK_PART_COUNT; i++) {
            built[i] = new RaftPart(this, RaftPart.Role.DECK, DECK_PART_SIZE, DECK_PART_HEIGHT);
        }
        built[DECK_PART_COUNT] = new RaftPart(this, RaftPart.Role.NOTCH, 0.5F, 0.5F);
        this.parts = built;
        // Reserve a contiguous id block (parent + parts) so client-side part ids match (EnderDragon multipart idiom).
        this.setId(ENTITY_COUNTER.getAndAdd(this.parts.length + 1) + 1);
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.parts != null) { // null while the super() constructor runs
            for (int i = 0; i < this.parts.length; i++) {
                this.parts[i].setId(id + i + 1);
            }
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public net.neoforged.neoforge.entity.PartEntity<?>[] getParts() {
        return this.parts;
    }

    public RaftEntity(Level level, double x, double y, double z) {
        this(BannerboundAntiquity.RAFT.get(), level);
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_HEALTH, MAX_HEALTH);
        builder.define(DATA_FIBER_LEASH, true);
        builder.define(DATA_GHOST, false);
    }

    public boolean isGhost() {
        return this.entityData.get(DATA_GHOST);
    }

    public void setGhost(boolean ghost) {
        this.entityData.set(DATA_GHOST, ghost);
    }

    public static RaftEntity spawnGhost(ServerLevel level, double x, double y, double z, float yaw) {
        RaftEntity raft = new RaftEntity(level, x, y, z);
        raft.setGhost(true);
        raft.setYRot(yaw);
        raft.yRotO = yaw;
        if (!level.addFreshEntity(raft)) {
            return null;
        }
        return raft;
    }

    public boolean isFiberLeash() {
        return this.entityData.get(DATA_FIBER_LEASH);
    }

    public void setFiberLeash(boolean fiber) {
        this.entityData.set(DATA_FIBER_LEASH, fiber);
    }

    public float getRaftHealth() {
        return this.entityData.get(DATA_HEALTH);
    }

    public void setRaftHealth(float health) {
        this.entityData.set(DATA_HEALTH, Mth.clamp(health, 0.0F, MAX_HEALTH));
    }

    public float getIntegrityFraction() {
        return Mth.clamp(this.getRaftHealth() / MAX_HEALTH, 0.0F, 1.0F);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            Leashable.tickLeash(this);
            this.reconcileRopedPost();
            if (this.isGhost()) {
                ghostEmptyTicks = this.getPassengers().isEmpty() ? ghostEmptyTicks + 1 : 0;
                if (ghostEmptyTicks > GHOST_EMPTY_DESPAWN_TICKS) {
                    this.discard();
                    return;
                }
                // Derive paddle state AFTER super.tick(): vanilla force-clears it every tick when the first passenger isn't a player.
                Vec3 motion = this.getDeltaMovement();
                boolean rowing = motion.x * motion.x + motion.z * motion.z > 0.0025;
                this.setPaddleState(rowing, rowing);
                if (rowing) {
                    if (++paddleSoundTicks >= 16) {
                        paddleSoundTicks = 0;
                        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            net.minecraft.sounds.SoundEvents.BOAT_PADDLE_WATER, this.getSoundSource(),
                            1.0F, 0.8F + 0.4F * this.random.nextFloat());
                    }
                } else {
                    paddleSoundTicks = 12;
                }
            }
        }
        if (this.isControlledByLocalInstance()) {
            this.applyBuoyancy();
            this.applyDamageSlowdown();
        }
        this.positionParts();
    }

    private void positionParts() {
        double deckBottom = this.deckSurfaceY() - this.parts[0].getBbHeight();
        for (int i = 0; i < DECK_PART_COUNT; i++) {
            double t = (double) i / (DECK_PART_COUNT - 1);
            double forward = Mth.lerp(t, DECK_SPAN, -DECK_SPAN);
            placePart(this.parts[i], 0.0, deckBottom, forward);
        }
        Vec3 n = this.getNotchPosition(1.0F);
        RaftPart notch = this.parts[DECK_PART_COUNT];
        notch.xo = notch.getX();
        notch.yo = notch.getY();
        notch.zo = notch.getZ();
        notch.setPos(n.x, n.y - notch.getBbHeight() / 2.0, n.z);
    }

    private void placePart(RaftPart part, double sideways, double vertical, double forward) {
        Vec3 off = new Vec3(sideways, 0.0, forward).yRot(-this.getYRot() * ((float) Math.PI / 180.0F));
        part.xo = part.getX();
        part.yo = part.getY();
        part.zo = part.getZ();
        part.setPos(this.getX() + off.x, this.getY() + vertical + off.y, this.getZ() + off.z);
    }

    private void reconcileRopedPost() {
        BlockPos target = null;
        if (this.isFiberLeash()
                && this.getLeashHolder() instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity knot) {
            BlockPos kp = knot.getPos();
            if (this.level().getBlockState(kp).getBlock()
                    instanceof com.bannerbound.antiquity.block.RopeFencePostBlock) {
                target = kp;
            }
        }
        if (!java.util.Objects.equals(target, this.ropedPostPos)) {
            if (this.ropedPostPos != null) {
                com.bannerbound.antiquity.rope.RopeTies.refreshRoped(this.level(),
                    new com.bannerbound.antiquity.rope.RopeAnchor(this.ropedPostPos, 0));
            }
            this.ropedPostPos = target;
        }
        if (target != null) {
            com.bannerbound.antiquity.rope.RopeTies.setRopedModel(this.level(),
                new com.bannerbound.antiquity.rope.RopeAnchor(target, 0), true);
        }
    }

    public Vec3 getNotchPosition(float partialTick) {
        double ex = Mth.lerp(partialTick, this.xo, this.getX());
        double ey = Mth.lerp(partialTick, this.yo, this.getY());
        double ez = Mth.lerp(partialTick, this.zo, this.getZ());
        float yaw = Mth.lerp(partialTick, this.yRotO, this.getYRot());
        Vec3 local = new Vec3(NOTCH_SIDE, NOTCH_UP, NOTCH_FORWARD).yRot(-yaw * ((float) Math.PI / 180.0F));
        return new Vec3(ex + local.x, ey + local.y, ez + local.z);
    }

    public InteractionResult tieRope(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean rope = stack.is(Items.LEAD) || stack.is(BannerboundAntiquity.FIBER_ROPE.get());
        if (rope && !this.isLeashed()) {
            if (!this.level().isClientSide) {
                this.setFiberLeash(stack.is(BannerboundAntiquity.FIBER_ROPE.get()));
                this.setLeashedTo(player, true);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        if (stack.isEmpty() && this.getLeashHolder() == player) {
            if (!this.level().isClientSide) {
                // Attaching never consumed the rope item, so detaching must not drop one.
                this.dropLeash(true, false);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide());
        }
        return InteractionResult.PASS;
    }

    @Override
    public void leashTooFarBehaviour() {
        Entity holder = this.getLeashHolder();
        if (holder != null) {
            this.elasticRangeLeashBehaviour(holder, (float) Math.min(this.distanceTo(holder), 9.0));
        }
    }

    private void applyBuoyancy() {
        double surface = Double.NaN;
        BlockPos base = this.blockPosition();
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos p = base.above(dy);
            var fluid = this.level().getFluidState(p);
            if (fluid.is(FluidTags.WATER)) {
                surface = p.getY() + fluid.getHeight(this.level(), p);
            }
        }
        if (Double.isNaN(surface)) {
            return;
        }
        double target = surface - 0.15;
        if (this.getY() < target) {
            Vec3 v = this.getDeltaMovement();
            double lift = Math.min((target - this.getY()) * 0.2, 0.12);
            if (v.y < lift) {
                this.setDeltaMovement(v.x, lift, v.z);
            }
        }
    }

    private void applyDamageSlowdown() {
        float factor = Mth.lerp(this.getIntegrityFraction(), WRECKED_SPEED_FACTOR, 1.0F);
        if (factor < 1.0F) {
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x * factor, v.y, v.z * factor);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isGhost()) {
            return false;
        }
        if (this.level().isClientSide || this.isRemoved()) {
            return true;
        }
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.markHurt();
        this.setDamage(this.getDamage() + amount * 10.0F);
        this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
        if (source.getEntity() instanceof Player player && player.getAbilities().instabuild) {
            this.discard();
            return true;
        }
        this.setRaftHealth(this.getRaftHealth() - amount * HIT_DAMAGE_SCALE);
        if (this.getRaftHealth() <= 0.0F) {
            this.destroyRaft();
        }
        return true;
    }

    private void destroyRaft() {
        if (this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            int count = 2 + this.random.nextInt(2);
            this.spawnAtLocation(new ItemStack(BannerboundAntiquity.THATCH_ITEM.get(), count));
        }
        this.kill();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.isGhost()) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.is(BannerboundAntiquity.THATCH_BUNDLE.get()) && this.getRaftHealth() < MAX_HEALTH) {
            if (!this.level().isClientSide) {
                this.setRaftHealth(this.getRaftHealth() + REPAIR_AMOUNT);
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
                this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    BannerboundAntiquity.THATCH_PLACE_SOUND, SoundSource.BLOCKS, 1.0F, 1.0F);
                if (this.level() instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        this.getX(), this.getY() + 0.4, this.getZ(), 12, 0.6, 0.3, 0.6, 0.0);
                }
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (!this.isOnWater()) {
            return InteractionResult.PASS;
        }
        return super.interact(player, hand);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (this.isGhost() && passenger instanceof com.bannerbound.core.entity.CitizenEntity) {
            return false;
        }
        return super.canAddPassenger(passenger);
    }

    private boolean isOnWater() {
        BlockPos base = this.blockPosition();
        return this.level().getFluidState(base).is(FluidTags.WATER)
            || this.level().getFluidState(base.below()).is(FluidTags.WATER);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (this.ropedPostPos != null && !this.level().isClientSide) {
            com.bannerbound.antiquity.rope.RopeTies.refreshRoped(this.level(),
                new com.bannerbound.antiquity.rope.RopeAnchor(this.ropedPostPos, 0));
            this.ropedPostPos = null;
        }
        super.remove(reason);
    }

    @Override
    public Item getDropItem() {
        return BannerboundAntiquity.THATCH_ITEM.get();
    }

    @Override
    protected Component getTypeName() {
        return Component.translatable("entity.bannerboundantiquity.raft");
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        if (onGround) {
            this.resetFallDistance();
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("RaftHealth", this.getRaftHealth());
        tag.putBoolean("FiberLeash", this.isFiberLeash());
        tag.putBoolean("Ghost", this.isGhost());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("RaftHealth")) {
            this.setRaftHealth(tag.getFloat("RaftHealth"));
        }
        if (tag.contains("FiberLeash")) {
            this.setFiberLeash(tag.getBoolean("FiberLeash"));
        }
        if (tag.contains("Ghost")) {
            this.setGhost(tag.getBoolean("Ghost"));
        }
    }

    @Override
    protected int getMaxPassengers() {
        return 3;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity entity, EntityDimensions dimensions, float partialTick) {
        int count = this.getPassengers().size();
        int index = this.getPassengers().indexOf(entity);
        double forward;
        if (count <= 1 || index < 0) {
            forward = 0.0;
        } else if (count == 2) {
            forward = index == 0 ? 0.8 : -0.8;
        } else {
            forward = index == 0 ? 1.1 : (index == 1 ? 0.0 : -1.1);
        }
        double seatY = dimensions.height() * 0.8888889F;
        if (entity instanceof com.bannerbound.core.entity.CitizenEntity) {
            seatY -= 0.7;
        }
        return new Vec3(0.0, seatY, forward)
            .yRot(-this.getYRot() * (float) (Math.PI / 180.0));
    }

    @Override
    public boolean canCollideWith(Entity entity) {
        if (entity instanceof RaftPart part && part.getParent() == this) {
            return false;
        }
        return super.canCollideWith(entity);
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return new Vec3(this.getX(), this.getY() + this.deckSurfaceY() + 0.02, this.getZ());
    }
}
