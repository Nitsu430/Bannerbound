package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.ClayTankBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Clay Tank (lives on the controller cell, the bottom of the pillar,
 * PART == 0). Stores a simple liquid: a bucket count plus one LiquidType shared by the whole
 * pillar; deliberately not a Forge fluid, since the curing liquid is no registered fluid and
 * never flows. Each tank piece holds BUCKETS_PER_PIECE (8) buckets, so capacity = 8 x pillar
 * height (pillarHeight counts connected tank blocks above the controller); clampBuckets shrinks
 * the held amount when a pillar piece is destroyed. Water goes in and out by the bucket (only
 * WATER is bucket-recoverable), quicklime converts the held water into the hide-curing liquid,
 * and each hide cured draws one bucket of it. fillWater/fillCuring are the NPC tanner's bulk
 * paths (pouring a fetched bucket / water + quicklime); fillWater never overwrites held curing
 * liquid. LiquidType carries the packed ARGB tint the fill renderer uses, fillFraction (0..1 of
 * the whole pillar) drives the rendered fill surface Y, and setChanged always re-syncs to
 * clients for that renderer.
 */
@ApiStatus.Internal
public class ClayTankBlockEntity extends BlockEntity {
    public static final int BUCKETS_PER_PIECE = 8;

    public enum LiquidType {
        EMPTY(0x00000000),
        WATER(0xFF3F76E4),
        CURING(0xFFE9E2C4);

        private final int color;

        LiquidType(int color) {
            this.color = color;
        }

        public int color() {
            return color;
        }
    }

    private int buckets = 0;
    private LiquidType liquid = LiquidType.EMPTY;

    public ClayTankBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.CLAY_TANK_BE.get(), pos, state);
    }

    public int getBuckets() {
        return buckets;
    }

    public LiquidType getLiquid() {
        return liquid;
    }

    public int pillarHeight() {
        if (level == null) return 1;
        int h = 1;
        BlockPos.MutableBlockPos p = getBlockPos().mutable().move(0, 1, 0);
        while (level.getBlockState(p).getBlock() instanceof ClayTankBlock && h < ClayTankBlock.MAX_PIECES) {
            h++;
            p.move(0, 1, 0);
        }
        return h;
    }

    public int maxBuckets() {
        return BUCKETS_PER_PIECE * pillarHeight();
    }

    public float fillFraction() {
        int max = maxBuckets();
        return max <= 0 ? 0f : (float) buckets / max;
    }

    public boolean addWater() {
        if (liquid == LiquidType.CURING) return false;
        if (buckets >= maxBuckets()) return false;
        liquid = LiquidType.WATER;
        buckets++;
        playSplash(1.0F);
        setChanged();
        return true;
    }

    public boolean removeWater() {
        if (liquid != LiquidType.WATER || buckets <= 0) return false;
        buckets--;
        if (buckets == 0) liquid = LiquidType.EMPTY;
        playSplash(1.2F);
        setChanged();
        return true;
    }

    public boolean convertToCuring() {
        if (liquid != LiquidType.WATER || buckets <= 0) return false;
        liquid = LiquidType.CURING;
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.8F, 1.1F);
        }
        setChanged();
        return true;
    }

    public boolean hasCuring() {
        return liquid == LiquidType.CURING && buckets > 0;
    }

    public void fillWater() {
        if (liquid == LiquidType.CURING) return;
        liquid = LiquidType.WATER;
        buckets = maxBuckets();
        playSplash(1.0F);
        setChanged();
    }

    public void fillCuring() {
        liquid = LiquidType.CURING;
        buckets = maxBuckets();
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        setChanged();
    }

    public void clampBuckets(int max) {
        if (buckets > Math.max(0, max)) {
            buckets = Math.max(0, max);
            if (buckets == 0) liquid = LiquidType.EMPTY;
            setChanged();
        }
    }

    public boolean drawCuring() {
        if (!hasCuring()) return false;
        buckets--;
        if (buckets == 0) liquid = LiquidType.EMPTY;
        playSplash(0.9F);
        setChanged();
        return true;
    }

    private void playSplash(float pitch) {
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.6F, pitch);
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
        tag.putInt("Buckets", buckets);
        tag.putString("Liquid", liquid.name());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        buckets = tag.getInt("Buckets");
        try {
            liquid = LiquidType.valueOf(tag.getString("Liquid"));
        } catch (IllegalArgumentException e) {
            liquid = LiquidType.EMPTY;
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
