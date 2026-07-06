package com.bannerbound.core.social;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * One active per-citizen thought. Immutable: expireGameTime is set on creation/refresh and never
 * changes, so a "tick" is just comparing the world's current game time against this stored absolute.
 * That keeps the tick loop trivial (no per-thought decrement) and makes save/load idempotent -- a
 * thought saved at gameTime=1000 and loaded at gameTime=2000 still expires at the same absolute tick.
 * Infinite thoughts carry expireGameTime == totalDurationTicks == -1; totalDurationTicks is kept so
 * the client can draw a "remaining / total" bar without extra bookkeeping.
 *
 * <p>Two flavours: solo (otherUuid == null) is one instance per kind per citizen and adding refreshes
 * it; per-partner (otherUuid != null) is one instance per (kind, partner) pair so several coexist.
 * startGameTime is the absolute create/refresh time that escalating kinds use to age the grievance
 * (effectiveModifier ramps modifier toward the kind's floor). savedPartnerName snapshots the partner's
 * bare-string name at creation for cases where the partner may no longer be UUID-resolvable -- death
 * thoughts capture the dead citizen's name so the label still reads after the entity is gone; it is
 * null for partners expected to stay resolvable (conversation outcomes, child-born), which resolve
 * by UUID at screen-build time.
 *
 * <p>NBT keys: KID=kind id, M=modifier, E=expire, D=total, S=start, O=partner UUID, N=savedName.
 * load() is tolerant -- an unresolvable kind returns null so the caller drops the entry; it reads the
 * id key "KID" and falls back to the legacy ordinal key "K" so pre-registry saves still load, and a
 * missing "S" is treated as freshly created (0) so an old grievance restarts its ramp instead of
 * snapping to the floor.
 */
public record Thought(
    ThoughtType kind,
    int modifier,
    long expireGameTime,
    int totalDurationTicks,
    long startGameTime,
    @Nullable UUID otherUuid,
    @Nullable String savedPartnerName
) {
    public static final long INFINITE_EXPIRY = -1L;

    public boolean isExpired(long now) {
        return expireGameTime != INFINITE_EXPIRY && now >= expireGameTime;
    }

    public long remainingTicks(long now) {
        if (expireGameTime == INFINITE_EXPIRY) return -1L;
        return Math.max(0L, expireGameTime - now);
    }

    public int effectiveModifier(long now) {
        if (!kind.escalates()) return modifier;
        long age = Math.max(0L, now - startGameTime);
        return kind.modifierAt(age);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("KID", kind.id().toString());
        tag.putInt("M", modifier);
        tag.putLong("E", expireGameTime);
        tag.putInt("D", totalDurationTicks);
        tag.putLong("S", startGameTime);
        if (otherUuid != null) {
            tag.put("O", NbtUtils.createUUID(otherUuid));
        }
        if (savedPartnerName != null) {
            tag.putString("N", savedPartnerName);
        }
        return tag;
    }

    @Nullable
    public static Thought load(CompoundTag tag) {
        ThoughtType kind = tag.contains("KID") ? ThoughtTypes.byId(tag.getString("KID")) : null;
        if (kind == null && tag.contains("K")) {
            kind = ThoughtKind.fromOrdinal(tag.getInt("K"));
        }
        if (kind == null) return null;
        int modifier = tag.contains("M") ? tag.getInt("M") : kind.modifier();
        long expire = tag.contains("E") ? tag.getLong("E") : INFINITE_EXPIRY;
        int total = tag.contains("D") ? tag.getInt("D") : ThoughtKind.INFINITE_DURATION;
        long start = tag.contains("S") ? tag.getLong("S") : 0L;
        UUID other = tag.contains("O") ? NbtUtils.loadUUID(tag.get("O")) : null;
        String savedName = tag.contains("N") ? tag.getString("N") : null;
        return new Thought(kind, modifier, expire, total, start, other, savedName);
    }
}
