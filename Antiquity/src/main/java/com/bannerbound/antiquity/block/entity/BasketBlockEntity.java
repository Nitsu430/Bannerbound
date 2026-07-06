package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.world.inventory.BasketMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Basket, a 9-slot storage block. Implements Container directly so it backs
 * the BasketMenu without a separate inventory object. getDisplayStack returns the first non-empty
 * slot, which BasketRenderer draws sitting on top of the basket; because that displayed slot can
 * change on any edit, setChanged always re-syncs to clients (sendBlockUpdated).
 *
 * pickupRequested is a transient flag set in BasketBlock.playerWillDestroy when a player
 * sneak-breaks the basket: the break then drops one basket item carrying the contents (baked from
 * getItems) instead of dropping them loose. It lives only for the destroy tick and is never saved
 * to NBT. loadFromContents restores contents from a placed basket item's component
 * (BasketBlock.setPlacedBy).
 */
@ApiStatus.Internal
public class BasketBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int SIZE = 9;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    private boolean pickupRequested;

    public BasketBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.BASKET_BE.get(), pos, state);
    }

    public NonNullList<ItemStack> getItems() {
        return items;
    }

    public void loadFromContents(net.minecraft.world.item.component.ItemContainerContents contents) {
        contents.copyInto(items);
        setChanged();
    }

    public void markPickup() {
        pickupRequested = true;
    }

    public boolean isPickupRequested() {
        return pickupRequested;
    }

    public ItemStack getDisplayStack() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        setChanged();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        ContainerHelper.saveAllItems(tag, items, provider);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, provider);
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

    public Container getDroppableInventory() {
        return this;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bannerboundantiquity.basket");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new BasketMenu(containerId, playerInv, this);
    }
}
