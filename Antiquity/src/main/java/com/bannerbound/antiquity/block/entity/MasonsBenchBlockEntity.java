package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.workshop.MasonryOutput;
import com.bannerbound.antiquity.workshop.MasonryOutputManager;
import com.bannerbound.antiquity.workshop.StoneFamily;

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
 * Block entity for the Mason's Bench -- batch stoneworking, the stone analogue of the Carpenter's
 * Table (no quality tiers: these are building materials). The deposited base stones are a real,
 * removable pile (one grid cell per distinct item, slide-in animated) that doubles as the masonry
 * budget; on top sits an ordered build list of queued outputs. Each output costs baseCost of
 * exactly one StoneFamily's base stone -- far simpler than carpentry's three-category budget --
 * and ListEntry captures familyKey + baseCost at queue time so the chisel step is self-contained.
 * The picker reuses the ghost-preview infrastructure ({@link GhostRecipeWorkstation}): the
 * floating ghost is the selected affordable output (there is never a separate solid result, so
 * getResult() is always EMPTY), browse arrows cycle offers, and a GhostActionPayload.FILL appends
 * one unit to the list. Offers are transient -- recomputed, never persisted -- from the families
 * present in the pile x researched (CraftGating) output variants, in stable LinkedHashMap
 * insertion order so browsing is predictable; the selection is sticky across recomputes so newly
 * landing materials don't yank the player's choice. Nothing is consumed until the chisel minigame
 * completes: completeAndOutput pops every queued item and removes only the stone the list cost,
 * leaving leftover pile on the bench, while removeOne refuses to hand back stone already reserved
 * by the build list (remaining = pile - committed). Ghost fields are computed server-side and
 * mirrored to the client via update packets; after a load, recompute is deferred to the first
 * server tick (needsRecompute) because CraftGating needs a live level.
 */
@ApiStatus.Internal
public class MasonsBenchBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    public static final int MAX_BUDGET = 64;
    public static final int SLIDE_TICKS = 6;

    public record ListEntry(Item output, int units, int yieldPerUnit, String familyKey, int baseCost) {}

    private record Offer(Item output, int yield, String familyKey, int baseCost) {}

    private final List<ItemStack> stones = new ArrayList<>();
    private final List<ListEntry> buildList = new ArrayList<>();

    private Item selectedOutput = null;

    private ItemStack ghostResult = ItemStack.EMPTY;
    private int offerCount = 0;

    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;
    private int lastSlideCell = -1;
    private boolean needsRecompute = false;

    public MasonsBenchBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.MASONS_BENCH_BE.get(), pos, state);
    }

    public List<ItemStack> getStones() {
        return stones;
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

    public ItemStack representativeBudgetStone() {
        return stones.isEmpty() ? new ItemStack(net.minecraft.world.level.block.Blocks.COBBLESTONE)
            : new ItemStack(stones.get(0).getItem());
    }

    public int remainingTotal() {
        int total = 0;
        for (ItemStack s : stones) total += s.getCount();
        for (ListEntry e : buildList) total -= e.units() * e.baseCost();
        return Math.max(0, total);
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
        return 1.62;
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
        // Intentional no-op: masonry's selection is always sticky, there is nothing to lock.
    }

    @Override
    public boolean insertOne(ItemStack held, Direction from) {
        if (!isBudgetMaterial(held) || totalStones() >= MAX_BUDGET) return false;
        insertAnimTicks = SLIDE_TICKS;
        insertDir = from == null ? Direction.NORTH : from;
        for (int i = 0; i < stones.size(); i++) {
            ItemStack s = stones.get(i);
            if (ItemStack.isSameItemSameComponents(s, held)) {
                s.grow(1);
                lastSlideCell = i;
                recompute();
                return true;
            }
        }
        stones.add(held.copyWithCount(1));
        lastSlideCell = stones.size() - 1;
        recompute();
        return true;
    }

    public static boolean isBudgetMaterial(ItemStack stack) {
        return StoneFamily.isBudgetStone(stack);
    }

    public int insertStack(ItemStack stack, Direction from) {
        int added = 0;
        while (added < stack.getCount() && insertOne(stack, from)) added++;
        return added;
    }

    @Override
    public ItemStack removeOne() {
        if (stones.isEmpty()) return ItemStack.EMPTY;
        for (int i = stones.size() - 1; i >= 0; i--) {
            ItemStack cell = stones.get(i);
            if (!isRemovable(cell)) continue;
            ItemStack out = cell.copyWithCount(1);
            cell.shrink(1);
            if (cell.isEmpty()) stones.remove(i);
            lastSlideCell = Math.min(lastSlideCell, stones.size() - 1);
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
        if (offer.baseCost() > remaining(offer.familyKey())) return false;
        for (int i = 0; i < buildList.size(); i++) {
            ListEntry e = buildList.get(i);
            if (e.output() == offer.output() && e.familyKey().equals(offer.familyKey())) {
                buildList.set(i, new ListEntry(e.output(), e.units() + 1, e.yieldPerUnit(),
                    e.familyKey(), e.baseCost()));
                applySelection(offer);
                recompute();
                return true;
            }
        }
        buildList.add(new ListEntry(offer.output(), 1, offer.yield(), offer.familyKey(), offer.baseCost()));
        applySelection(offer);
        recompute();
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
            buildList.set(last, new ListEntry(e.output(), e.units() - 1, e.yieldPerUnit(),
                e.familyKey(), e.baseCost()));
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
            removePileMatching(e.familyKey(), e.units() * e.baseCost());
        }
        buildList.clear();
        recompute();
    }

    public void dropStones(net.minecraft.world.level.Level level) {
        for (ItemStack s : stones) {
            Block.popResource(level, getBlockPos(), s);
        }
    }

    private void removePileMatching(String familyKey, int count) {
        StoneFamily fam = StoneFamily.fromKey(familyKey);
        if (fam == null) return;
        Item base = fam.baseItem();
        for (int i = stones.size() - 1; i >= 0 && count > 0; i--) {
            ItemStack cell = stones.get(i);
            if (cell.getItem() != base) continue;
            int take = Math.min(count, cell.getCount());
            cell.shrink(take);
            count -= take;
            if (cell.isEmpty()) stones.remove(i);
        }
        lastSlideCell = Math.min(lastSlideCell, stones.size() - 1);
    }

    private int totalStones() {
        int n = 0;
        for (ItemStack s : stones) n += s.getCount();
        return n;
    }

    private Map<String, Integer> familyCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : stones) {
            StoneFamily fam = StoneFamily.fromBase(s.getItem());
            if (fam != null) m.merge(fam.key(), s.getCount(), Integer::sum);
        }
        return m;
    }

    private int pileMatching(String familyKey) {
        StoneFamily fam = StoneFamily.fromKey(familyKey);
        if (fam == null) return 0;
        Item base = fam.baseItem();
        int n = 0;
        for (ItemStack s : stones) {
            if (s.getItem() == base) n += s.getCount();
        }
        return n;
    }

    private int committed(String familyKey) {
        int n = 0;
        for (ListEntry e : buildList) {
            if (e.familyKey().equals(familyKey)) n += e.units() * e.baseCost();
        }
        return n;
    }

    private int remaining(String familyKey) {
        return pileMatching(familyKey) - committed(familyKey);
    }

    private boolean isRemovable(ItemStack cell) {
        StoneFamily fam = StoneFamily.fromBase(cell.getItem());
        if (fam == null) return true;
        return remaining(fam.key()) > 0;
    }

    private List<Offer> computeOffers() {
        List<Offer> out = new ArrayList<>();
        if (level == null) return out;
        for (String key : familyCounts().keySet()) {
            StoneFamily fam = StoneFamily.fromKey(key);
            if (fam == null) continue;
            for (MasonryOutput o : MasonryOutputManager.all()) {
                if (o.baseCost() > remaining(key)) continue;
                Item item = fam.variant(o.variant());
                if (item == null) continue;
                if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                        level, getBlockPos(), item)) continue;
                out.add(new Offer(item, o.yield(), key, o.baseCost()));
            }
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
                            MasonsBenchBlockEntity be) {
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
        ListTag stonesTag = new ListTag();
        for (ItemStack s : stones) {
            if (!s.isEmpty()) stonesTag.add(s.save(provider));
        }
        tag.put("Stones", stonesTag);

        ListTag listTag = new ListTag();
        for (ListEntry e : buildList) {
            CompoundTag c = new CompoundTag();
            c.putString("Item", BuiltInRegistries.ITEM.getKey(e.output()).toString());
            c.putInt("Units", e.units());
            c.putInt("Yield", e.yieldPerUnit());
            c.putString("Family", e.familyKey());
            c.putInt("BaseCost", e.baseCost());
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
        stones.clear();
        ListTag stonesTag = tag.getList("Stones", Tag.TAG_COMPOUND);
        for (int i = 0; i < stonesTag.size(); i++) {
            ItemStack.parse(provider, stonesTag.getCompound(i)).ifPresent(stones::add);
        }
        buildList.clear();
        ListTag listTag = tag.getList("BuildList", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag c = listTag.getCompound(i);
            if (!c.contains("Family")) continue;
            Item item = itemOf(c.getString("Item"));
            if (item == null) continue;
            buildList.add(new ListEntry(item, c.getInt("Units"), c.getInt("Yield"),
                c.getString("Family"), c.getInt("BaseCost")));
        }
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        insertDir = Direction.from3DDataValue(tag.getInt("InsertDir"));
        lastSlideCell = Math.min(tag.getInt("LastSlideCell"), stones.size() - 1);
        needsRecompute = true;
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
