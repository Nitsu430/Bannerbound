package com.bannerbound.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

/**
 * One citizen's active list of {@link Thought}s and the source of truth for their happiness. Overall
 * happiness is the AVERAGE of the four HappinessCategory pillar satisfactions (so each pillar is an
 * equal 25-point slice); each pillar = BASE_HAPPINESS + the sum of that category's active thoughts'
 * effectiveModifier, clamped to [MIN_HAPPINESS, MAX_HAPPINESS]. Reading through effectiveModifier means
 * escalating grievances deepen with age everywhere the number is shown or felt. BASE_HAPPINESS (60) is
 * the neutral baseline a citizen with no thoughts sits at.
 *
 * <p>Mutation API: add/addWithModifier (add or refresh the entry for a (kind, partner) pair; partner
 * is null for solo thoughts; addWithModifier overrides the kind's default magnitude for per-occurrence
 * thoughts and is only valid for non-escalating kinds), remove (drop one entry, e.g. clear UNEMPLOYED
 * on assignment), and tick (drop expired entries). Each returns whether something changed so the caller
 * can decide when to recompute and resync happiness. Type identity is compared by reference (registered
 * types are singletons) and, defensively, by id.
 *
 * <p>NBT: a list of compound tags under the "Thoughts" key on CitizenEntity (see {@link Thought#save}).
 * load() is tolerant -- entries whose kind no longer resolves are silently dropped.
 */
public final class Thoughts {
    public static final int BASE_HAPPINESS = 60;
    public static final int MIN_HAPPINESS = 0;
    public static final int MAX_HAPPINESS = 100;

    private final List<Thought> entries = new ArrayList<>();

    public boolean isEmpty() { return entries.isEmpty(); }

    public List<Thought> entries() { return Collections.unmodifiableList(entries); }

    public int categorySatisfaction(HappinessCategory cat, long now) {
        int sum = BASE_HAPPINESS;
        for (Thought t : entries) {
            if (t.kind() != null && t.kind().category() == cat) sum += t.effectiveModifier(now);
        }
        if (sum < MIN_HAPPINESS) return MIN_HAPPINESS;
        if (sum > MAX_HAPPINESS) return MAX_HAPPINESS;
        return sum;
    }

    public int aggregateHappiness(long now) {
        HappinessCategory[] cats = HappinessCategory.values();
        int total = 0;
        for (HappinessCategory c : cats) total += categorySatisfaction(c, now);
        return Math.round(total / (float) cats.length);
    }

    public Thought add(ThoughtType kind, @Nullable UUID partner, long now, RandomSource rng) {
        return add(kind, partner, null, now, rng);
    }

    public Thought add(ThoughtType kind, @Nullable UUID partner, @Nullable String savedName,
                       long now, RandomSource rng) {
        int duration = kind.rollDurationTicks(rng);
        long expire = duration == ThoughtKind.INFINITE_DURATION
            ? Thought.INFINITE_EXPIRY
            : now + duration;
        Thought next = new Thought(kind, kind.modifier(), expire, duration, now, partner, savedName);
        for (int i = 0; i < entries.size(); i++) {
            Thought t = entries.get(i);
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) {
                entries.set(i, next);
                return next;
            }
        }
        entries.add(next);
        return next;
    }

    public Thought addWithModifier(ThoughtType kind, @Nullable UUID partner, int modifier,
                                   long now, RandomSource rng) {
        int duration = kind.rollDurationTicks(rng);
        long expire = duration == ThoughtKind.INFINITE_DURATION ? Thought.INFINITE_EXPIRY : now + duration;
        Thought next = new Thought(kind, modifier, expire, duration, now, partner, null);
        for (int i = 0; i < entries.size(); i++) {
            Thought t = entries.get(i);
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) {
                entries.set(i, next);
                return next;
            }
        }
        entries.add(next);
        return next;
    }

    public boolean remove(ThoughtType kind, @Nullable UUID partner) {
        for (int i = 0; i < entries.size(); i++) {
            Thought t = entries.get(i);
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    public boolean has(ThoughtType kind, @Nullable UUID partner) {
        for (Thought t : entries) {
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) return true;
        }
        return false;
    }

    private static boolean sameKind(ThoughtType a, ThoughtType b) {
        return a == b || (a != null && b != null && a.id().equals(b.id()));
    }

    public boolean tick(long now) {
        boolean changed = false;
        Iterator<Thought> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().isExpired(now)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Thought t : entries) list.add(t.save());
        return list;
    }

    public void load(ListTag list) {
        entries.clear();
        if (list == null) return;
        for (Tag t : list) {
            if (!(t instanceof CompoundTag ct)) continue;
            Thought th = Thought.load(ct);
            if (th != null) entries.add(th);
        }
    }

    private static boolean sameOther(@Nullable UUID a, @Nullable UUID b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
