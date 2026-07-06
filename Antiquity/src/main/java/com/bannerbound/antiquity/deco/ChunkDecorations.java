package com.bannerbound.antiquity.deco;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * All face decorations within one chunk: {@code BlockPos -> (Direction -> FaceDeco)}. Used both as
 * the server-side chunk {@linkplain net.neoforged.neoforge.attachment.AttachmentType attachment}
 * value and as the client-side per-chunk cache. Persisted via {@link #CODEC}, which serializes as a
 * flat list of {@link FaceDecoEntry} (the chunk save format). Mutable; empty faces are never stored:
 * set() with a null/empty deco clears the face and prunes the position, so isEmpty() stays accurate.
 * appealAt() sums per-face plaster/trim appeal at one position; anyPlaster() drives the blast/break
 * "sturdier" effects; forEachInYRange() (block Y within [minY, maxY)) feeds per-section rendering.
 */
public class ChunkDecorations {
    private final Map<BlockPos, EnumMap<Direction, FaceDeco>> faces = new HashMap<>();

    public static final Codec<ChunkDecorations> CODEC =
        FaceDecoEntry.CODEC.listOf().xmap(ChunkDecorations::fromEntries, ChunkDecorations::toEntries);

    public FaceDeco get(BlockPos pos, Direction dir) {
        EnumMap<Direction, FaceDeco> m = faces.get(pos);
        FaceDeco d = m == null ? null : m.get(dir);
        return d == null ? FaceDeco.EMPTY : d;
    }

    public boolean set(BlockPos pos, Direction dir, FaceDeco deco) {
        BlockPos key = pos.immutable();
        if (deco == null || deco.isEmpty()) {
            EnumMap<Direction, FaceDeco> m = faces.get(key);
            if (m == null) {
                return false;
            }
            boolean changed = m.remove(dir) != null;
            if (m.isEmpty()) {
                faces.remove(key);
            }
            return changed;
        }
        EnumMap<Direction, FaceDeco> m = faces.computeIfAbsent(key, k -> new EnumMap<>(Direction.class));
        return !deco.equals(m.put(dir, deco));
    }

    public boolean isEmpty() {
        return faces.isEmpty();
    }

    public EnumMap<Direction, FaceDeco> removeAll(BlockPos pos) {
        return faces.remove(pos);
    }

    public double appealAt(BlockPos pos, double plasterEach, double trimEach) {
        EnumMap<Direction, FaceDeco> m = faces.get(pos);
        if (m == null) {
            return 0.0;
        }
        double s = 0.0;
        for (FaceDeco d : m.values()) {
            if (d.plaster()) {
                s += plasterEach;
            }
            if (d.hasTrim()) {
                s += trimEach;
            }
        }
        return s;
    }

    public boolean anyPlaster(BlockPos pos) {
        EnumMap<Direction, FaceDeco> m = faces.get(pos);
        if (m == null) {
            return false;
        }
        for (FaceDeco d : m.values()) {
            if (d.plaster()) {
                return true;
            }
        }
        return false;
    }

    public void forEach(Consumer<FaceDecoEntry> out) {
        faces.forEach((pos, m) -> m.forEach((dir, deco) -> out.accept(new FaceDecoEntry(pos, dir, deco))));
    }

    public void forEachInYRange(int minY, int maxY, Consumer<FaceDecoEntry> out) {
        faces.forEach((pos, m) -> {
            if (pos.getY() >= minY && pos.getY() < maxY) {
                m.forEach((dir, deco) -> out.accept(new FaceDecoEntry(pos, dir, deco)));
            }
        });
    }

    public List<FaceDecoEntry> toEntries() {
        List<FaceDecoEntry> list = new ArrayList<>();
        forEach(list::add);
        return list;
    }

    public static ChunkDecorations fromEntries(List<FaceDecoEntry> entries) {
        ChunkDecorations cd = new ChunkDecorations();
        for (FaceDecoEntry e : entries) {
            cd.set(e.pos(), e.dir(), e.deco());
        }
        return cd;
    }
}
