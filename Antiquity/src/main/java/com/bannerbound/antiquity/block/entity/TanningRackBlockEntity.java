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
 * Block entity for the Tanning Rack: ONE hide in progress at a time (like the pottery slab), walked
 * through EMPTY -> RAW -> DRYING -> DRY. RAW keeps the quality-tagged raw hide itself in {@code held}
 * so its quality survives; right-clicking it with a knife opens the scrape minigame, which consumes
 * it for scraped_hide x quality. DRYING starts when a cured hide is placed and counts down DRY_TICKS
 * (1200 = ~60s); DRY yields leather on an empty-handed right-click. Phase transitions are driven by
 * TanningRackBlock and the Tannery job; dryProgress() (0..1) drives the cured->leather render
 * cross-fade. setChanged() doubles as the client sync point (sendBlockUpdated + full-state update
 * tag); an unknown saved phase falls back to EMPTY on load.
 */
@ApiStatus.Internal
public class TanningRackBlockEntity extends BlockEntity {
    public static final int DRY_TICKS = 1200;

    public enum Phase { EMPTY, RAW, DRYING, DRY }

    private Phase phase = Phase.EMPTY;
    private ItemStack held = ItemStack.EMPTY;
    private int dryTicks = 0;

    public TanningRackBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.TANNING_RACK_BE.get(), pos, state);
    }

    public Phase getPhase() {
        return phase;
    }

    public ItemStack getDisplayStack() {
        return switch (phase) {
            case RAW -> held;
            case DRYING -> new ItemStack(BannerboundAntiquity.CURED_HIDE.get());
            case DRY -> new ItemStack(net.minecraft.world.item.Items.LEATHER);
            case EMPTY -> ItemStack.EMPTY;
        };
    }

    public ItemStack getRawHide() {
        return held;
    }

    public boolean placeRaw(ItemStack rawHide) {
        if (phase != Phase.EMPTY) return false;
        held = rawHide.copyWithCount(1);
        phase = Phase.RAW;
        setChanged();
        return true;
    }

    public boolean placeCured() {
        if (phase != Phase.EMPTY) return false;
        phase = Phase.DRYING;
        dryTicks = DRY_TICKS;
        held = ItemStack.EMPTY;
        setChanged();
        return true;
    }

    public ItemStack retrieve() {
        ItemStack out = switch (phase) {
            case RAW -> held;
            case DRYING -> new ItemStack(BannerboundAntiquity.CURED_HIDE.get());
            case DRY -> new ItemStack(net.minecraft.world.item.Items.LEATHER);
            case EMPTY -> ItemStack.EMPTY;
        };
        clear();
        return out;
    }

    public void clear() {
        phase = Phase.EMPTY;
        held = ItemStack.EMPTY;
        dryTicks = 0;
        setChanged();
    }

    public boolean isRaw() {
        return phase == Phase.RAW;
    }

    public boolean isDry() {
        return phase == Phase.DRY;
    }

    public float dryProgress() {
        if (phase == Phase.DRY) return 1.0F;
        if (phase != Phase.DRYING) return 0.0F;
        return 1.0F - dryTicks / (float) DRY_TICKS;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TanningRackBlockEntity be) {
        if (be.phase != Phase.DRYING) return;
        // Both sides count down (smooth client fade, no per-tick sync); the server alone commits DRYING -> DRY and re-syncs.
        if (be.dryTicks > 0) be.dryTicks--;
        if (level.isClientSide) {
            if (level.random.nextInt(5) == 0) {
                level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    pos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 1.6,
                    pos.getY() + 1.1 + level.random.nextDouble() * 0.7,
                    pos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 1.6,
                    0.0, 0.015, 0.0);
            }
        } else if (be.dryTicks <= 0) {
            be.phase = Phase.DRY;
            be.setChanged();
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
        tag.putString("Phase", phase.name());
        tag.putInt("DryTicks", dryTicks);
        if (!held.isEmpty()) {
            tag.put("Held", held.save(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        try {
            phase = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException e) {
            phase = Phase.EMPTY;
        }
        dryTicks = tag.getInt("DryTicks");
        held = tag.contains("Held")
            ? ItemStack.parse(provider, tag.getCompound("Held")).orElse(ItemStack.EMPTY)
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
