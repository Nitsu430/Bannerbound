package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.FermentationTroughBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for a Fermentation Trough. Each cell stores its own SHARE of liquid in units (one
 * bucket = UNITS_PER_CELL = 5 units = 100% of one cell; a hand-scoop adds 1 unit = 20%); connected
 * troughs form one shared pool, and the displayed fill is the run's total divided by capacity
 * (FermentationTroughBlock.runFraction). Fermentation (GROG_PLAN.md Phase 2): charging the pool
 * with a fermentable stamps a grog recipe id ("" = plain water), a start game-time, and a duration
 * snapshotted (already warmth-adjusted) at charge time; readiness is LAZY -- computed from
 * game-time on read (fermenting/grogReady/fermentProgress drive the rendered bubbles and colour
 * ripening). The ferment fields are kept identical across a pool's cells (the block consolidates
 * them on every structural change) so any cell answers for the whole pool; setFerment writes them
 * verbatim, syncs only on an actual change, and any change re-arms the one-shot "ready" cue
 * (persisted via the notified flag so it never replays). A cheap early-out server tick fires that
 * cue (sound + poof) the moment the grog finishes. The recipe id resolves the liquid's
 * tint/identity via GrogRecipeManager; Phase 3 pours it into mugs/horns.
 */
@ApiStatus.Internal
public class FermentationTroughBlockEntity extends BlockEntity {
    public static final int UNITS_PER_CELL = 5;

    private int units = 0;
    private String grogRecipeId = "";
    private long fermentStart = 0L;
    private int fermentTicks = 0;
    private boolean notified = false;

    public FermentationTroughBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.FERMENTATION_TROUGH_BE.get(), pos, state);
    }

    public int units() {
        return units;
    }

    public int spaceLeft() {
        return UNITS_PER_CELL - units;
    }

    public int addUnits(int n) {
        int added = Math.min(Math.max(0, n), spaceLeft());
        if (added > 0) {
            units += added;
            setChanged();
        }
        return added;
    }

    public boolean removeUnit() {
        if (units <= 0) return false;
        units--;
        setChanged();
        return true;
    }

    public boolean isCharged() {
        return !grogRecipeId.isEmpty();
    }

    public String grogRecipeId() {
        return grogRecipeId;
    }

    public long fermentStart() {
        return fermentStart;
    }

    public int fermentTicks() {
        return fermentTicks;
    }

    public boolean fermenting(long gameTime) {
        return isCharged() && gameTime - fermentStart < fermentTicks;
    }

    public boolean grogReady(long gameTime) {
        return isCharged() && gameTime - fermentStart >= fermentTicks;
    }

    public float fermentProgress(long gameTime) {
        if (!isCharged()) return 0.0F;
        if (fermentTicks <= 0) return 1.0F;
        return Math.min(1.0F, Math.max(0.0F, (gameTime - fermentStart) / (float) fermentTicks));
    }

    public void charge(String recipeId, long start, int ticks) {
        setFerment(recipeId, start, ticks);
    }

    public void setFerment(String recipeId, long start, int ticks) {
        if (grogRecipeId.equals(recipeId) && fermentStart == start && fermentTicks == ticks) return;
        grogRecipeId = recipeId;
        fermentStart = start;
        fermentTicks = ticks;
        notified = false;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FermentationTroughBlockEntity be) {
        if (!be.isCharged() || be.notified || !be.grogReady(level.getGameTime())) return;
        be.notified = true;
        be.setChanged();
        // Only the pool-start cell (RIGHT side not open) emits, so the cue plays once per pool.
        boolean poolStart = !state.hasProperty(FermentationTroughBlock.RIGHT)
            || !state.getValue(FermentationTroughBlock.RIGHT);
        if (poolStart && level instanceof ServerLevel sl) {
            sl.playSound(null, pos, SoundEvents.COMPOSTER_READY, SoundSource.BLOCKS, 0.8F, 1.0F);
            sl.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.65, pos.getZ() + 0.5,
                10, 0.3, 0.05, 0.3, 0.01);
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
        tag.putInt("Units", units);
        tag.putString("Grog", grogRecipeId);
        tag.putLong("FermentStart", fermentStart);
        tag.putInt("FermentTicks", fermentTicks);
        tag.putBoolean("Notified", notified);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        units = tag.getInt("Units");
        grogRecipeId = tag.getString("Grog");
        fermentStart = tag.getLong("FermentStart");
        fermentTicks = tag.getInt("FermentTicks");
        notified = tag.getBoolean("Notified");
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
