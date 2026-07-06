package com.bannerbound.core.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bannerbound.core.network.BarterEntry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * One settlement-to-settlement trade deal (player-to-player barter first, per CITY_STATES_PLAN.md
 * P4). Pure barter: proposerGives against recipientGives, item lines as {@link BarterEntry}. ONE
 * active deal per settlement pair - proposing is blocked while another deal with that partner is
 * open, which keeps the inbox, badges, and escrow trivially unambiguous.
 *
 * <p>Delivery is the "clock" model (no caravan entity yet; that's the physical-courier phase): on
 * accept each side's goods are withdrawn from its show-for-trading stockpiles when those chunks are
 * loaded (escrow), both shipments then travel on a distance timer, and arrivals deposit into the
 * receiver's trading stockpiles (retrying until loaded). Legs are independent after departure - a
 * defaulted escrow before departure refunds the other side; nothing is lost in transit. On each
 * Leg, null courierId/journeyId with arriveAt 0 mean the abstract clock carries it; a set journeyId
 * means a walking stocker does and arrival is event-driven. PARTIAL = one leg delivered, one failed
 * (courier killed - its cargo is loot on the ground).
 *
 * <p>Mutable, persisted inside {@link TradeData}; save/load mirrors the {@code CityState} pattern.
 * Both the State and Leg.LegState enums persist by ordinal, so only ever APPEND constants.
 */
public final class TradeDeal {
    // Appended-only: ordinals are persisted (save format).
    public enum State {
        PROPOSED,
        COUNTERED,
        ACCEPTED,
        IN_TRANSIT,
        DELIVERED,
        FAILED,
        REJECTED,
        CANCELLED,
        EXPIRED,
        PARTIAL;

        public static State fromOrdinalOrDefault(int ord) {
            State[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : EXPIRED;
        }

        public boolean active() {
            return this == PROPOSED || this == COUNTERED || this == ACCEPTED || this == IN_TRANSIT;
        }
    }

    public static final class Leg {
        // Appended-only: ordinals are persisted (save format).
        public enum LegState { PENDING, ESCROWED, IN_TRANSIT, ARRIVED_PENDING, DELIVERED, REFUNDING, REFUNDED, FAILED }

        public LegState state = LegState.PENDING;
        public final List<BarterEntry> manifest = new ArrayList<>();
        public long arriveAt;

        public UUID courierId;
        public UUID journeyId;
        public long courierSearchUntil;

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("State", state.ordinal());
            tag.putLong("ArriveAt", arriveAt);
            tag.put("Manifest", saveEntries(manifest));
            if (courierId != null) tag.putUUID("Courier", courierId);
            if (journeyId != null) tag.putUUID("Journey", journeyId);
            tag.putLong("CourierSearchUntil", courierSearchUntil);
            return tag;
        }

        static Leg load(CompoundTag tag) {
            Leg leg = new Leg();
            LegState[] v = LegState.values();
            int ord = tag.getInt("State");
            leg.state = (ord >= 0 && ord < v.length) ? v[ord] : LegState.FAILED;
            leg.arriveAt = tag.getLong("ArriveAt");
            loadEntries(tag.getList("Manifest", Tag.TAG_COMPOUND), leg.manifest);
            if (tag.hasUUID("Courier")) leg.courierId = tag.getUUID("Courier");
            if (tag.hasUUID("Journey")) leg.journeyId = tag.getUUID("Journey");
            leg.courierSearchUntil = tag.getLong("CourierSearchUntil");
            return leg;
        }
    }

    public final UUID id;
    public UUID proposer;
    public UUID recipient;
    public final List<BarterEntry> proposerGives = new ArrayList<>();
    public final List<BarterEntry> recipientGives = new ArrayList<>();
    public State state = State.PROPOSED;
    public UUID awaitingParty;
    public boolean unreadForAwaiting = true;
    public long proposedAt;
    public long expiresAt;
    public long resolvedAt;
    public long escrowDeadline;
    public final Leg proposerLeg = new Leg();
    public final Leg recipientLeg = new Leg();
    public String failReasonKey = "";

    public TradeDeal(UUID id) {
        this.id = id;
    }

    public UUID other(UUID settlementId) {
        if (proposer.equals(settlementId)) return recipient;
        if (recipient.equals(settlementId)) return proposer;
        return null;
    }

    public boolean involves(UUID settlementId) {
        return proposer.equals(settlementId) || recipient.equals(settlementId);
    }

    public List<BarterEntry> givesOf(UUID settlementId) {
        return proposer.equals(settlementId) ? proposerGives : recipientGives;
    }

    public Leg legOf(UUID settlementId) {
        return proposer.equals(settlementId) ? proposerLeg : recipientLeg;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Proposer", proposer);
        tag.putUUID("Recipient", recipient);
        tag.putInt("State", state.ordinal());
        if (awaitingParty != null) tag.putUUID("Awaiting", awaitingParty);
        tag.putBoolean("Unread", unreadForAwaiting);
        tag.putLong("ProposedAt", proposedAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putLong("ResolvedAt", resolvedAt);
        tag.putLong("EscrowDeadline", escrowDeadline);
        tag.put("ProposerGives", saveEntries(proposerGives));
        tag.put("RecipientGives", saveEntries(recipientGives));
        tag.put("ProposerLeg", proposerLeg.save());
        tag.put("RecipientLeg", recipientLeg.save());
        tag.putString("FailReason", failReasonKey);
        return tag;
    }

    public static TradeDeal load(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Proposer") || !tag.hasUUID("Recipient")) return null;
        TradeDeal deal = new TradeDeal(tag.getUUID("Id"));
        deal.proposer = tag.getUUID("Proposer");
        deal.recipient = tag.getUUID("Recipient");
        deal.state = State.fromOrdinalOrDefault(tag.getInt("State"));
        if (tag.hasUUID("Awaiting")) deal.awaitingParty = tag.getUUID("Awaiting");
        deal.unreadForAwaiting = tag.getBoolean("Unread");
        deal.proposedAt = tag.getLong("ProposedAt");
        deal.expiresAt = tag.getLong("ExpiresAt");
        deal.resolvedAt = tag.getLong("ResolvedAt");
        deal.escrowDeadline = tag.getLong("EscrowDeadline");
        loadEntries(tag.getList("ProposerGives", Tag.TAG_COMPOUND), deal.proposerGives);
        loadEntries(tag.getList("RecipientGives", Tag.TAG_COMPOUND), deal.recipientGives);
        copyLeg(Leg.load(tag.getCompound("ProposerLeg")), deal.proposerLeg);
        copyLeg(Leg.load(tag.getCompound("RecipientLeg")), deal.recipientLeg);
        deal.failReasonKey = tag.getString("FailReason");
        return deal;
    }

    private static void copyLeg(Leg from, Leg into) {
        into.state = from.state;
        into.arriveAt = from.arriveAt;
        into.manifest.clear();
        into.manifest.addAll(from.manifest);
    }

    static ListTag saveEntries(List<BarterEntry> entries) {
        ListTag list = new ListTag();
        for (BarterEntry e : entries) {
            if (e.count() <= 0) continue;
            CompoundTag c = new CompoundTag();
            c.putString("Item", e.itemId());
            c.putInt("Count", e.count());
            list.add(c);
        }
        return list;
    }

    static void loadEntries(ListTag list, List<BarterEntry> into) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            into.add(new BarterEntry(c.getString("Item"), c.getInt("Count"), 0));
        }
    }
}
