package com.bannerbound.core.block.entity;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.stockpile.StockpileService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for {@link com.bannerbound.core.block.StockpileBlock}. Anchors a logical
 * {@link Stockpile} record and, on a staggered 10 s ticker (200 ticks, offset per-pos hash so many
 * stockpiles don't pile their scans onto one tick), re-scans the surrounding fence/roof enclosure via
 * {@link StockpileService#validate} - which writes validity + the enclosed container positions back
 * onto the record.
 *
 * <p>Unlike a rod-marked home it does NOT consult a rod-marked region: the enclosure (the player's
 * fence ring + roof) <i>is</i> the marking. No inventory of its own - the storage is the player's
 * container blocks inside the enclosure; no menu here either, the terminal screen is delivered via a
 * custom payload.
 *
 * <p>The BE lazily registers its {@link Stockpile} if it wakes without one (e.g. /setblock bypassed
 * the place event), keyed by a stable UUID persisted under the "StockpileId" NBT tag.
 */
@ApiStatus.Internal
public class StockpileBlockEntity extends BlockEntity {
    public static final String TYPE_ID = "stockpile";
    public static final int VALIDATION_INTERVAL_TICKS = 200;

    private UUID stockpileId;

    public StockpileBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundCore.STOCKPILE_BE.get(), pos, state);
    }

    public UUID getStockpileId() { return stockpileId; }
    public void setStockpileId(UUID id) {
        this.stockpileId = id;
        setChanged();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, StockpileBlockEntity be) {
        if (level.isClientSide || !(level instanceof ServerLevel sl)) return;
        long phase = Math.floorMod(pos.asLong(), VALIDATION_INTERVAL_TICKS);
        if (sl.getGameTime() % VALIDATION_INTERVAL_TICKS != phase) return;
        be.runValidation(sl);
    }

    public void runValidation(ServerLevel sl) {
        MinecraftServer server = sl.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement owner = data.getByChunk(new ChunkPos(getBlockPos()).toLong());
        if (owner == null) return;
        Stockpile sp = stockpileId != null ? owner.getStockpileById(stockpileId) : null;
        if (sp == null) sp = registerLazy(owner, data);
        StockpileService.validate(sl, sp);
        data.setDirty();
    }

    private Stockpile registerLazy(Settlement owner, SettlementData data) {
        UUID id = UUID.randomUUID();
        Stockpile sp = new Stockpile(id, getBlockPos().immutable());
        owner.putStockpile(sp);
        this.stockpileId = id;
        setChanged();
        data.setDirty();
        return sp;
    }

    public static void onBlockRemoved(ServerLevel sl, BlockPos pos, UUID stockpileId) {
        if (stockpileId == null) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement owner = data.getByChunk(new ChunkPos(pos).toLong());
        if (owner == null) return;
        if (owner.removeStockpile(pos) != null) data.setDirty();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (stockpileId != null) tag.putUUID("StockpileId", stockpileId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.hasUUID("StockpileId")) stockpileId = tag.getUUID("StockpileId");
    }
}
