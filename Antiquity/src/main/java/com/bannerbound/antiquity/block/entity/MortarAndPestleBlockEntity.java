package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Mortar and Pestle. Holds the bowl's liquid (by string id, "" = empty) and
 * the ingredient being ground -- possibly batched: item-output recipes grind up to MAX_BATCH in
 * one go. Grinding itself is the press-and-grind minigame (MortarGrind); this entity just stores
 * the loaded contents and plays a short completion flourish (mixAnimTicks, MIX_CYCLE_TICKS = the
 * 1-second "Mix" animation) triggered server-side via playFlourish() so nearby players see the
 * pestle strike. Liquid, ingredient and the flourish timer mirror to the client through
 * setChanged() block updates; the live in-session pestle motion is driven client-side by
 * MortarGrindState, not by this entity.
 */
@ApiStatus.Internal
public class MortarAndPestleBlockEntity extends BlockEntity {
    public static final int MIX_CYCLE_TICKS = 20;
    public static final int MAX_BATCH = 16;

    private String liquidId = "";
    private ItemStack ingredient = ItemStack.EMPTY;
    private int mixAnimTicks = 0;

    public MortarAndPestleBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.MORTAR_AND_PESTLE_BE.get(), pos, state);
    }

    public String getLiquidId() {
        return liquidId;
    }

    public boolean hasLiquid() {
        return !liquidId.isEmpty();
    }

    public void setLiquid(String id) {
        this.liquidId = id == null ? "" : id;
        setChanged();
    }

    public ItemStack getIngredient() {
        return ingredient;
    }

    public void setIngredient(ItemStack stack) {
        this.ingredient = stack == null ? ItemStack.EMPTY : stack;
        setChanged();
    }

    public boolean isMixing() {
        return mixAnimTicks > 0;
    }

    public int getMixAnimTicks() {
        return mixAnimTicks;
    }

    public void playFlourish() {
        mixAnimTicks = MIX_CYCLE_TICKS;
        setChanged();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, MortarAndPestleBlockEntity be) {
        if (be.mixAnimTicks > 0) {
            be.mixAnimTicks--;
        }
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
        tag.putString("Liquid", liquidId);
        tag.putInt("MixAnimTicks", mixAnimTicks);
        if (!ingredient.isEmpty()) {
            tag.put("Ingredient", ingredient.save(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        liquidId = tag.getString("Liquid");
        mixAnimTicks = tag.getInt("MixAnimTicks");
        ingredient = tag.contains("Ingredient")
            ? ItemStack.parse(provider, tag.getCompound("Ingredient")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
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
