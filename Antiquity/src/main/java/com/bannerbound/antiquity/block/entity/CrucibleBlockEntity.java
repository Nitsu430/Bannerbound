package com.bannerbound.antiquity.block.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.workshop.MetalworkingItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A crucible sitting on the ground (METALWORKING_PLAN.md Part 2, overhauled): holds a charge of
 * raw smeltable items (MetalworkingItems.isSmeltable) dropped in by right-clicking, rendered
 * inside the bowl. CAPACITY is 8 items (~4 raw ore = a sword's worth). Items can only be added or
 * popped back out while the charge is still solid -- once molten it is sealed. setContents adopts
 * a placed crucible item's charge (called from the block's setPlacedBy), and breaking the block
 * returns a crucible item carrying the charge (CrucibleBlock#getDrops); insert that into a
 * bloomery to melt it. Contents persist via CrucibleContents.CODEC and every mutation pushes a
 * full block-update sync to clients.
 */
public class CrucibleBlockEntity extends BlockEntity {
    public static final int CAPACITY = 8;

    private CrucibleContents contents = CrucibleContents.EMPTY;

    public CrucibleBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.CRUCIBLE_BLOCK_BE.get(), pos, state);
    }

    public CrucibleContents contents() {
        return contents;
    }

    public void setContents(CrucibleContents c) {
        this.contents = c;
        sync();
    }

    public boolean isFull() {
        return contents.charge().size() >= CAPACITY;
    }

    public boolean addItem(ItemStack stack) {
        if (contents.molten() || isFull() || !MetalworkingItems.isSmeltable(stack)) return false;
        contents = contents.withAdded(stack);
        sync();
        return true;
    }

    public ItemStack removeLast() {
        if (contents.molten() || !contents.hasCharge()) return ItemStack.EMPTY;
        ItemStack popped = contents.lastItem();
        contents = contents.withoutLast();
        sync();
        return popped;
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        CrucibleContents.CODEC.encodeStart(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                contents)
            .resultOrPartial(err -> BannerboundAntiquity.LOGGER.error("Crucible save failed: {}", err))
            .ifPresent(t -> tag.put("Contents", t));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.contains("Contents")) {
            CrucibleContents.CODEC.parse(provider.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                    tag.get("Contents"))
                .resultOrPartial(err -> BannerboundAntiquity.LOGGER.error("Crucible load failed: {}", err))
                .ifPresent(c -> contents = c);
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
