package com.bannerbound.core.trade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Top-level SavedData for settlement-to-settlement trade deals. A deal spans TWO parties, so it lives
 * here rather than on either Settlement; it is attached to the OVERWORLD, so call get(ServerLevel)
 * server-side, and every mutator calls setDirty(). Query helpers back the Diplomacy tab: activeBetween
 * finds the one active deal for a pair (order-independent), activeFor lists a settlement's active
 * deals, and unreadCountFor drives the tab's unread badge. Resolved deals linger briefly for the
 * record then TradeManager sweeps them once stale (no history UI yet, so the map stays small).
 */
public class TradeData extends SavedData {
    private static final String DATA_NAME = "bannerbound_trades";

    private final Map<UUID, TradeDeal> deals = new HashMap<>();

    public TradeData() {
    }

    public static TradeData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<TradeData> factory() {
        return new Factory<>(TradeData::new, TradeData::load);
    }

    public Collection<TradeDeal> all() {
        return Collections.unmodifiableCollection(deals.values());
    }

    @Nullable
    public TradeDeal getById(UUID id) {
        return id == null ? null : deals.get(id);
    }

    public void add(TradeDeal deal) {
        deals.put(deal.id, deal);
        setDirty();
    }

    public void remove(UUID id) {
        deals.remove(id);
        setDirty();
    }

    @Nullable
    public TradeDeal activeBetween(UUID a, UUID b) {
        for (TradeDeal d : deals.values()) {
            if (d.state.active() && d.involves(a) && d.involves(b)) return d;
        }
        return null;
    }

    public List<TradeDeal> activeFor(UUID settlementId) {
        List<TradeDeal> out = new ArrayList<>();
        for (TradeDeal d : deals.values()) {
            if (d.state.active() && d.involves(settlementId)) out.add(d);
        }
        return out;
    }

    public int unreadCountFor(UUID settlementId, UUID withPartner) {
        TradeDeal d = activeBetween(settlementId, withPartner);
        return d != null && d.unreadForAwaiting
            && settlementId.equals(d.awaitingParty)
            && (d.state == TradeDeal.State.PROPOSED || d.state == TradeDeal.State.COUNTERED) ? 1 : 0;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (TradeDeal d : deals.values()) list.add(d.save());
        tag.put("Deals", list);
        return tag;
    }

    public static TradeData load(CompoundTag tag, HolderLookup.Provider provider) {
        TradeData data = new TradeData();
        ListTag list = tag.getList("Deals", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            TradeDeal d = TradeDeal.load(list.getCompound(i));
            if (d != null) data.deals.put(d.id, d);
        }
        return data;
    }
}
