package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.carpentry.CarpentryAssembly;
import com.bannerbound.antiquity.carpentry.CarpentryAssemblyManager;
import com.bannerbound.antiquity.carpentry.CarpentryOutput;
import com.bannerbound.antiquity.carpentry.CarpentryOutputManager;
import com.bannerbound.antiquity.carpentry.Cost;
import com.bannerbound.antiquity.carpentry.WoodFamily;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Carpenter's Table, the batch-woodworking workstation. Deposited materials form
 * a real, removable pile (same idiom as the Crafting Stone: placed and taken back one at a time,
 * rendered in a grid) which doubles as the wood budget (max {@link #MAX_BUDGET}); on top of that sits
 * an ordered build list of queued outputs. The picker reuses the ghost-preview infrastructure
 * ({@link GhostRecipeWorkstation}): the floating ghost result is the currently-selected affordable
 * output, the browse arrows cycle offers (family/variant outputs from logs, then assembly recipes from
 * planks/sticks, in a stable order), and clicking the result (a GhostActionPayload.FILL special-case
 * for this BE) appends one unit to the list. Nothing is consumed until the saw minigame completes; it
 * then pops the queued items above the table and removes only the materials the list cost, leaving any
 * leftover pile in place. Budget accounting is fully derived, never stored: each queued entry captures
 * its per-unit costs and yield at queue time (so the saw step is self-contained), reservations sharing
 * a cost signature (kind|ref, e.g. every "#planks" cost) compete for one pool, and pile removal is
 * refused only while a pool the cell belongs to has no slack. All pile and build-list mutations are
 * server-side; recompute() rebuilds the synced ghost fields (result + offer count, client just renders
 * them) and pushes a block update. The picker selection is sticky across recomputes and identified by
 * output item alone, which works because offer outputs are unique across families and assembly recipes.
 * Legacy pre-cost-model build-list entries are dropped on load.
 */
@ApiStatus.Internal
public class WoodworkingTableBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    public static final int MAX_BUDGET = 64;
    public static final int SLIDE_TICKS = 6;

    public record ListEntry(Item output, int units, int yieldPerUnit, List<Cost> costs) {}

    private record Offer(Item output, int yield, List<Cost> costs) {}

    private final List<ItemStack> logs = new ArrayList<>();
    private final List<ListEntry> buildList = new ArrayList<>();

    private Item selectedOutput = null;

    private ItemStack ghostResult = ItemStack.EMPTY;
    private int offerCount = 0;

    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;
    private int lastSlideCell = -1;
    private boolean needsRecompute = false;

    public WoodworkingTableBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.WOODWORKING_TABLE_BE.get(), pos, state);
    }

    public List<ItemStack> getLogs() {
        return logs;
    }

    public List<ListEntry> getBuildList() {
        return buildList;
    }

    public boolean hasBuildList() {
        return !buildList.isEmpty();
    }

    public int getInsertAnimTicks() {
        return insertAnimTicks;
    }

    public Direction getInsertDir() {
        return insertDir;
    }

    public int getLastSlideCell() {
        return lastSlideCell;
    }

    public ItemStack representativeBudgetLog() {
        for (ItemStack s : logs) {
            if (WoodFamily.isBudgetLog(s)) return new ItemStack(s.getItem());
        }
        return logs.isEmpty() ? ItemStack.EMPTY : new ItemStack(logs.get(0).getItem());
    }

    @Override
    public ItemStack getResult() {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack getGhostResult() {
        return ghostResult;
    }

    @Override
    public List<ItemStack> getGhostIngredients() {
        return List.of();
    }

    @Override
    public int getGhostCandidateCount() {
        return offerCount;
    }

    @Override
    public double ghostPreviewY() {
        return 1.62; // picker band height must line up with the readout renderer
    }

    public int[] remainingByCategory() {
        int[] net = new int[3];
        for (ItemStack s : logs) {
            int idx = catIndex(Cost.categoryOf(s));
            if (idx >= 0) net[idx] += s.getCount();
        }
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                int idx = catIndex(c.category());
                if (idx >= 0) net[idx] -= e.units() * c.perUnit();
            }
        }
        for (int i = 0; i < net.length; i++) net[i] = Math.max(0, net[i]);
        return net;
    }

    private static int catIndex(Cost.Category cat) {
        return switch (cat) {
            case LOG -> 0;
            case PLANK -> 1;
            case STICK -> 2;
            case OTHER -> -1;
        };
    }

    @Override
    public void cycleGhost(int dir) {
        List<Offer> offers = computeOffers();
        if (offers.size() < 2) return;
        int idx = Math.floorMod(Math.max(0, indexOfSelection(offers)) + dir, offers.size());
        applySelection(offers.get(idx));
        recompute();
    }

    @Override
    public void lockGhost() {
    }

    @Override
    public boolean insertOne(ItemStack held, Direction from) {
        if (!isBudgetMaterial(held) || totalLogs() >= MAX_BUDGET) return false;
        insertAnimTicks = SLIDE_TICKS;
        insertDir = from == null ? Direction.NORTH : from;
        for (int i = 0; i < logs.size(); i++) {
            ItemStack s = logs.get(i);
            if (ItemStack.isSameItemSameComponents(s, held)) {
                s.grow(1);
                lastSlideCell = i;
                recompute();
                return true;
            }
        }
        logs.add(held.copyWithCount(1));
        lastSlideCell = logs.size() - 1;
        recompute();
        return true;
    }

    public static boolean isBudgetMaterial(ItemStack stack) {
        return WoodFamily.isBudgetLog(stack) || CarpentryAssemblyManager.isIngredient(stack);
    }

    public int insertStack(ItemStack stack, Direction from) {
        int added = 0;
        while (added < stack.getCount() && insertOne(stack, from)) added++;
        return added;
    }

    public ItemStack removeOne() {
        if (logs.isEmpty()) return ItemStack.EMPTY;
        for (int i = logs.size() - 1; i >= 0; i--) {
            ItemStack cell = logs.get(i);
            if (!isRemovable(cell)) continue;
            ItemStack out = cell.copyWithCount(1);
            cell.shrink(1);
            if (cell.isEmpty()) logs.remove(i);
            lastSlideCell = Math.min(lastSlideCell, logs.size() - 1);
            recompute();
            return out;
        }
        return ItemStack.EMPTY;
    }

    public boolean addSelected() {
        List<Offer> offers = computeOffers();
        int idx = indexOfSelection(offers);
        if (idx < 0) {
            if (offers.isEmpty()) return false;
            idx = 0;
        }
        Offer offer = offers.get(idx);
        if (!affordable(offer.costs())) return false;
        for (int i = 0; i < buildList.size(); i++) {
            ListEntry e = buildList.get(i);
            if (e.output() == offer.output() && e.costs().equals(offer.costs())) {
                buildList.set(i, new ListEntry(e.output(), e.units() + 1, e.yieldPerUnit(), e.costs()));
                applySelection(offer);
                recompute();
                return true;
            }
        }
        buildList.add(new ListEntry(offer.output(), 1, offer.yield(), offer.costs()));
        applySelection(offer);
        recompute();
        return true;
    }

    private boolean affordable(List<Cost> costs) {
        for (Cost c : costs) {
            if (c.perUnit() > remaining(c)) return false;
        }
        return true;
    }

    public boolean removeEntryAt(int index) {
        if (index < 0 || index >= buildList.size()) return false;
        buildList.remove(index);
        recompute();
        return true;
    }

    public boolean removeLastEntry() {
        if (buildList.isEmpty()) return false;
        int last = buildList.size() - 1;
        ListEntry e = buildList.get(last);
        if (e.units() <= 1) {
            buildList.remove(last);
        } else {
            buildList.set(last, new ListEntry(e.output(), e.units() - 1, e.yieldPerUnit(), e.costs()));
        }
        recompute();
        return true;
    }

    public void completeAndOutput(ServerLevel level) {
        BlockPos above = getBlockPos().above();
        for (ListEntry e : buildList) {
            int total = e.units() * e.yieldPerUnit();
            while (total > 0) {
                int n = Math.min(total, e.output().getDefaultMaxStackSize());
                Block.popResource(level, above, new ItemStack(e.output(), n));
                total -= n;
            }
        }
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                removePileMatching(c, e.units() * c.perUnit());
            }
        }
        buildList.clear();
        recompute();
    }

    public void dropLogs(net.minecraft.world.level.Level level) {
        for (ItemStack s : logs) {
            Block.popResource(level, getBlockPos(), s);
        }
    }

    private void removePileMatching(Cost cost, int count) {
        for (int i = logs.size() - 1; i >= 0 && count > 0; i--) {
            ItemStack cell = logs.get(i);
            if (!cost.matches(cell)) continue;
            int take = Math.min(count, cell.getCount());
            cell.shrink(take);
            count -= take;
            if (cell.isEmpty()) logs.remove(i);
        }
        lastSlideCell = Math.min(lastSlideCell, logs.size() - 1);
    }

    private int totalLogs() {
        int n = 0;
        for (ItemStack s : logs) n += s.getCount();
        return n;
    }

    private Map<String, Integer> familyCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : logs) {
            WoodFamily fam = WoodFamily.fromLog(s.getItem());
            if (fam != null) m.merge(fam.key(), s.getCount(), Integer::sum);
        }
        return m;
    }

    private int pileMatching(Cost cost) {
        int n = 0;
        for (ItemStack s : logs) {
            if (cost.matches(s)) n += s.getCount();
        }
        return n;
    }

    private int committed(Cost cost) {
        String sig = signature(cost);
        int n = 0;
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                if (signature(c).equals(sig)) n += e.units() * c.perUnit();
            }
        }
        return n;
    }

    private int remaining(Cost cost) {
        return pileMatching(cost) - committed(cost);
    }

    private static String signature(Cost cost) {
        return cost.kind() + "|" + cost.ref();
    }

    private boolean isRemovable(ItemStack cell) {
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                if (c.matches(cell) && remaining(c) <= 0) return false;
            }
        }
        return true;
    }

    private List<Offer> computeOffers() {
        List<Offer> out = new ArrayList<>();
        if (level == null) return out;
        for (String key : familyCounts().keySet()) {
            WoodFamily fam = WoodFamily.fromKey(key);
            if (fam == null) continue;
            for (CarpentryOutput o : CarpentryOutputManager.all()) {
                Cost cost = new Cost(Cost.Kind.FAMILY, key, o.logCost());
                if (cost.perUnit() > remaining(cost)) continue;
                Item item = fam.variant(o.variant());
                if (item == null) continue;
                if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                        level, getBlockPos(), item)) continue;
                out.add(new Offer(item, o.yield(), List.of(cost)));
            }
        }
        for (CarpentryAssembly a : CarpentryAssemblyManager.all()) {
            if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), a.result())) continue;
            List<Cost> costs = a.costs();
            if (!affordable(costs)) continue;
            out.add(new Offer(a.result(), a.yield(), costs));
        }
        return out;
    }

    private int indexOfSelection(List<Offer> offers) {
        for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i).output() == selectedOutput) return i;
        }
        return -1;
    }

    private void applySelection(Offer offer) {
        selectedOutput = offer.output();
    }

    private void recompute() {
        if (level == null || level.isClientSide) {
            setChanged();
            return;
        }
        List<Offer> offers = computeOffers();
        offerCount = offers.size();
        if (offers.isEmpty()) {
            ghostResult = ItemStack.EMPTY;
            selectedOutput = null;
            setChanged();
            return;
        }
        int idx = indexOfSelection(offers);
        if (idx < 0) {
            applySelection(offers.get(0));
            idx = 0;
        }
        Offer sel = offers.get(idx);
        ghostResult = new ItemStack(sel.output(), sel.yield());
        setChanged();
    }

    public static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            WoodworkingTableBlockEntity be) {
        if (be.insertAnimTicks > 0) be.insertAnimTicks--;
        if (!level.isClientSide && be.needsRecompute) {
            be.needsRecompute = false;
            be.recompute();
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
        ListTag logsTag = new ListTag();
        for (ItemStack s : logs) {
            if (!s.isEmpty()) logsTag.add(s.save(provider));
        }
        tag.put("Logs", logsTag);

        ListTag listTag = new ListTag();
        for (ListEntry e : buildList) {
            CompoundTag c = new CompoundTag();
            c.putString("Item", BuiltInRegistries.ITEM.getKey(e.output()).toString());
            c.putInt("Units", e.units());
            c.putInt("Yield", e.yieldPerUnit());
            ListTag costsTag = new ListTag();
            for (Cost cost : e.costs()) costsTag.add(cost.save());
            c.put("Costs", costsTag);
            listTag.add(c);
        }
        tag.put("BuildList", listTag);

        tag.putInt("InsertAnimTicks", insertAnimTicks);
        tag.putInt("InsertDir", insertDir.get3DDataValue());
        tag.putInt("LastSlideCell", lastSlideCell);
        if (selectedOutput != null) {
            tag.putString("SelItem", BuiltInRegistries.ITEM.getKey(selectedOutput).toString());
        }
        if (!ghostResult.isEmpty()) tag.put("Ghost", ghostResult.save(provider));
        tag.putInt("OfferCount", offerCount);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        logs.clear();
        ListTag logsTag = tag.getList("Logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < logsTag.size(); i++) {
            ItemStack.parse(provider, logsTag.getCompound(i)).ifPresent(logs::add);
        }
        buildList.clear();
        ListTag listTag = tag.getList("BuildList", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag c = listTag.getCompound(i);
            if (!c.contains("Costs")) continue; // legacy pre-cost-model saves lack Costs; drop them
            Item item = itemOf(c.getString("Item"));
            if (item == null) continue;
            List<Cost> costs = new ArrayList<>();
            ListTag costsTag = c.getList("Costs", Tag.TAG_COMPOUND);
            for (int j = 0; j < costsTag.size(); j++) costs.add(Cost.load(costsTag.getCompound(j)));
            buildList.add(new ListEntry(item, c.getInt("Units"), c.getInt("Yield"), costs));
        }
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        insertDir = Direction.from3DDataValue(tag.getInt("InsertDir"));
        lastSlideCell = Math.min(tag.getInt("LastSlideCell"), logs.size() - 1);
        needsRecompute = true; // level is null during load; recompute must wait for the first server tick
        selectedOutput = tag.contains("SelItem") ? itemOf(tag.getString("SelItem")) : null;
        ghostResult = tag.contains("Ghost")
            ? ItemStack.parse(provider, tag.getCompound("Ghost")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        offerCount = tag.getInt("OfferCount");
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.get(rl) : null;
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
