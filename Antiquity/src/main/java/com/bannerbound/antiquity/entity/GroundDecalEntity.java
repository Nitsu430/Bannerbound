package com.bannerbound.antiquity.entity;

import java.util.OptionalDouble;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Purely-cosmetic ground decal for the hunting tracker: either a blood splat (KIND_BLOOD, a bleeding
 * wound) or a footprint track (KIND_TRACK, a walking animal). It lies flat on the ground and the
 * renderer fades it out over a config lifetime keyed off {@code tickCount} (which also drives the
 * cone fade, so {@code super.tick()} must keep running). Decals never float: spawn clamps down to the
 * top surface of the nearest solid block within MAX_DROP of the column and silently spawns nothing if
 * there is none. No collision or gravity, not attackable (left-click must not destroy it), never
 * saved to disk ({@code shouldBeSaved()} is false; the save-data overrides exist only because Entity
 * requires them) - but pickable so the crosshair can right-click it.
 *
 * <p>Right-clicking "examines" the decal: the server stamps DATA_REVEAL_TICK and the renderer draws a
 * translucent white search cone along DATA_DIRECTION (Hunter: Call of the Wild style), fading
 * client-side over ~3s and re-armed by another click. Examining a footprint additionally highlights
 * every other active track from the same animal in cyan - gated by the {@code hunting_instincts}
 * research and triggered purely client-side since it is cosmetic. All tracks from one animal share
 * DATA_GROUP_ID = the source animal's entity id (stable for its lifetime, unique among loaded
 * entities; -1 = unknown), which is how the tracker groups them.
 *
 * <p>Heading: mob yaw twitches and spins on sharp turns, so instead of trusting the animal's facing,
 * for the first HEADING_SETTLE_TICKS (~2s) the server keeps re-pointing DATA_DIRECTION along the
 * animal's net displacement away from the decal (once it has travelled MIN_TRACK_DIST), then locks
 * it. {@code sourceAnimalId} is server-side only and is dropped once settled or when the animal
 * despawns, keeping the last good heading.
 */
public class GroundDecalEntity extends Entity {
    public static final int KIND_BLOOD = 0;
    public static final int KIND_TRACK = 1;
    private static final int MAX_DROP = 16;
    private static final int HEADING_SETTLE_TICKS = 40;
    private static final double MIN_TRACK_DIST = 0.5;

    private int sourceAnimalId = -1;

    private static final EntityDataAccessor<Integer> DATA_KIND =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_SPECIES =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_DIRECTION =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_REVEAL_TICK =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_GROUP_ID =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);

    public GroundDecalEntity(EntityType<? extends GroundDecalEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public static void spawnBlood(Level level, double x, double y, double z, int variant, Entity source) {
        spawn(level, x, y, z, source, KIND_BLOOD, variant, "");
    }

    public static void spawnTrack(Level level, double x, double y, double z, String species, Entity source) {
        spawn(level, x, y, z, source, KIND_TRACK, 0, species);
    }

    private static void spawn(Level level, double x, double y, double z, Entity source,
                              int kind, int variant, String species) {
        OptionalDouble surface = groundSurface(level, x, y, z);
        if (surface.isEmpty()) {
            return;
        }
        GroundDecalEntity decal = new GroundDecalEntity(BannerboundAntiquity.GROUND_DECAL.get(), level);
        decal.setPos(x, surface.getAsDouble() + 0.02, z);
        decal.entityData.set(DATA_KIND, kind);
        decal.entityData.set(DATA_VARIANT, variant);
        decal.entityData.set(DATA_SPECIES, species);
        decal.entityData.set(DATA_DIRECTION, source.getYRot());
        decal.entityData.set(DATA_GROUP_ID, source.getId());
        decal.sourceAnimalId = source.getId();
        level.addFreshEntity(decal);
    }

    private static OptionalDouble groundSurface(Level level, double x, double startY, double z) {
        BlockPos.MutableBlockPos pos =
            new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(startY), Mth.floor(z));
        for (int i = 0; i <= MAX_DROP; i++) {
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                return OptionalDouble.of(pos.getY() + shape.max(Direction.Axis.Y));
            }
            pos.move(Direction.DOWN);
        }
        return OptionalDouble.empty();
    }

    public int getKind() {
        return this.entityData.get(DATA_KIND);
    }

    public int getVariant() {
        return this.entityData.get(DATA_VARIANT);
    }

    public String getSpecies() {
        return this.entityData.get(DATA_SPECIES);
    }

    public float getHeading() {
        return this.entityData.get(DATA_DIRECTION);
    }

    public int getRevealTick() {
        return this.entityData.get(DATA_REVEAL_TICK);
    }

    public int getGroupId() {
        return this.entityData.get(DATA_GROUP_ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, KIND_BLOOD);
        builder.define(DATA_VARIANT, 0);
        builder.define(DATA_SPECIES, "");
        builder.define(DATA_DIRECTION, 0.0F);
        builder.define(DATA_REVEAL_TICK, -1);
        builder.define(DATA_GROUP_ID, -1);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_REVEAL_TICK, this.tickCount);
        } else if (getKind() == KIND_TRACK
                && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            // Dist guard: FootprintHighlight is client-only; keep it fully-qualified and behind isClient() or a dedicated server can crash on classload.
            com.bannerbound.antiquity.client.FootprintHighlight.examine(this);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel server) {
            settleHeading(server);
            int lifetime = getKind() == KIND_TRACK
                ? Config.FOOTPRINT_LIFETIME_TICKS.get()
                : Config.BLOOD_SPLAT_LIFETIME_TICKS.get();
            if (this.tickCount >= lifetime) {
                this.discard();
            }
        }
    }

    private void settleHeading(ServerLevel server) {
        if (sourceAnimalId < 0) {
            return;
        }
        Entity source = server.getEntity(sourceAnimalId);
        if (source == null || !source.isAlive()) {
            sourceAnimalId = -1;
            return;
        }
        double dx = source.getX() - this.getX();
        double dz = source.getZ() - this.getZ();
        if (dx * dx + dz * dz >= MIN_TRACK_DIST * MIN_TRACK_DIST) {
            // MC yaw forward vector is (-sin, cos), so decal->animal yaw = atan2(-dx, dz).
            this.entityData.set(DATA_DIRECTION, (float) (Mth.atan2(-dx, dz) * (180.0 / Math.PI)));
        }
        if (this.tickCount >= HEADING_SETTLE_TICKS) {
            sourceAnimalId = -1;
        }
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_KIND, tag.getInt("Kind"));
        this.entityData.set(DATA_VARIANT, tag.getInt("Variant"));
        this.entityData.set(DATA_SPECIES, tag.getString("Species"));
        this.entityData.set(DATA_DIRECTION, tag.getFloat("Direction"));
        this.entityData.set(DATA_REVEAL_TICK, tag.contains("RevealTick") ? tag.getInt("RevealTick") : -1);
        this.entityData.set(DATA_GROUP_ID, tag.contains("GroupId") ? tag.getInt("GroupId") : -1);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Kind", getKind());
        tag.putInt("Variant", getVariant());
        tag.putString("Species", getSpecies());
        tag.putFloat("Direction", getHeading());
        tag.putInt("RevealTick", getRevealTick());
        tag.putInt("GroupId", getGroupId());
    }
}
