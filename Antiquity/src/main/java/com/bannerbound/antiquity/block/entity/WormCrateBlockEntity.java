package com.bannerbound.antiquity.block.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class WormCrateBlockEntity extends BlockEntity {
    private int amountOfWorms = 0;
    private int ticks = 0;

    public WormCrateBlockEntity(BlockPos pos, BlockState blockState) {
        super(BannerboundAntiquity.WORM_CRATE_BE.get(), pos, blockState);
    }

    public static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            WormCrateBlockEntity be) {
        // yay we got tick
    }

    public int getAmountOfWorms() {
        return amountOfWorms;
    }

    public void setAmountOfWorms(int amountOfWorms) {
        this.amountOfWorms = amountOfWorms;
    }

    public int getTicks() {
        return ticks;
    }

    public void setTicks(int ticks) {
        this.ticks = ticks;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        tag.putInt("amountOfWorms", getAmountOfWorms());
        tag.putInt("ticks", getTicks());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.contains("amountOfWorms")) {
            setAmountOfWorms(tag.getInt("amountOfWorms"));
        }

        if (tag.contains("ticks")) {
            setTicks(tag.getInt("ticks"));
        }
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
