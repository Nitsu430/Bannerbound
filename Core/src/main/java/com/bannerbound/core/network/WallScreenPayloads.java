package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The wall-preview screen's payload family (WALLS_PLAN.md Phase 3). OpenWallPreview composes the
 * territory screen's base payload (camera/slabs/claims reuse) with a compact PieceLite list for
 * the border polyline + gate slots; OpenWallDesigner carries the active design set, the
 * researched placeable block-item ids with parallel owned counts, and any autosaved drafts that
 * override the active set in the editor; WallStatus is the in-screen status line that replaces
 * chat feedback (chat bloat, playtest 2026-06-12).
 *
 * <p>All client-bound payloads MUST be registered in both dist branches of
 * {@link BannerboundNetwork} (standing rule). Designs travel on the wire as global block-state
 * ids (Block.getId / Block.stateById) so no registry lookups are needed - see writeDesign /
 * readDesign.
 *
 * <p>PieceLite has two anchor keys: anchor() is the gate-slot identity (x, 0, z) - gates replace
 * segments at the same slot on purpose; refineAnchor() is KIND-AWARE (x, kind+1, z) because a
 * corner and a segment can share a start column and a position-only key raised both (playtest
 * 2026-06-12). PieceLite.designIndex is an index into OpenWallPreview.designs() (-1 =
 * unresolved) and noFoundation is the per-piece continuation-suppression refinement.
 */
@ApiStatus.Internal
public final class WallScreenPayloads {

    private WallScreenPayloads() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, path);
    }

    public record PieceLite(int startX, int startZ, int length, int depth,
                            int outward2d, int kindOrdinal, boolean waterGap,
                            int baseY, int designHeight, int minGround, int maxGround,
                            int designIndex, boolean noFoundation) {
        public int topY() {
            return baseY + designHeight - 1;
        }

        public long anchor() {
            return net.minecraft.core.BlockPos.asLong(startX, 0, startZ);
        }

        public long refineAnchor() {
            return net.minecraft.core.BlockPos.asLong(startX, kindOrdinal + 1, startZ);
        }
    }

    public record RefineWallTop(long anchor, int delta) implements CustomPacketPayload {
        public static final Type<RefineWallTop> TYPE = new Type<>(id("refine_wall_top"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RefineWallTop> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeLong(p.anchor());
                buf.writeVarInt(p.delta());
            }, buf -> new RefineWallTop(buf.readLong(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestWallPreview() implements CustomPacketPayload {
        public static final Type<RequestWallPreview> TYPE = new Type<>(id("request_wall_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestWallPreview> STREAM_CODEC =
            StreamCodec.unit(new RequestWallPreview());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CycleWallVariant(long anchor) implements CustomPacketPayload {
        public static final Type<CycleWallVariant> TYPE = new Type<>(id("cycle_wall_variant"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CycleWallVariant> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeLong(p.anchor()),
                buf -> new CycleWallVariant(buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ToggleWallFoundation(long anchor) implements CustomPacketPayload {
        public static final Type<ToggleWallFoundation> TYPE = new Type<>(id("toggle_wall_foundation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleWallFoundation> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeLong(p.anchor()),
                buf -> new ToggleWallFoundation(buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ToggleWallGate(long anchor) implements CustomPacketPayload {
        public static final Type<ToggleWallGate> TYPE = new Type<>(id("toggle_wall_gate"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToggleWallGate> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeLong(p.anchor()),
                buf -> new ToggleWallGate(buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PreviewWallGhosts(boolean show) implements CustomPacketPayload {
        public static final Type<PreviewWallGhosts> TYPE = new Type<>(id("preview_wall_ghosts"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PreviewWallGhosts> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeBoolean(p.show()),
                buf -> new PreviewWallGhosts(buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConstructWalls() implements CustomPacketPayload {
        public static final Type<ConstructWalls> TYPE = new Type<>(id("construct_walls"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConstructWalls> STREAM_CODEC =
            StreamCodec.unit(new ConstructWalls());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CancelWallPlan() implements CustomPacketPayload {
        public static final Type<CancelWallPlan> TYPE = new Type<>(id("cancel_wall_plan"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CancelWallPlan> STREAM_CODEC =
            StreamCodec.unit(new CancelWallPlan());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void writeDesign(RegistryFriendlyByteBuf buf,
                                   com.bannerbound.core.api.walls.WallDesign design) {
        buf.writeUtf(design.id());
        buf.writeUtf(design.name());
        buf.writeByte(design.kind().ordinal());
        buf.writeVarInt(design.length());
        buf.writeVarInt(design.depth());
        buf.writeVarInt(design.height());
        java.util.List<net.minecraft.world.level.block.state.BlockState> palette = design.palette();
        buf.writeVarInt(palette.size());
        for (net.minecraft.world.level.block.state.BlockState state : palette) {
            buf.writeVarInt(net.minecraft.world.level.block.Block.getId(state));
        }
        buf.writeByteArray(design.voxelsCopy());
        buf.writeVarInt(net.minecraft.world.level.block.Block.getId(design.foundation()));
    }

    public static com.bannerbound.core.api.walls.WallDesign readDesign(RegistryFriendlyByteBuf buf) {
        String id = buf.readUtf();
        String name = buf.readUtf();
        com.bannerbound.core.api.walls.WallDesign.Kind kind =
            com.bannerbound.core.api.walls.WallDesign.Kind.values()[buf.readByte()];
        int length = buf.readVarInt();
        int depth = buf.readVarInt();
        int height = buf.readVarInt();
        int paletteSize = buf.readVarInt();
        java.util.List<net.minecraft.world.level.block.state.BlockState> palette =
            new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            palette.add(net.minecraft.world.level.block.Block.stateById(buf.readVarInt()));
        }
        byte[] voxels = buf.readByteArray();
        net.minecraft.world.level.block.state.BlockState foundation =
            net.minecraft.world.level.block.Block.stateById(buf.readVarInt());
        return new com.bannerbound.core.api.walls.WallDesign(
            id, name, kind, length, depth, height, palette, voxels, foundation);
    }

    public record RequestWallDesigner() implements CustomPacketPayload {
        public static final Type<RequestWallDesigner> TYPE = new Type<>(id("request_wall_designer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestWallDesigner> STREAM_CODEC =
            StreamCodec.unit(new RequestWallDesigner());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenWallDesigner(List<com.bannerbound.core.api.walls.WallDesign> activeSet,
                                   int[] knownBlockItemIds,
                                   int[] ownedCounts,
                                   List<com.bannerbound.core.api.walls.WallDesign> drafts,
                                   List<com.bannerbound.core.api.walls.WallDesign> library)
        implements CustomPacketPayload {
        public static final Type<OpenWallDesigner> TYPE = new Type<>(id("open_wall_designer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenWallDesigner> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarInt(p.activeSet().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.activeSet()) {
                    writeDesign(buf, design);
                }
                buf.writeVarIntArray(p.knownBlockItemIds());
                buf.writeVarIntArray(p.ownedCounts());
                buf.writeVarInt(p.drafts().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.drafts()) {
                    writeDesign(buf, design);
                }
                buf.writeVarInt(p.library().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.library()) {
                    writeDesign(buf, design);
                }
            }, buf -> {
                int n = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> designs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    designs.add(readDesign(buf));
                }
                int[] known = buf.readVarIntArray();
                int[] owned = buf.readVarIntArray();
                int d = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> drafts = new ArrayList<>(d);
                for (int i = 0; i < d; i++) {
                    drafts.add(readDesign(buf));
                }
                int l = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> library = new ArrayList<>(l);
                for (int i = 0; i < l; i++) {
                    library.add(readDesign(buf));
                }
                return new OpenWallDesigner(designs, known, owned, drafts, library);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record DeleteWallDesign(String designId) implements CustomPacketPayload {
        public static final Type<DeleteWallDesign> TYPE = new Type<>(id("delete_wall_design"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DeleteWallDesign> STREAM_CODEC =
            StreamCodec.of((buf, p) -> buf.writeUtf(p.designId()),
                buf -> new DeleteWallDesign(buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SaveWallDesign(com.bannerbound.core.api.walls.WallDesign design, boolean draft)
        implements CustomPacketPayload {
        public static final Type<SaveWallDesign> TYPE = new Type<>(id("save_wall_design"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveWallDesign> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                writeDesign(buf, p.design());
                buf.writeBoolean(p.draft());
            }, buf -> new SaveWallDesign(readDesign(buf), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record WallStatus(String message, boolean error) implements CustomPacketPayload {
        public static final Type<WallStatus> TYPE = new Type<>(id("wall_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WallStatus> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeUtf(p.message());
                buf.writeBoolean(p.error());
            }, buf -> new WallStatus(buf.readUtf(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenWallPreview(OpenExpandTerritoryScreenPayload base, List<PieceLite> pieces,
                                  boolean hasPlan, int completenessPercent,
                                  int gateLength, boolean openRefine, boolean planCurrent,
                                  List<com.bannerbound.core.api.walls.WallDesign> designs)
        implements CustomPacketPayload {
        public static final Type<OpenWallPreview> TYPE = new Type<>(id("open_wall_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenWallPreview> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                OpenExpandTerritoryScreenPayload.STREAM_CODEC.encode(buf, p.base());
                buf.writeVarInt(p.pieces().size());
                for (PieceLite piece : p.pieces()) {
                    buf.writeVarInt(piece.startX());
                    buf.writeVarInt(piece.startZ());
                    buf.writeVarInt(piece.length());
                    buf.writeByte(piece.depth());
                    buf.writeByte(piece.outward2d());
                    buf.writeByte(piece.kindOrdinal());
                    buf.writeBoolean(piece.waterGap());
                    buf.writeVarInt(piece.baseY());
                    buf.writeVarInt(piece.designHeight());
                    buf.writeVarInt(piece.minGround());
                    buf.writeVarInt(piece.maxGround());
                    buf.writeVarInt(piece.designIndex() + 1); // -1 -> 0 (varint-safe)
                    buf.writeBoolean(piece.noFoundation());
                }
                buf.writeBoolean(p.hasPlan());
                buf.writeVarInt(p.completenessPercent());
                buf.writeVarInt(p.gateLength());
                buf.writeBoolean(p.openRefine());
                buf.writeBoolean(p.planCurrent());
                buf.writeVarInt(p.designs().size());
                for (com.bannerbound.core.api.walls.WallDesign design : p.designs()) {
                    writeDesign(buf, design);
                }
            }, buf -> {
                OpenExpandTerritoryScreenPayload base =
                    OpenExpandTerritoryScreenPayload.STREAM_CODEC.decode(buf);
                int n = buf.readVarInt();
                List<PieceLite> pieces = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    pieces.add(new PieceLite(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readByte(), buf.readByte(), buf.readByte(), buf.readBoolean(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt() - 1, buf.readBoolean()));
                }
                boolean hasPlan = buf.readBoolean();
                int completeness = buf.readVarInt();
                int gateLength = buf.readVarInt();
                boolean openRefine = buf.readBoolean();
                boolean planCurrent = buf.readBoolean();
                int designCount = buf.readVarInt();
                List<com.bannerbound.core.api.walls.WallDesign> designs = new ArrayList<>(designCount);
                for (int i = 0; i < designCount; i++) {
                    designs.add(readDesign(buf));
                }
                return new OpenWallPreview(base, pieces, hasPlan, completeness,
                    gateLength, openRefine, planCurrent, designs);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
