package com.bannerbound.antiquity.entity;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * A hittable sub-box of a {@link RaftEntity}. A single entity AABB is a square that cannot rotate,
 * so the raft hangs several of these on itself (thin DECK slabs tiling the hull plus one bow
 * NOTCH) and repositions them every tick, giving a raft-shaped, rotating click/attack/standing
 * surface. NeoForge includes PartEntity in {@code Level#getEntities}, so parts are picked for
 * interaction like normal entities; damage and interaction route to the parent (the NOTCH ties the
 * tow rope, DECK boards/repairs), and is() treats a part as its parent for identity checks
 * (leash/passenger tests). Only DECK parts are collidable -- and the parent excludes them from its
 * own collision so it can't jam on itself; a GHOST raft's parts are neither collidable nor
 * pickable, so the parent can't be reached through them at all. Parts are never saved, synced, or
 * spawned on their own: the parent recreates them and assigns their contiguous entity ids.
 */
public class RaftPart extends PartEntity<RaftEntity> {
    public enum Role { DECK, NOTCH }

    private final EntityDimensions size;
    public final Role role;

    public RaftPart(RaftEntity parent, Role role, float width, float height) {
        super(parent);
        this.role = role;
        this.size = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean canBeCollidedWith() {
        return this.role == Role.DECK && !this.getParent().isGhost();
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved() && !this.getParent().isGhost();
    }

    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return this.isInvulnerableTo(source) ? false : this.getParent().hurt(source, amount);
    }

    @Override
    public InteractionResult interactAt(Player player, Vec3 hitVec, InteractionHand hand) {
        return this.role == Role.NOTCH
            ? this.getParent().tieRope(player, hand)
            : this.getParent().interact(player, hand);
    }

    @Override
    public ItemStack getPickResult() {
        return this.getParent().getPickResult();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        throw new UnsupportedOperationException();
    }
}
