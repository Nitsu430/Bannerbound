package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Generic storage-container access for citizen jobs: resolve the {@link Container} behind a marked
 * drop-off / supply block and move items in and out. Workers no longer route yield into a workstation
 * block entity; the player marks a vanilla chest, an Antiquity basket, or a Stockpile block, and the
 * worker inserts into whatever container backs it.
 *
 * <p>Core has NO compile dependency on Antiquity, so the basket is matched by registry id
 * ({@code bannerboundantiquity:basket}), never {@code instanceof}; chest and basket both expose a
 * vanilla {@link Container} via their block entity, which is all the insert/room math needs. Double
 * chests resolve to their combined container so both halves fill; anything that aggregates or counts
 * over container positions MUST skip the secondary half (see {@link #isSecondaryChestHalf}) or the
 * shared inventory is counted twice. A Stockpile block fans out to an {@link AggregateContainer} of
 * every container its enclosure scan found -- always a markable target, workers just wait when it is
 * full.
 *
 * <p>Resolution ladder for the self-directed gatherers: {@link #resolveJobDepot} takes the marked
 * drop-off if it resolves, else -- for an anarchy citizen whose drop-off is the town-hall sentinel --
 * the citizen's 64-item carry pack, which is physically hauled to the town hall and dumped (no
 * teleport). {@link #resolveOrPreferred}/{@link #resolveSupply} take a marked pos (only outpost
 * workers and the anarchy sink still set one) else fall back to the settlement storage pool's nearest
 * deposit-/take-open container. {@link #resolvePreferredStorage} is deprecated, subsumed by the pool
 * and kept only for legacy ghost-stocker road planning.
 *
 * <p>{@link #isWildStorage} tells AUTOMATIC detection (stocker loose-source scan, item census) to skip
 * unopened loot containers and containers buried more than {@link #CELLAR_DEPTH} blocks below the
 * surface heightmap (generated-structure territory, not the settlement pantry); explicitly marked
 * storage always counts. {@link #insert} is two-pass (merge into matching stacks, then fill empties)
 * and honors each slot's {@link Container#canPlaceItem} and max stack size; {@link #roomFor} counts an
 * empty slot as a full clamped stack plus any matching-slot leftover; {@link #hasFreeSlot} is the gate
 * for workers whose yield is unknown ahead of time (fisher catch, forager drop).
 */
@ApiStatus.Internal
public final class DropOffContainers {
    private static final ResourceLocation BASKET_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "basket");

    private DropOffContainers() {
    }

    @Nullable
    public static Container resolveDropOff(Level level, @Nullable BlockPos pos) {
        if (pos == null) return null;
        BlockState state = level.getBlockState(pos);
        if (state.is(BannerboundCore.STOCKPILE.get()) && level instanceof ServerLevel sl) {
            return resolveStockpile(sl, pos);
        }
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, level, pos, true);
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (BASKET_ID.equals(id)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c) return c;
        }
        return null;
    }

    @Nullable
    public static Container resolveJobDepot(CitizenEntity citizen) {
        Container c = resolveOrPreferred(citizen, citizen.getDropOff());
        if (c != null) return c;
        if (citizen.isAnarchyHaulDropOff()) return citizen.getAnarchyHaul();
        return null;
    }

    @Nullable
    public static Container resolveOrPreferred(CitizenEntity citizen, @Nullable BlockPos pos) {
        Container real = resolveDropOff(citizen.level(), pos);
        if (real != null) return real;
        return depotPool(citizen);
    }

    @Nullable
    public static Container resolveSupply(CitizenEntity citizen, @Nullable BlockPos pos) {
        Container real = resolveDropOff(citizen.level(), pos);
        if (real != null) return real;
        return supplyPool(citizen);
    }

    @Nullable
    public static Container depotPool(CitizenEntity citizen) {
        Settlement s = poolSettlement(citizen);
        if (s == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        return SettlementStorage.depotAggregate(sl, s, citizen.blockPosition());
    }

    @Nullable
    public static Container supplyPool(CitizenEntity citizen) {
        Settlement s = poolSettlement(citizen);
        if (s == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        return SettlementStorage.supplyAggregate(sl, s, citizen.blockPosition());
    }

    @Nullable
    private static Settlement poolSettlement(CitizenEntity citizen) {
        if (citizen.getSettlementId() == null
                || !(citizen.level() instanceof ServerLevel sl)) return null;
        MinecraftServer server = sl.getServer();
        if (server == null) return null;
        return SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
    }

    @Deprecated
    @Nullable
    public static Container resolvePreferredStorage(CitizenEntity citizen) {
        return supplyPool(citizen);
    }

    @Nullable
    private static Container resolveStockpile(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        Settlement owner = SettlementData.get(server.overworld())
            .getByChunk(new ChunkPos(pos).toLong());
        if (owner == null) return null;
        Stockpile sp = owner.getStockpile(pos);
        if (sp == null) return null;
        List<Container> parts = new ArrayList<>();
        if (sp.valid()) {
            for (BlockPos cpos : sp.containers()) {
                if (isSecondaryChestHalf(level, cpos)) continue;   // far half double-counts the shared inventory
                Container c = resolveContainerAt(level, cpos);
                if (c != null) parts.add(c);
            }
        }
        return new AggregateContainer(parts);
    }

    public static boolean isSecondaryChestHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return false;
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return false;
        return pos.relative(ChestBlock.getConnectedDirection(state)).asLong() < pos.asLong();
    }

    @Nullable
    private static Container resolveContainerAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, level, pos, true);
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    public static boolean isWildStorage(ServerLevel sl, BlockPos pos) {
        BlockEntity be = sl.getBlockEntity(pos);
        if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r
                && r.getLootTable() != null) {
            return true;
        }
        int surface = sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
            pos.getX(), pos.getZ());
        return pos.getY() < surface - CELLAR_DEPTH;
    }

    public static final int CELLAR_DEPTH = 12;

    public static boolean isDropOffBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BannerboundCore.STOCKPILE.get())) return true;
        if (state.getBlock() instanceof ChestBlock) return true;
        return BASKET_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static ItemStack extract(Container container, net.minecraft.world.item.Item item, int maxCount) {
        int taken = 0;
        for (int i = 0; i < container.getContainerSize() && taken < maxCount; i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty() || !slot.is(item)) continue;
            int move = Math.min(maxCount - taken, slot.getCount());
            slot.shrink(move);
            if (slot.isEmpty()) container.setItem(i, ItemStack.EMPTY);
            taken += move;
        }
        if (taken == 0) return ItemStack.EMPTY;
        container.setChanged();
        return new ItemStack(item, taken);
    }

    public static ItemStack insert(Container container, ItemStack stack) {
        if (stack.isEmpty()) return stack;
        ItemStack remaining = stack.copy();
        boolean changed = false;
        int size = container.getContainerSize();
        for (int i = 0; i < size && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(slot, remaining)) continue;
            int cap = Math.min(container.getMaxStackSize(), slot.getMaxStackSize());
            int room = cap - slot.getCount();
            if (room <= 0) continue;
            int move = Math.min(room, remaining.getCount());
            slot.grow(move);
            remaining.shrink(move);
            changed = true;
        }
        for (int i = 0; i < size && !remaining.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty()) continue;
            if (!container.canPlaceItem(i, remaining)) continue;
            int cap = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
            int move = Math.min(cap, remaining.getCount());
            ItemStack placed = remaining.copy();
            placed.setCount(move);
            container.setItem(i, placed);
            remaining.shrink(move);
            changed = true;
        }
        if (changed) container.setChanged();
        return remaining;
    }

    public static ItemStack extractOne(Container container, net.minecraft.world.item.Item item) {
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && slot.is(item)) {
                ItemStack one = slot.copy();
                one.setCount(1);
                slot.shrink(1);
                container.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
                container.setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean contains(Container container, net.minecraft.world.item.Item item) {
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && slot.is(item)) return true;
        }
        return false;
    }

    public static boolean hasFreeSlot(Container container) {
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (container.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    public static int roomFor(Container container, ItemStack template) {
        if (template.isEmpty()) return 0;
        int cap = Math.min(container.getMaxStackSize(), template.getMaxStackSize());
        int room = 0;
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                if (container.canPlaceItem(i, template)) room += cap;
            } else if (ItemStack.isSameItemSameComponents(slot, template)) {
                room += Math.max(0, cap - slot.getCount());
            }
        }
        return room;
    }
}
