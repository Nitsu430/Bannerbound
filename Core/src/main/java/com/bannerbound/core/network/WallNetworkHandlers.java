package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.territory.TerritoryService;
import com.bannerbound.core.api.walls.WallData;
import com.bannerbound.core.api.walls.WallLayoutEngine;
import com.bannerbound.core.api.walls.WallPiece;
import com.bannerbound.core.api.walls.WallPlan;
import com.bannerbound.core.api.walls.WallProgress;
import com.bannerbound.core.api.walls.WallService;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server handlers for the wall-preview screen's payload family - thin glue over
 * {@link WallService} (the same verbs the {@code /bannerbound walls} commands use). Every
 * action runs on the main thread (context.enqueueWork) and replies with a refreshed
 * OpenWallPreview so the open screen re-renders. All wall feedback goes through the in-screen
 * status banner, never chat (chat bloat, playtest 2026-06-12); it falls back to the client's
 * action bar when no wall screen is open.
 *
 * <p>The save path never trusts the client: it rebuilds the WallDesign with a server-owned id
 * (a slug of the player name + kind, so re-saving the same name overwrites and a new name makes
 * a new library entry), then re-validates geometry, that every palette block is researched by
 * the settlement, and that a GATE design has at least one pathable opening (fence gate / door
 * tag). A draft save is a silent autosave of the working copy - persisted in world data, no
 * validation beyond geometry, never activated, never entering the layout resolver - so closing
 * the designer never loses work; drafts ride the OpenWallDesigner payload and override the
 * active set in the editor.
 *
 * <p>Preview payload: every design the pieces reference rides along deduped in insertion order,
 * and each PieceLite carries its index into that list so per-piece variants render their real
 * blocks client-side. planCurrent = the committed plan still matches the previewed layout;
 * false means the built wall is an older design and the "% built" headline must say so.
 */
@ApiStatus.Internal
public final class WallNetworkHandlers {

    private WallNetworkHandlers() {
    }

    private static void status(ServerPlayer player, String message, boolean error) {
        PacketDistributor.sendToPlayer(player, new WallScreenPayloads.WallStatus(message, error));
    }

    public static void handleRequestWallPreview(WallScreenPayloads.RequestWallPreview payload,
                                                IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null || !settlement.hasTownHall()) {
                status(player, "You need a settlement with a town hall to plan walls.", true);
                return;
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleToggleWallGate(WallScreenPayloads.ToggleWallGate payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String error = WallService.toggleGate(player.serverLevel(), settlement, payload.anchor());
            if (error != null) {
                status(player, error, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handlePreviewWallGhosts(WallScreenPayloads.PreviewWallGhosts payload,
                                               IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            if (payload.show()) {
                com.bannerbound.core.api.walls.WallSync.sendPlanPreview(player, settlement,
                    WallService.computeLayout(player.serverLevel(), settlement).plan());
            } else {
                com.bannerbound.core.api.walls.WallSync.syncPlayer(player, settlement);
            }
        });
    }

    public static void handleRefineWallTop(WallScreenPayloads.RefineWallTop payload,
                                           IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String error = WallService.refineTop(player.serverLevel(), settlement,
                payload.anchor(), payload.delta());
            if (error != null) {
                status(player, error, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleCycleWallVariant(WallScreenPayloads.CycleWallVariant payload,
                                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String result = WallService.cycleVariant(player.serverLevel(), settlement, payload.anchor());
            if (result.startsWith("ok:")) {
                status(player, "Variant: " + result.substring(3), false);
            } else {
                status(player, result, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleToggleWallFoundation(WallScreenPayloads.ToggleWallFoundation payload,
                                                  IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String result = WallService.toggleFoundation(player.serverLevel(), settlement, payload.anchor());
            if (result.startsWith("ok:")) {
                status(player, "Foundation continuation: " + result.substring(3).toUpperCase(java.util.Locale.ROOT), false);
            } else {
                status(player, result, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleConstructWalls(WallScreenPayloads.ConstructWalls payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            WallService.ConstructResult outcome = WallService.construct(player.serverLevel(), settlement);
            if (!outcome.ok()) {
                status(player, outcome.error(), true);
            } else {
                status(player, "Plan committed — ghosts mark the line; builders and you can "
                    + "start building.", false);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleCancelWallPlan(WallScreenPayloads.CancelWallPlan payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            int leftovers = WallService.cancel(player.serverLevel(), settlement);
            if (leftovers < 0) {
                status(player, "No wall plan to cancel.", true);
            } else if (leftovers > 0) {
                status(player, "Plan cancelled — " + leftovers
                    + " standing wall blocks remembered for demolition.", false);
            } else {
                status(player, "Plan cancelled.", false);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleRequestWallDesigner(WallScreenPayloads.RequestWallDesigner payload,
                                                 IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            sendDesigner(player, settlement, player.serverLevel());
        });
    }

    private static void sendDesigner(ServerPlayer player, Settlement settlement, ServerLevel level) {
        var designs = WallService.designs(level, settlement);
        it.unimi.dsi.fastutil.ints.IntArrayList known = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList owned = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) continue;
            if (blockItem.getBlock().defaultBlockState().isAir()) continue;
            if (!com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(
                    settlement, null, new net.minecraft.world.item.ItemStack(item))) {
                continue;
            }
            known.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(item));
            owned.add(com.bannerbound.core.stockpile.StockpileService.count(level, settlement, item)
                + player.getInventory().countItem(item));
        }
        WallData walls = WallData.get(level);
        List<com.bannerbound.core.api.walls.WallDesign> drafts = new ArrayList<>();
        for (com.bannerbound.core.api.walls.WallDesign.Kind kind
                : com.bannerbound.core.api.walls.WallDesign.Kind.values()) {
            com.bannerbound.core.api.walls.WallDesign draft = walls.draft(settlement.id(), kind);
            if (draft != null) drafts.add(draft);
        }
        PacketDistributor.sendToPlayer(player, new WallScreenPayloads.OpenWallDesigner(
            List.of(designs.wall(), designs.corner(), designs.gate()),
            known.toIntArray(), owned.toIntArray(), drafts,
            new ArrayList<>(walls.library(settlement.id()))));
    }

    public static void handleSaveWallDesign(WallScreenPayloads.SaveWallDesign payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            ServerLevel level = player.serverLevel();
            com.bannerbound.core.api.walls.WallDesign incoming = payload.design();

            String kindName = incoming.kind().name().toLowerCase(java.util.Locale.ROOT);
            String serverId = payload.draft() ? "draft_" + kindName
                : "u_" + slug(incoming.name()) + "_" + kindName;
            com.bannerbound.core.api.walls.WallDesign design;
            try {
                design = new com.bannerbound.core.api.walls.WallDesign(serverId, incoming.name(),
                    incoming.kind(), incoming.length(), incoming.depth(), incoming.height(),
                    incoming.palette(), incoming.voxelsCopy(), incoming.foundation());
            } catch (IllegalArgumentException e) {
                if (!payload.draft()) status(player, "Invalid design: " + e.getMessage(), true);
                return;
            }
            WallData walls = WallData.get(level);
            if (payload.draft()) {
                walls.setDraft(settlement.id(), design);
                return;
            }
            if (design.blockCount() == 0) {
                status(player, "The " + kindName + " design is empty — place some blocks.", true);
                return;
            }
            for (net.minecraft.world.level.block.state.BlockState state : design.palette()) {
                if (!com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(settlement,
                        null, new net.minecraft.world.item.ItemStack(state.getBlock().asItem()))) {
                    status(player, "Your settlement doesn't know "
                        + state.getBlock().getName().getString() + " yet.", true);
                    return;
                }
            }
            if (design.kind() == com.bannerbound.core.api.walls.WallDesign.Kind.GATE
                && !hasPathableOpening(design)) {
                status(player,
                    "A gate design needs at least one fence gate or door so citizens can pass.",
                    true);
                return;
            }
            boolean exists = walls.libraryDesign(settlement.id(), design.id()) != null;
            if (!exists && walls.library(settlement.id()).size() >= 48) {
                status(player, "Design library is full (48) — Shift+click entries to delete some.", true);
                return;
            }
            walls.upsertDesign(settlement.id(), design);
            walls.setActiveId(settlement.id(), design.kind(), design.id());
            walls.setDraft(settlement.id(), design);
            status(player, "Designs saved & set active — re-run Construct to apply.", false);
            sendDesigner(player, settlement, level);
        });
    }

    private static String slug(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "design";
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    public static void handleDeleteWallDesign(WallScreenPayloads.DeleteWallDesign payload,
                                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            ServerLevel level = player.serverLevel();
            WallData walls = WallData.get(level);
            if (walls.libraryDesign(settlement.id(), payload.designId()) == null) {
                status(player, "That design no longer exists.", true);
            } else {
                walls.removeDesign(settlement.id(), payload.designId());
                status(player, "Design deleted.", false);
            }
            sendDesigner(player, settlement, level);
        });
    }

    private static boolean hasPathableOpening(com.bannerbound.core.api.walls.WallDesign design) {
        for (int l = 0; l < design.length(); l++) {
            for (int d = 0; d < design.depth(); d++) {
                for (int h = 0; h < design.height(); h++) {
                    net.minecraft.world.level.block.state.BlockState state = design.stateAt(l, d, h);
                    if (state != null && (state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                        || state.is(net.minecraft.tags.BlockTags.DOORS))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void sendPreview(ServerPlayer player, Settlement settlement) {
        sendPreview(player, settlement, false);
    }

    public static void sendPreview(ServerPlayer player, Settlement settlement, boolean openRefine) {
        ServerLevel overworld = player.getServer().overworld();
        ServerLevel level = player.serverLevel();
        OpenExpandTerritoryScreenPayload base =
            TerritoryService.buildScreenPayload(overworld, settlement, player);
        WallLayoutEngine.LayoutResult result = WallService.computeLayout(level, settlement);
        java.util.function.Function<String, com.bannerbound.core.api.walls.WallDesign> resolver =
            WallService.resolver(level, settlement);
        java.util.Map<String, Integer> designIndexById = new java.util.LinkedHashMap<>();
        List<com.bannerbound.core.api.walls.WallDesign> referencedDesigns = new ArrayList<>();
        List<WallScreenPayloads.PieceLite> pieces = new ArrayList<>(result.plan().pieces().size());
        for (WallPiece piece : result.plan().pieces()) {
            com.bannerbound.core.api.walls.WallDesign design = resolver.apply(piece.designId());
            int designIndex = -1;
            if (design != null) {
                Integer existing = designIndexById.get(piece.designId());
                if (existing == null) {
                    existing = referencedDesigns.size();
                    designIndexById.put(piece.designId(), existing);
                    referencedDesigns.add(design);
                }
                designIndex = existing;
            }
            pieces.add(new WallScreenPayloads.PieceLite(piece.startX(), piece.startZ(),
                piece.length(), piece.depth(), piece.outward().get2DDataValue(),
                piece.kind().ordinal(), piece.waterGap(),
                piece.baseY(), design == null ? 3 : design.height(),
                piece.minGround(), piece.maxGround(),
                designIndex, piece.noFoundation()));
        }
        WallPlan committed = WallData.get(level).plan(settlement.id());
        int completeness = 0;
        if (committed != null) {
            completeness = WallProgress.scan(level, committed,
                WallService.resolver(level, settlement)).percent();
        }
        boolean planCurrent = committed != null
            && samePieces(committed.pieces(), result.plan().pieces());
        var activeSet = WallService.designs(level, settlement);
        PacketDistributor.sendToPlayer(player,
            new WallScreenPayloads.OpenWallPreview(base, pieces, committed != null, completeness,
                activeSet.gate().length(), openRefine, planCurrent, referencedDesigns));
    }

    private static boolean samePieces(List<WallPiece> a, List<WallPiece> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WallPiece p = a.get(i);
            WallPiece q = b.get(i);
            if (p.startX() != q.startX() || p.startZ() != q.startZ()
                || p.kind() != q.kind() || !p.designId().equals(q.designId())
                || p.length() != q.length() || p.depth() != q.depth()
                || p.baseY() != q.baseY() || p.outward() != q.outward()
                || p.waterGap() != q.waterGap()) {
                return false;
            }
        }
        return true;
    }

    private static Settlement settlementOf(ServerPlayer player) {
        return SettlementData.get(player.getServer().overworld()).getByPlayer(player.getUUID());
    }
}
