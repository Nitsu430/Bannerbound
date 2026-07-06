package com.bannerbound.core.social;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * One citizen's relationships with every other citizen they have interacted with, stored as a
 * Map<UUID, Relationship> keyed by the other citizen's entity UUID. The whole social system's tier
 * thresholds (ACQUAINTANCES..FRIENDS_FOR_LIFE) live as constants here so balance edits touch one file.
 *
 * <p>get() returns Relationship.STRANGERS for unknown UUIDs WITHOUT storing anything, keeping the map
 * small for citizens who only ever met a handful of others; that STRANGERS default is intentionally
 * excluded from entries(). applyDelta clamps score+delta into [MIN, MAX] and stamps lastInteractTick.
 * setScore is a debug-only hard-overwrite ({@code /bannerbound set_relationship}) that bypasses the
 * family/lover guard rails, so normal gameplay must go through applyDelta. linkFamily installs the
 * canonical Relationship.FAMILY record, used at birth for the permanent parent<->child bond. Every
 * mutator leaves setDirty to the caller (see SocialEvents, the symmetric chokepoint that keeps both
 * sides of a pair in agreement).
 *
 * <p>NBT: a list of compound tags under the "Relations" key on CitizenEntity, each shaped
 * { "Other": UUID, "S": int, "L": int, "B": int, "T": long }. save() should be skipped when isEmpty().
 * load() is tolerant: entries missing "Other" are dropped rather than crashing the citizen on load.
 */
public final class Relationships {
    public static final int MIN = -100;
    public static final int MAX = 100;
    public static final int ACQUAINTANCES    = 10;
    public static final int FRIENDS          = 25;
    public static final int CLOSE_FRIENDS    = 50;
    public static final int FRIENDS_FOR_LIFE = 80;

    private final Map<UUID, Relationship> byOther = new HashMap<>();

    public Relationship get(UUID other) {
        Relationship r = byOther.get(other);
        return r != null ? r : Relationship.STRANGERS;
    }

    public Relationship applyDelta(UUID other, int delta, long now) {
        Relationship next = get(other).withScoreDelta(delta, now);
        byOther.put(other, next);
        return next;
    }

    public void linkFamily(UUID other) {
        byOther.put(other, Relationship.FAMILY);
    }

    public void setScore(UUID other, int score, long now) {
        int clamped = Math.max(MIN, Math.min(MAX, score));
        byOther.put(other, new Relationship(clamped, 0, 0, now, false));
    }

    public boolean forget(UUID dead) {
        return byOther.remove(dead) != null;
    }

    public boolean isEmpty() {
        return byOther.isEmpty();
    }

    public Map<UUID, Relationship> entries() {
        return Collections.unmodifiableMap(byOther);
    }

    public RelationshipTier tierWith(UUID other) {
        return get(other).tier();
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Relationship> e : byOther.entrySet()) {
            CompoundTag entry = e.getValue().save();
            entry.put("Other", NbtUtils.createUUID(e.getKey()));
            list.add(entry);
        }
        return list;
    }

    public void load(ListTag list) {
        byOther.clear();
        if (list == null) return;
        for (Tag t : list) {
            if (!(t instanceof CompoundTag entry)) continue;
            if (!entry.contains("Other")) continue;
            UUID other = NbtUtils.loadUUID(entry.get("Other"));
            byOther.put(other, Relationship.load(entry));
        }
    }
}
