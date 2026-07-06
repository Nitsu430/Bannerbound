package com.bannerbound.antiquity.block.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds the Bellows Block's "Push" animation timer. triggerPush (called when a player lands on the
 * block) sets animTicks to PUSH_TICKS and syncs to clients; the renderer plays
 * BellowsAnimations.PUSH over that window while tick() counts it back down to 0. PUSH_TICKS is 20 =
 * the animation's 1-second length.
 */
public class BellowsBlockEntity extends BlockEntity {
    public static final int PUSH_TICKS = 20;

    private int animTicks = 0;

    public BellowsBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.BELLOWS_BLOCK_BE.get(), pos, state);
    }

    public int animTicks() {
        return animTicks;
    }

    public void triggerPush() {
        animTicks = PUSH_TICKS;
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        setChanged();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, BellowsBlockEntity be) {
        if (be.animTicks > 0) {
            be.animTicks--;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("AnimTicks", animTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        animTicks = tag.getInt("AnimTicks");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
