package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The OUTPOST: a remote, exclusive-but-unprotected working claim on an unclaimed chunk near (but
 * outside) a settlement's borders. There is no custom outpost block - an outpost is established by
 * planting a faction banner outside the border and confirming it in the banner's right-click
 * screen ("place then confirm"). Rod markers and worker drop-offs treat a working-claimed chunk as
 * workable territory and stockers haul from its containers, so a remote ore chunk becomes a real,
 * supplied extraction site with an exposed supply line. The weakness IS the design: the banner
 * sits on unprotected land, and breaking it (anyone, survival) drops the claim on the spot - that
 * is conquest v1. Unless a member dismantled it, the loss sounds a faction-wide alarm: on-site
 * toll, per-member toll for those out of earshot, red broadcast, and an ~1h ALERT status entry so
 * offline members still learn of it (stopgap until proper offline-event delivery,
 * OFFLINE_PLAY_PLAN.md). World-event wiring (break / right-click / stale sweep) lives in
 * {@code FactionBannerEvents}; this class holds the rules and the management/establish screen
 * payloads, parallel to {@link FactionBanner}. See MINER_PLAN.md / FACTION_BANNER_PLAN.md.
 *
 * <p>Rules: the cap is {@link #BASE_OUTPOSTS} (deliberately stingy - outpost slots are a strategic
 * scarcity lever) plus one per completed research, science OR culture, granting a numbered
 * bannerbound.outpost_slot_N flag - counted, so several researches stack without code changes.
 * Range is Chebyshev {@link #OUTPOST_RANGE_CHUNKS} chunks from the settlement's nearest
 * fully-claimed chunk. Outposts may sit NEAR a city-state but never on its claimed territory.
 * {@link #validateOutposts}, called from the once-a-second banner sweep (mirroring
 * {@link FactionBanner#validate}), catches removals that fire no break event (explosion, piston,
 * /setblock); only loaded banner positions are judged - an unloaded banner is presumed standing -
 * and legacy claims with no recorded banner pos are skipped.
 *
 * <p>Worker appointment: ore/material/crop chunks get a banner-owned marker (miner deposit /
 * digger deposit / crop field) created on appoint and removed on recall or claim loss, while
 * herder PENS carry the player's animal/keep config and are only ever bound/unbound, never
 * auto-created or destroyed. The appointed citizen becomes an outpost RESIDENT: its persistent
 * outpostSite anchor keeps patrol/idle/sleep on site and its town house is vacated; recall
 * reverses this so tryAutoAssignHome re-homes it in town. openScreen auto-vacates stale
 * appointments (worker dead or re-jobbed) so a post never points at a ghost. Citizen display
 * names embed a private-use-area job-glyph char that only the name-tag font can draw; cleanName
 * strips it before any plain-GUI text or it renders as a tofu box.
 */
@ApiStatus.Internal
public final class Outpost {
    private Outpost() {}

    public static final String FLAG_OUTPOST = "bannerbound.unlock.outpost";
    public static final int BASE_OUTPOSTS = 2;
    private static final int MAX_SLOT_FLAGS = 3;
    public static final int OUTPOST_RANGE_CHUNKS = 8;

    public static int maxOutposts(Settlement settlement) {
        int max = BASE_OUTPOSTS;
        for (int i = 1; i <= MAX_SLOT_FLAGS; i++) {
            if (ResearchManager.hasFlagEitherTree(settlement, "bannerbound.outpost_slot_" + i)) max++;
        }
        return max;
    }

    public static boolean withinRange(Settlement settlement, ChunkPos cp) {
        for (long packed : settlement.claimedChunks()) {
            ChunkPos own = new ChunkPos(packed);
            if (Math.max(Math.abs(own.x - cp.x), Math.abs(own.z - cp.z)) <= OUTPOST_RANGE_CHUNKS) {
                return true;
            }
        }
        return false;
    }

    public static String tryEstablish(ServerLevel sl, ServerPlayer player, BlockPos pos) {
        if (sl.dimension() != Level.OVERWORLD) return "bannerbound.outpost.overworld_only";
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) return "bannerbound.outpost.no_settlement";
        if (!ResearchManager.hasFlag(settlement, FLAG_OUTPOST)) return "bannerbound.outpost.not_researched";
        ChunkPos cp = new ChunkPos(pos);
        long packed = cp.toLong();
        if (settlement.claimedChunks().contains(packed)) return "bannerbound.outpost.inside_territory";
        if (com.bannerbound.core.citystate.CityStateData.get(sl.getServer().overworld())
                .getByChunk(packed) != null) return "bannerbound.outpost.in_city_state";
        if (data.getByChunk(packed) != null) return "bannerbound.outpost.chunk_taken";
        if (data.getByWorkingClaim(packed) != null) return "bannerbound.outpost.chunk_taken";
        if (settlement.workingClaims().size() >= maxOutposts(settlement)) return "bannerbound.outpost.limit";
        if (!withinRange(settlement, cp)) return "bannerbound.outpost.too_far";
        if (!data.claimWorkingChunk(settlement, cp)) return "bannerbound.outpost.chunk_taken";
        settlement.setOutpostBanner(packed, pos);
        data.setDirty();
        player.displayClientMessage(
            Component.translatable("bannerbound.outpost.established",
                settlement.workingClaims().size(), maxOutposts(settlement))
                .withStyle(ChatFormatting.GREEN), true);
        sl.playSound(null, pos, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.2f);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 16, 0.6, 0.6, 0.6, 0.0);
        return null;
    }

    public static void loseOutpost(ServerLevel sl, Settlement owner, BlockPos pos, boolean memberBreak) {
        MinecraftServer server = sl.getServer();
        SettlementData data = SettlementData.get(server.overworld());
        ChunkPos cp = new ChunkPos(pos);
        if (data.getByWorkingClaim(cp.toLong()) == null) return;
        data.unclaimWorkingChunk(owner, cp);
        com.bannerbound.core.api.world.BlockSelection marker = findWorkMarker(sl, owner, cp, null);
        if (marker != null) {
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(server.overworld())
                .unregister(marker.rodId());
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(server);
        }
        if (!memberBreak) {
            sl.playSound(null, pos, net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.6f);
            owner.addStatusEffect(new StatusEffect(
                UUID.randomUUID(), "bannerbound.status.outpost_lost",
                java.util.List.of(pos.getX() + ", " + pos.getZ()),
                StatusEffectIcon.ALERT, 0, 72_000));
            SettlementManager.broadcastStatusEffectsToMembers(server, owner);
            for (UUID memberId : owner.members()) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberId);
                if (member == null) continue;
                member.sendSystemMessage(Component.translatable("bannerbound.outpost.lost",
                        pos.getX(), pos.getZ())
                    .withStyle(ChatFormatting.RED));
                boolean heardOnSite = member.level() == sl
                    && member.blockPosition().closerThan(pos, 48);
                if (!heardOnSite) {
                    member.playNotifySound(net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                        net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.6f);
                }
            }
        }
    }

    public static void validateOutposts(ServerLevel sl, Settlement owner) {
        if (owner.workingClaims().isEmpty()) return;
        // Snapshot: loseOutpost mutates workingClaims.
        for (long packed : new java.util.ArrayList<>(owner.workingClaims())) {
            BlockPos pos = owner.outpostBannerPos(packed);
            if (pos == null || !sl.isLoaded(pos)) continue;
            if (!FactionBanner.isBanner(sl.getBlockState(pos))) {
                loseOutpost(sl, owner, pos, false);
            }
        }
    }

    public static void openScreen(ServerLevel sl, ServerPlayer sp, BlockPos bannerPos) {
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        ChunkPos cp = new ChunkPos(bannerPos);
        Settlement owner = data.getByWorkingClaim(cp.toLong());
        if (owner == null) return;
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(sl, cp);
        boolean ore = com.bannerbound.core.territory.BoulderLayout.isOreChunk(type);
        boolean material = com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(type);
        String resourceName = type != com.bannerbound.core.territory.ChunkResource.NONE
            ? type.name().toLowerCase(java.util.Locale.ROOT) : "";
        String expectedJob = expectedJob(type);
        String markerType = markerTypeFor(type);
        boolean storage = com.bannerbound.core.entity.MinerWorkGoal.findOutpostStorage(sl, cp, bannerPos) != null;
        int beds = countRoofedBeds(sl, cp, bannerPos.getY());

        com.bannerbound.core.api.world.BlockSelection marker =
            markerType == null ? null : findWorkMarker(sl, owner, cp, markerType);
        if (marker != null && !marker.targetsAllWorkers()) {
            java.util.UUID boundId = marker.assignedCitizenId();
            boolean dead = rosterName(owner, boundId) == null;
            boolean reJobbed = sl.getEntity(boundId) instanceof com.bannerbound.core.entity.CitizenEntity bc
                && !java.util.Objects.equals(expectedJob, bc.getJobType());
            if (dead || reJobbed) {
                com.bannerbound.core.api.world.BlockSelectionRegistry registry =
                    com.bannerbound.core.api.world.BlockSelectionRegistry.get(sl.getServer().overworld());
                String mt = marker.workstationType();
                if (com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE.equals(mt)
                        || com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE.equals(mt)
                        || (com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE.equals(mt)
                            && com.bannerbound.core.territory.MaterialDepositLayout
                                .isMaterialPacked(marker.seedItemId()))) {
                    registry.unregister(marker.rodId());
                    marker = null;
                } else {
                    marker = marker.withAssignedCitizen(null);
                    registry.register(marker);
                }
                com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            }
        }
        boolean markerOpen = marker != null && marker.targetsAllWorkers();
        String assignedName = "";
        if (marker != null && !markerOpen) {
            String n = rosterName(owner, marker.assignedCitizenId());
            assignedName = cleanName(n != null ? n : "Worker");
        }
        int veinReady = -1;
        int veinTotal = 0;
        int richness = ore ? com.bannerbound.core.territory.BoulderLayout.richness(sl.getSeed(), cp) : -1;
        if (ore) {
            Integer baseY = marker != null
                ? com.bannerbound.core.entity.MinerWorkGoal.mineBaseY(marker.seedItemId())
                : com.bannerbound.core.territory.BoulderLayout.locateBaseY(sl, cp, type).orElse(Integer.MIN_VALUE);
            if (baseY != Integer.MIN_VALUE) {
                veinReady = 0;
                net.minecraft.world.level.block.Block oreBlock =
                    com.bannerbound.core.territory.BoulderLayout.oreBlock(type).getBlock();
                for (com.bannerbound.core.territory.BoulderLayout.Spot s
                        : com.bannerbound.core.territory.BoulderLayout.spots(sl.getSeed(), cp, baseY)) {
                    if (!s.ore()) continue;
                    veinTotal++;
                    if (sl.getBlockState(s.pos()).is(oreBlock)) veinReady++;
                }
            }
        }
        if (material) {
            Integer baseY = marker != null
                ? com.bannerbound.core.territory.MaterialDepositLayout.materialBaseY(marker.seedItemId())
                : com.bannerbound.core.territory.MaterialDepositLayout.locateBaseY(sl, cp, type)
                    .orElse(Integer.MIN_VALUE);
            if (baseY != Integer.MIN_VALUE) {
                veinReady = 0;
                net.minecraft.world.level.block.Block sourceBlock =
                    com.bannerbound.core.territory.MaterialDepositLayout.sourceBlock(type).getBlock();
                for (com.bannerbound.core.territory.MaterialDepositLayout.Spot s
                        : com.bannerbound.core.territory.MaterialDepositLayout
                            .spots(sl.getSeed(), cp, baseY, type)) {
                    if (!s.source()) continue;
                    veinTotal++;
                    if (sl.getBlockState(s.pos()).is(sourceBlock)) veinReady++;
                }
            }
        }
        java.util.UUID appointedId = marker != null && !markerOpen ? marker.assignedCitizenId() : null;
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (expectedJob != null) {
            for (com.bannerbound.core.entity.CitizenEntity c : sl.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(
                        com.bannerbound.core.entity.CitizenEntity.class),
                    c -> c.isAlive() && owner.id().equals(c.getSettlementId())
                        && expectedJob.equals(c.getJobType()))) {
                if (c.getUUID().equals(appointedId)) continue;
                ids.add(c.getUUID().toString());
                names.add(cleanName(c.getCustomName() != null ? c.getCustomName().getString() : "Worker"));
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
            new com.bannerbound.core.network.OpenOutpostScreenPayload(bannerPos, resourceName, storage,
                beds, veinReady, veinTotal, richness, markerOpen, assignedName, ids, names,
                owner.workingClaims().size(), maxOutposts(owner), true));
    }

    public static void openEstablishScreen(ServerLevel sl, ServerPlayer sp, BlockPos bannerPos) {
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement mine = data.getByPlayer(sp.getUUID());
        if (mine == null) return;
        ChunkPos cp = new ChunkPos(bannerPos);
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(sl, cp);
        String resourceName = type != com.bannerbound.core.territory.ChunkResource.NONE
            ? type.name().toLowerCase(java.util.Locale.ROOT) : "";
        int richness = com.bannerbound.core.territory.BoulderLayout.isOreChunk(type)
            ? com.bannerbound.core.territory.BoulderLayout.richness(sl.getSeed(), cp) : -1;
        boolean storage = com.bannerbound.core.entity.MinerWorkGoal.findOutpostStorage(sl, cp, bannerPos) != null;
        int beds = countRoofedBeds(sl, cp, bannerPos.getY());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
            new com.bannerbound.core.network.OpenOutpostScreenPayload(bannerPos, resourceName, storage,
                beds, -1, 0, richness, false, "", java.util.List.of(), java.util.List.of(),
                mine.workingClaims().size(), maxOutposts(mine), false));
    }

    private static String cleanName(String name) {
        return name.replaceAll("[\\uE000-\\uF8FF]", "").trim();
    }

    private static boolean isLivestockChunk(com.bannerbound.core.territory.ChunkResource t) {
        return switch (t) {
            case HORSES, CATTLE, PIGS, CHICKENS, SHEEP -> true;
            default -> false;
        };
    }

    public static String expectedJob(com.bannerbound.core.territory.ChunkResource t) {
        if (com.bannerbound.core.territory.BoulderLayout.isOreChunk(t)) {
            return com.bannerbound.core.entity.MinerWorkGoal.JOB_TYPE_ID;
        }
        if (com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(t)) {
            return com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID;
        }
        if (isLivestockChunk(t)) return com.bannerbound.core.entity.HerderWorkGoal.JOB_TYPE_ID;
        if (com.bannerbound.core.territory.CropChunks.isCropChunk(t)) {
            return com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID;
        }
        return null;
    }

    private static String markerTypeFor(com.bannerbound.core.territory.ChunkResource t) {
        if (com.bannerbound.core.territory.BoulderLayout.isOreChunk(t)) {
            return com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE;
        }
        if (com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(t)) {
            return com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE;
        }
        if (isLivestockChunk(t)) return com.bannerbound.core.entity.HerderWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.territory.CropChunks.isCropChunk(t)) {
            return com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE;
        }
        return null;
    }

    private static com.bannerbound.core.api.world.BlockSelection findWorkMarker(
            ServerLevel sl, Settlement owner, ChunkPos cp, @org.jetbrains.annotations.Nullable String selectionType) {
        for (com.bannerbound.core.api.world.BlockSelection sel
                : com.bannerbound.core.api.world.BlockSelectionRegistry.get(sl.getServer().overworld())
                    .getForSettlement(owner.id())) {
            if (sel.kind() != com.bannerbound.core.api.world.BlockSelection.Kind.WORKSTATION) continue;
            String t = sel.workstationType();
            boolean matches = selectionType != null ? selectionType.equals(t)
                : com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE.equals(t)
                    || com.bannerbound.core.entity.HerderWorkGoal.SELECTION_TYPE.equals(t)
                    || com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE.equals(t)
                    || (com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE.equals(t)
                        && com.bannerbound.core.territory.MaterialDepositLayout
                            .isMaterialPacked(sel.seedItemId()));
            if (!matches) continue;
            if (com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE.equals(t)
                    && !com.bannerbound.core.territory.MaterialDepositLayout
                        .isMaterialPacked(sel.seedItemId())) {
                continue;
            }
            if (cp.equals(new ChunkPos(new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) return sel;
        }
        return null;
    }

    private static void bindOutpostResident(ServerLevel sl, Settlement owner, ChunkPos cp,
                                            BlockPos bannerPos, java.util.UUID citizenId) {
        if (citizenId == null) return;
        BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, bannerPos.getY(), cp.getMinBlockZ() + 8);
        if (sl.getEntity(citizenId) instanceof com.bannerbound.core.entity.CitizenEntity c) {
            c.setOutpostSite(anchor);
        }
        Home home = owner.getHomeFor(citizenId);
        if (home != null) {
            home.removeResident(citizenId);
            SettlementData.get(sl).setDirty();
        }
    }

    private static void unbindOutpostResident(ServerLevel sl, @org.jetbrains.annotations.Nullable java.util.UUID citizenId) {
        if (citizenId != null && sl.getEntity(citizenId) instanceof com.bannerbound.core.entity.CitizenEntity c) {
            c.setOutpostSite(null);
        }
    }

    private static String rosterName(Settlement owner, java.util.UUID citizenId) {
        for (com.bannerbound.core.api.settlement.Citizen c : owner.citizens()) {
            if (c.entityId().equals(citizenId)) return c.name();
        }
        return null;
    }

    @org.jetbrains.annotations.Nullable
    public static String setOutpostWorker(ServerLevel sl, Settlement owner, BlockPos bannerPos,
                                          @org.jetbrains.annotations.Nullable java.util.UUID citizenId,
                                          java.util.UUID actingPlayer) {
        ServerLevel overworld = sl.getServer().overworld();
        com.bannerbound.core.api.world.BlockSelectionRegistry registry =
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(overworld);
        ChunkPos cp = new ChunkPos(bannerPos);
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(sl, cp);
        String markerType = markerTypeFor(type);
        if (markerType == null) return "bannerbound.outpost.no_deposit";
        boolean ore = com.bannerbound.core.territory.BoulderLayout.isOreChunk(type);
        boolean crop = com.bannerbound.core.territory.CropChunks.isCropChunk(type);
        boolean material = com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(type);
        com.bannerbound.core.api.world.BlockSelection existing = findWorkMarker(sl, owner, cp, markerType);
        if (citizenId == null) {
            if (existing != null) {
                if (!existing.targetsAllWorkers()) unbindOutpostResident(overworld, existing.assignedCitizenId());
                if (ore || crop || material) {
                    registry.unregister(existing.rodId());
                } else {
                    registry.register(existing.withAssignedCitizen(null));
                }
                com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            }
            return null;
        }
        if (existing != null && !existing.targetsAllWorkers()) {
            java.util.UUID prev = existing.assignedCitizenId();
            if (prev != null && !prev.equals(citizenId)) unbindOutpostResident(overworld, prev);
        }
        bindOutpostResident(overworld, owner, cp, bannerPos, citizenId);
        if (existing != null) {
            registry.register(existing.withAssignedCitizen(citizenId));
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            return null;
        }
        if (crop) {
            int baseY = com.bannerbound.core.territory.BoulderLayout.groundSurfaceY(
                sl, cp.getMinBlockX() + 8, cp.getMinBlockZ() + 8);
            BlockPos lo = new BlockPos(cp.getMinBlockX() + 2, baseY - 5, cp.getMinBlockZ() + 2);
            BlockPos hi = new BlockPos(cp.getMinBlockX() + 14, baseY + 1, cp.getMinBlockZ() + 14);
            net.minecraft.resources.ResourceLocation seedKey = net.minecraft.core.registries.BuiltInRegistries
                .ITEM.getKey(com.bannerbound.core.territory.CropChunks.seedFor(type));
            com.bannerbound.core.api.world.BlockSelection marker =
                com.bannerbound.core.api.world.BlockSelection.workstation(
                    java.util.UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                    lo, hi, com.bannerbound.core.entity.FarmerWorkGoal.OUTPOST_SELECTION_TYPE,
                    actingPlayer, seedKey.toString())
                .withAssignedCitizen(citizenId);
            registry.register(marker);
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            return null;
        }
        if (material) {
            if (com.bannerbound.core.territory.MaterialDepositLayout.isStoneBoulder(type)
                    && !com.bannerbound.core.api.research.ResearchManager.hasFlag(
                        owner, com.bannerbound.core.social.WorkstationNames.FLAG_QUARRY)) {
                return "bannerbound.foremans_rod.not_researched";
            }
            int baseY = com.bannerbound.core.territory.MaterialDepositLayout.locateBaseY(sl, cp, type)
                .orElseGet(() -> com.bannerbound.core.territory.MaterialDepositLayout.dress(sl, cp));
            if (baseY == Integer.MIN_VALUE) return "bannerbound.outpost.no_deposit";
            BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, baseY, cp.getMinBlockZ() + 8);
            com.bannerbound.core.api.world.BlockSelection marker =
                com.bannerbound.core.api.world.BlockSelection.workstation(
                    java.util.UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                    anchor, anchor, com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE,
                    actingPlayer,
                    com.bannerbound.core.territory.MaterialDepositLayout.packDeposit(type, baseY))
                .withAssignedCitizen(citizenId);
            registry.register(marker);
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
            return null;
        }
        if (!ore) {
            return "bannerbound.outpost.no_pen";
        }
        if (com.bannerbound.core.territory.BoulderLayout.dropFor(type).isEmpty()) {
            return "bannerbound.outpost.no_deposit";
        }
        int baseY = com.bannerbound.core.territory.BoulderLayout.locateBaseY(sl, cp, type)
            .orElseGet(() -> com.bannerbound.core.territory.BoulderLayout.dress(sl, cp));
        BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, baseY + 1, cp.getMinBlockZ() + 8);
        com.bannerbound.core.api.world.BlockSelection marker =
            com.bannerbound.core.api.world.BlockSelection.workstation(
                java.util.UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                anchor, anchor, com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE,
                actingPlayer,
                com.bannerbound.core.entity.MinerWorkGoal.packMine(type, baseY))
            .withAssignedCitizen(citizenId);
        registry.register(marker);
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(sl.getServer());
        return null;
    }

    private static int countRoofedBeds(ServerLevel sl, ChunkPos cp, int aroundY) {
        int count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                for (int y = aroundY - 12; y <= aroundY + 12; y++) {
                    m.set(x, y, z);
                    BlockState bs = sl.getBlockState(m);
                    if (!(bs.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) continue;
                    if (bs.getValue(net.minecraft.world.level.block.BedBlock.PART)
                            != net.minecraft.world.level.block.state.properties.BedPart.HEAD) continue;
                    boolean roofed = false;
                    for (int dy = 1; dy <= 6 && !roofed; dy++) {
                        roofed = sl.getBlockState(m.offset(0, dy, 0)).blocksMotion();
                    }
                    if (roofed) count++;
                }
            }
        }
        return count;
    }
}
