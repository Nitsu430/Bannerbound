package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Chopping Stump. Tracks the source log block the stump was carved from
 * (logType skins the rendered stump body and its break particles, so an oak stump looks like
 * oak), the stack of logs deposited on top (one type at a time, capped at a normal stack) that
 * a bone axe splits into firewood, and a slide-in animation for a freshly deposited stack.
 * SLIDE_TICKS matches the bloomery's slide; insertDir is the horizontal side the depositing
 * player stood on, so the stack slides in from where it was placed. insert() replays the slide,
 * while setLogs() updates the pile without replaying it (used as the pile is whittled down).
 * setChanged always re-syncs to clients (sendBlockUpdated) so the rendered pile stays current.
 */
@ApiStatus.Internal
public class ChoppingStumpBlockEntity extends BlockEntity {
    public static final int SLIDE_TICKS = 6;

    private Block logType = Blocks.OAK_LOG;
    private ItemStack logs = ItemStack.EMPTY;
    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;

    public ChoppingStumpBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.CHOPPING_STUMP_BE.get(), pos, state);
    }

    public Block getLogType() {
        return logType;
    }

    public void setLogType(Block block) {
        this.logType = block == null ? Blocks.OAK_LOG : block;
        setChanged();
    }

    public ItemStack getLogs() {
        return logs;
    }

    public boolean isEmpty() {
        return logs.isEmpty();
    }

    public int getInsertAnimTicks() {
        return insertAnimTicks;
    }

    public Direction getInsertDir() {
        return insertDir;
    }

    public void insert(ItemStack stack, Direction from) {
        this.logs = stack == null ? ItemStack.EMPTY : stack;
        this.insertAnimTicks = SLIDE_TICKS;
        this.insertDir = from == null ? Direction.NORTH : from;
        setChanged();
    }

    public void setLogs(ItemStack stack) {
        this.logs = stack == null ? ItemStack.EMPTY : stack;
        setChanged();
    }

    public ItemStack takeLogs() {
        ItemStack out = logs;
        logs = ItemStack.EMPTY;
        insertAnimTicks = 0;
        setChanged();
        return out;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ChoppingStumpBlockEntity be) {
        if (be.insertAnimTicks > 0) {
            be.insertAnimTicks--;
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
        tag.putString("LogType", BuiltInRegistries.BLOCK.getKey(logType).toString());
        tag.putInt("InsertAnimTicks", insertAnimTicks);
        tag.putInt("InsertDir", insertDir.get3DDataValue());
        if (!logs.isEmpty()) {
            tag.put("Logs", logs.save(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.contains("LogType")) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("LogType"));
            logType = id != null && BuiltInRegistries.BLOCK.containsKey(id)
                ? BuiltInRegistries.BLOCK.get(id) : Blocks.OAK_LOG;
        }
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        insertDir = Direction.from3DDataValue(tag.getInt("InsertDir"));
        logs = tag.contains("Logs")
            ? ItemStack.parse(provider, tag.getCompound("Logs")).orElse(ItemStack.EMPTY)
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
