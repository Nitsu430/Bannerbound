package com.bannerbound.core.item;

import com.bannerbound.core.api.farmer.AwaitingSeedRegistry;

import java.util.List;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.network.OpenForemansRodPickerPayload;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.world.SelectionBroadcaster;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Foreman's Rod: a player-held tool that authors block-region "jobs" ({@link BlockSelection})
 * for a chosen worker type. The ItemStack stores only the current worker type plus the in-progress
 * A/B corners between clicks; every committed selection gets its own UUID in the
 * {@link BlockSelectionRegistry}, and one rod can author many. Every rejection sends a red message
 * and never mutates the registry; every success re-broadcasts via {@link SelectionBroadcaster}.
 *
 * Worker types are the rod's selection-type strings, distinct from the citizen job ids (e.g.
 * "diggers_slab"/"farmers_granary"):
 *  - A->B box drawers (isBoxType): digger, farmer, forester_farm. Two clicks set corners A then B;
 *    committing B UNIONS the box into any overlapping same-type box field (the oldest overlapped
 *    field survives and grows, keeping its id/crop/worker) rather than being rejected; else a fresh
 *    field. Adjacency-only growth (touching, no overlap) goes through the explicit sneak "expand".
 *  - Point markers (single click): herder (fenced pen), miner (ore-chunk boulder), guard (post),
 *    and a digger click inside a stone/clay/sand deposit chunk. Deposit/miner marks pack resource +
 *    base height into the selection's seed so DiggerWorkGoal/MinerWorkGoal switch to the infinite
 *    non-destructive cycle. Locating a boulder/deposit may DRESS one into a pre-feature chunk -- the
 *    one terrain edit, made once, on the player's own claim at the player's request.
 * Ordered types (isOrderedType = all six) bind a selection to one citizen or "all of that type" via
 * the rod's target; the shift-in-air "clear bound citizen back to all" path is currently disabled
 * (TODO block in use()) - shift-in-air always opens the picker. FARMER boxes must contain at least
 * one farmland or grass block or the commit is rejected ("no_farmland").
 *
 * Interaction: use() handles shift-in-air (picker); useOn() handles clicking a
 * block (A/B cycle, point marks, mid-draw sneak = expand nearest same-type field, sneak inside a
 * field = adopt its target onto the rod or open the farmer-field GUI). Shift-LEFT-click removal
 * lives in ForemansRodLeftClick. Shift-right-click on a digger CITIZEN (bind rod to that one) lives
 * in CitizenEntity.mobInteract, NOT here -- entity.interact() consumes the click before
 * interactLivingEntity would ever run.
 *
 * Commit guards: settlement membership, MAX_SELECTION_VOLUME, and territory (every touched chunk
 * own-claimed). Extensive Quarries research lets DIGGER boxes reach onto NOBODY's land within
 * QUARRY_REACH_CHUNKS -- never a rival's. Outpost working-claims are banner-managed, so rod
 * point-marks bail out there. GUARD_TYPE must equal GuardWorkGoal.SELECTION_TYPE (kept in sync by
 * referencing it directly).
 */
public class ForemansRodItem extends Item {
    public static final int MAX_SELECTION_VOLUME = 32_768;
    public static final String DIGGER_TYPE = "digger";
    public static final String FARMER_TYPE = "farmer";
    public static final String HERDER_TYPE = "herder";
    public static final String MINER_TYPE = "miner";
    public static final String FORESTER_FARM_TYPE = "forester_farm";
    public static final String GUARD_TYPE = com.bannerbound.core.entity.GuardWorkGoal.SELECTION_TYPE;
    public static final java.util.List<String> BASIC_PEN_ANIMALS = java.util.List.of(
        "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken");
    public static final String FLAG_EXTENSIVE_QUARRIES = "bannerbound.unlock.extensive_quarries";
    public static final int QUARRY_REACH_CHUNKS = 4;

    public ForemansRodItem(Properties properties) {
        super(properties);
    }

    private static boolean isOrderedType(String t) {
        return DIGGER_TYPE.equals(t) || FARMER_TYPE.equals(t) || HERDER_TYPE.equals(t)
            || MINER_TYPE.equals(t) || FORESTER_FARM_TYPE.equals(t) || GUARD_TYPE.equals(t);
    }

    private static boolean isBoxType(String t) {
        return DIGGER_TYPE.equals(t) || FARMER_TYPE.equals(t) || FORESTER_FARM_TYPE.equals(t);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }
        if (player.isShiftKeyDown()) {
            // TODO:
            /*String wsTypeNow = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
            if (isOrderedType(wsTypeNow)) {
                stack.remove(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
                stack.remove(BannerboundCore.FOREMAN_TARGET_NAME.get());
                Component wname = com.bannerbound.core.social.WorkstationNames.dynamic(
                    com.bannerbound.core.api.settlement.SettlementData.get(serverPlayer.serverLevel().getServer().overworld())
                        .getByPlayer(serverPlayer.getUUID()), wsTypeNow);
                serverPlayer.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.all", wname)
                        .withStyle(ChatFormatting.AQUA), true);
                return InteractionResultHolder.consume(stack);
            }*/
            PacketDistributor.sendToPlayer(serverPlayer, new OpenForemansRodPickerPayload());
            return InteractionResultHolder.consume(stack);
        }

        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.no_workstation")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();

        if (player.isShiftKeyDown()) {
            String wsTypeExpand = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
            BlockPos pendingA = stack.get(BannerboundCore.FOREMAN_POINT_A.get());
            if (pendingA != null && isBoxType(wsTypeExpand)) {
                tryExpandSelection(serverPlayer, stack, pendingA, clicked, wsTypeExpand);
                stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
                stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
                return InteractionResult.CONSUME;
            }
            net.minecraft.server.level.ServerLevel overworld = serverPlayer.serverLevel().getServer().overworld();
            com.bannerbound.core.api.settlement.Settlement settlement =
                com.bannerbound.core.api.settlement.SettlementData.get(overworld).getByPlayer(serverPlayer.getUUID());
            if (settlement != null) {
                for (BlockSelection sel : BlockSelectionRegistry.get(overworld)
                        .findContaining(clicked, settlement.id())) {
                    if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
                    String t = sel.workstationType();
                    if (!isOrderedType(t)) continue;
                    if (FARMER_TYPE.equals(t)) {
                        openFieldEditScreen(serverPlayer, overworld, sel, settlement);
                        return InteractionResult.CONSUME;
                    }
                    adoptSelectionOntoRod(serverPlayer, stack, sel, settlement);
                    stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
                    stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
                    return InteractionResult.CONSUME;
                }
            }
            return InteractionResult.PASS;
        }

        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.no_workstation")
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.FAIL;
        }

        if (HERDER_TYPE.equals(wsType)) {
            tryCommitHerderPen(serverPlayer, stack, clicked);
            return InteractionResult.CONSUME;
        }

        if (MINER_TYPE.equals(wsType)) {
            tryCommitMinerMarker(serverPlayer, stack, clicked);
            return InteractionResult.CONSUME;
        }

        if (GUARD_TYPE.equals(wsType)) {
            tryCommitGuardPost(serverPlayer, stack, clicked);
            return InteractionResult.CONSUME;
        }

        if (DIGGER_TYPE.equals(wsType)) {
            com.bannerbound.core.territory.ChunkResource material =
                com.bannerbound.core.territory.ChunkResources.typeAt(
                    serverPlayer.serverLevel(), new net.minecraft.world.level.ChunkPos(clicked));
            if (com.bannerbound.core.territory.MaterialDepositLayout.isMaterialChunk(material)) {
                tryCommitMaterialDepositMarker(serverPlayer, stack, clicked, material);
                return InteractionResult.CONSUME;
            }
        }

        BlockPos a = stack.get(BannerboundCore.FOREMAN_POINT_A.get());

        if (a == null) {
            stack.set(BannerboundCore.FOREMAN_POINT_A.get(), clicked);
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.point_a_set",
                    clicked.getX(), clicked.getY(), clicked.getZ())
                    .withStyle(ChatFormatting.GREEN), true);
            return InteractionResult.CONSUME;
        }

        tryCommitSelection(serverPlayer, stack, a, clicked, wsType);
        stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
        stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
        return InteractionResult.CONSUME;
    }

    private static void adoptSelectionOntoRod(ServerPlayer player, ItemStack stack, BlockSelection sel,
                                              com.bannerbound.core.api.settlement.Settlement settlement) {
        String t = sel.workstationType();
        stack.set(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get(), t);
        Component wname = com.bannerbound.core.social.WorkstationNames.dynamic(settlement, t);
        Component label;
        if (sel.targetsAllWorkers()) {
            stack.remove(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
            stack.remove(BannerboundCore.FOREMAN_TARGET_NAME.get());
            label = Component.translatable("bannerbound.foremans_rod.all", wname);
        } else {
            UUID owner = sel.assignedCitizenId();
            stack.set(BannerboundCore.FOREMAN_TARGET_CITIZEN.get(), owner.toString());
            String name = "Worker";
            net.minecraft.world.entity.Entity e =
                player.serverLevel().getServer().overworld().getEntity(owner);
            if (e instanceof com.bannerbound.core.entity.CitizenEntity c && c.getCustomName() != null) {
                name = c.getCustomName().getString();
            }
            stack.set(BannerboundCore.FOREMAN_TARGET_NAME.get(), name);
            label = Component.translatable("bannerbound.foremans_rod.one", wname, name);
        }
        player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.adopted", label)
            .withStyle(ChatFormatting.AQUA), true);
    }

    private static void openFieldEditScreen(ServerPlayer player, ServerLevel overworld,
                                            BlockSelection sel, Settlement settlement) {
        java.util.List<UUID> farmerIds = new java.util.ArrayList<>();
        java.util.List<String> farmerNames = new java.util.ArrayList<>();
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (overworld.getEntity(c.entityId()) instanceof com.bannerbound.core.entity.CitizenEntity ce
                    && com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID.equals(ce.getJobType())) {
                farmerIds.add(c.entityId());
                farmerNames.add(ce.getCustomName() != null
                    ? ce.getCustomName().getString() : ce.getName().getString());
            }
        }
        PacketDistributor.sendToPlayer(player, new com.bannerbound.core.network.OpenFieldEditPayload(
            sel.rodId(), com.bannerbound.core.farmer.SeedCandidates.itemIds(), sel.seedItemId(),
            farmerIds, farmerNames, sel.assignedCitizenId(),
            com.bannerbound.core.territory.CropChunks.bonusSeedIds(
                overworld, sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ())));
    }

    private static void tryCommitSelection(ServerPlayer player, ItemStack stack,
                                            BlockPos a, BlockPos b, String wsType) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        if (!checkVolume(player, a, b)) return;
        if (!checkTerritory(player, overworld, settlement, wsType, a, b)) return;

        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            a, b, wsType, player.getUUID(), "");
        if (DIGGER_TYPE.equals(wsType) || FARMER_TYPE.equals(wsType)
                || FORESTER_FARM_TYPE.equals(wsType)) {
            String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
            if (targetStr != null && !targetStr.isEmpty()) {
                try {
                    candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
                } catch (IllegalArgumentException ignored) { }
            }
        }

        if (FARMER_TYPE.equals(wsType)) {
            int amountOfFarmland = 0;

            for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
                BlockState state = overworld.getBlockState(pos);
                Block block = state.getBlock();

                if (block instanceof FarmBlock || block instanceof GrassBlock) {
                    amountOfFarmland++;
                }
            }

            if (amountOfFarmland == 0) {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.no_farmland")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
        }

        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);

        java.util.List<BlockSelection> mergeable = new java.util.ArrayList<>();
        boolean hardConflict = false;
        for (BlockSelection s : registry.getForSettlement(settlement.id())) {
            if (s.completed()) continue;
            if (!s.intersects(candidate)) continue;
            if (isMergeableField(s, wsType)) mergeable.add(s);
            else hardConflict = true;
        }
        if (hardConflict) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.overlap")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!mergeable.isEmpty()) {
            mergeIntoOverlapping(player, server, overworld, settlement, registry, candidate, mergeable, wsType);
            return;
        }

        registry.register(candidate);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.selection_committed", candidate.volume())
                .withStyle(ChatFormatting.GREEN), true);
        SelectionBroadcaster.broadcast(server);

        if ("farmer".equals(wsType)) {
            com.bannerbound.core.api.farmer.AwaitingSeedRegistry.queueAndMaybePush(
                server, player.getUUID(), selectionId,
                com.bannerbound.core.farmer.SeedCandidates.itemIds(),
                com.bannerbound.core.territory.CropChunks.bonusSeedIds(
                    overworld, candidate.minX(), candidate.minZ(), candidate.maxX(), candidate.maxZ()));
        }
    }

    private static boolean isMergeableField(BlockSelection s, String wsType) {
        if (s.kind() != BlockSelection.Kind.WORKSTATION) return false;
        if (!wsType.equals(s.workstationType())) return false;
        if (s.sizeX() == 1 && s.sizeY() == 1 && s.sizeZ() == 1) return false;
        return true;
    }

    private static void mergeIntoOverlapping(ServerPlayer player, MinecraftServer server,
            ServerLevel overworld, Settlement settlement, BlockSelectionRegistry registry,
            BlockSelection candidate, java.util.List<BlockSelection> mergeable, String wsType) {
        int unionMinX = candidate.minX(), unionMinY = candidate.minY(), unionMinZ = candidate.minZ();
        int unionMaxX = candidate.maxX(), unionMaxY = candidate.maxY(), unionMaxZ = candidate.maxZ();
        for (BlockSelection s : mergeable) {
            unionMinX = Math.min(unionMinX, s.minX()); unionMaxX = Math.max(unionMaxX, s.maxX());
            unionMinY = Math.min(unionMinY, s.minY()); unionMaxY = Math.max(unionMaxY, s.maxY());
            unionMinZ = Math.min(unionMinZ, s.minZ()); unionMaxZ = Math.max(unionMaxZ, s.maxZ());
        }
        BlockPos unionA = new BlockPos(unionMinX, unionMinY, unionMinZ);
        BlockPos unionB = new BlockPos(unionMaxX, unionMaxY, unionMaxZ);

        if (!checkVolume(player, unionA, unionB)) return;
        if (!checkTerritory(player, overworld, settlement, wsType, unionA, unionB)) return;

        BlockSelection primary = mergeable.get(0);
        BlockSelection grown = primary.withBounds(unionA, unionB);

        java.util.Set<UUID> merged = new java.util.HashSet<>();
        for (BlockSelection s : mergeable) merged.add(s.rodId());
        if (registry.anyOverlapExcludingAll(grown, merged)) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.overlap")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        for (BlockSelection s : mergeable) {
            if (!s.rodId().equals(primary.rodId())) registry.unregister(s.rodId());
        }
        registry.register(grown); // same rodId key -> upserts the surviving field in place, no duplicate
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.field_expanded", grown.volume())
                .withStyle(ChatFormatting.GREEN), true);
        SelectionBroadcaster.broadcast(server);
    }

    private static boolean checkVolume(ServerPlayer player, BlockPos a, BlockPos b) {
        long volume = BlockSelection.volumeOf(a, b);
        if (volume > MAX_SELECTION_VOLUME) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.too_large",
                    volume, MAX_SELECTION_VOLUME).withStyle(ChatFormatting.RED), true);
            return false;
        }
        return true;
    }

    private static boolean checkTerritory(ServerPlayer player, ServerLevel overworld,
            Settlement settlement, String wsType, BlockPos a, BlockPos b) {
        boolean extensive = DIGGER_TYPE.equals(wsType)
            && com.bannerbound.core.api.research.ResearchManager.hasFlag(settlement, FLAG_EXTENSIVE_QUARRIES);
        if (extensive) {
            String failKey = quarryReachFailKey(overworld, settlement, a, b);
            if (failKey != null) {
                player.displayClientMessage(Component.translatable(failKey, QUARRY_REACH_CHUNKS)
                    .withStyle(ChatFormatting.RED), true);
                return false;
            }
        } else if (!isFullyWithinTerritory(settlement, a, b)) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.outside_territory")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        return true;
    }

    private void tryExpandSelection(ServerPlayer player, ItemStack stack,
                                    BlockPos a, BlockPos b, String wsType) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        UUID rodTarget = BlockSelection.NO_CITIZEN;
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try { rodTarget = UUID.fromString(targetStr); } catch (IllegalArgumentException ignored) { }
        }

        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        BlockSelection probe = BlockSelection.workstation(
            UUID.randomUUID(), settlement.id(), settlement.color().ordinal(), a, b, wsType,
            player.getUUID(), "");
        int newMinX = Math.min(a.getX(), b.getX()) - 1, newMaxX = Math.max(a.getX(), b.getX()) + 1;
        int newMinY = Math.min(a.getY(), b.getY()) - 1, newMaxY = Math.max(a.getY(), b.getY()) + 1;
        int newMinZ = Math.min(a.getZ(), b.getZ()) - 1, newMaxZ = Math.max(a.getZ(), b.getZ()) + 1;

        BlockSelection best = null;
        long bestDist = Long.MAX_VALUE;
        for (BlockSelection s : registry.getForSettlement(settlement.id())) {
            if (s.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (s.completed()) continue;
            if (!wsType.equals(s.workstationType())) continue;
            if (!s.assignedCitizenId().equals(rodTarget)) continue;
            if (s.sizeX() == 1 && s.sizeY() == 1 && s.sizeZ() == 1) continue;
            boolean touches = s.minX() <= newMaxX && s.maxX() >= newMinX
                && s.minY() <= newMaxY && s.maxY() >= newMinY
                && s.minZ() <= newMaxZ && s.maxZ() >= newMinZ;
            if (!touches) continue;
            BlockPos sMin = new BlockPos(s.minX(), s.minY(), s.minZ());
            long d = (long) sMin.distSqr(new BlockPos(probe.minX(), probe.minY(), probe.minZ()));
            if (d < bestDist) { bestDist = d; best = s; }
        }

        if (best == null) {
            tryCommitSelection(player, stack, a, b, wsType);
            return;
        }

        BlockPos unionA = new BlockPos(
            Math.min(best.minX(), Math.min(a.getX(), b.getX())),
            Math.min(best.minY(), Math.min(a.getY(), b.getY())),
            Math.min(best.minZ(), Math.min(a.getZ(), b.getZ())));
        BlockPos unionB = new BlockPos(
            Math.max(best.maxX(), Math.max(a.getX(), b.getX())),
            Math.max(best.maxY(), Math.max(a.getY(), b.getY())),
            Math.max(best.maxZ(), Math.max(a.getZ(), b.getZ())));

        long volume = BlockSelection.volumeOf(unionA, unionB);
        if (volume > MAX_SELECTION_VOLUME) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.too_large",
                    volume, MAX_SELECTION_VOLUME).withStyle(ChatFormatting.RED), true);
            return;
        }
        boolean extensive = DIGGER_TYPE.equals(wsType)
            && com.bannerbound.core.api.research.ResearchManager.hasFlag(settlement, FLAG_EXTENSIVE_QUARRIES);
        if (extensive) {
            String failKey = quarryReachFailKey(overworld, settlement, unionA, unionB);
            if (failKey != null) {
                player.displayClientMessage(Component.translatable(failKey, QUARRY_REACH_CHUNKS)
                    .withStyle(ChatFormatting.RED), true);
                return;
            }
        } else if (!isFullyWithinTerritory(settlement, unionA, unionB)) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.outside_territory")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        BlockSelection grown = best.withBounds(unionA, unionB);
        if (registry.anyOverlapExcluding(grown, best.rodId())) {
            player.displayClientMessage(
                Component.translatable("bannerbound.foremans_rod.overlap")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(grown);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.field_expanded", volume)
                .withStyle(ChatFormatting.GREEN), true);
        SelectionBroadcaster.broadcast(server);
    }

    private static void tryCommitHerderPen(ServerPlayer player, ItemStack stack, BlockPos clicked) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !HERDER_TYPE.equals(sel.workstationType())) {
                continue;
            }
            BlockPos marker = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (marker.distSqr(clicked) > 64 * 64) continue;
            com.bannerbound.core.building.PenEnclosure.Result r =
                com.bannerbound.core.building.PenEnclosure.scan(level, marker);
            if (r.valid() && (r.interior().contains(clicked) || r.interior().contains(clicked.below()))) {
                if (player.isShiftKeyDown()) reassignPen(player, stack, overworld, sel);
                else openPenKeepScreen(player, level, sel, r);
                return;
            }
        }
        if (!isWorkableChunk(settlement, clicked)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        com.bannerbound.core.building.PenEnclosure.Result pen =
            com.bannerbound.core.building.PenEnclosure.scan(level, clicked);
        if (!pen.valid()) {
            player.displayClientMessage(Component.translatable(penFailKey(pen.reason()))
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        java.util.List<String> animals = new java.util.ArrayList<>(BASIC_PEN_ANIMALS);
        if (com.bannerbound.core.territory.ChunkResources.typeAt(level,
                new net.minecraft.world.level.ChunkPos(clicked)) == com.bannerbound.core.territory.ChunkResource.HORSES) {
            animals.add("minecraft:horse");
        }
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.OpenPenAnimalPickerPayload(clicked, animals));
    }

    private static void openPenKeepScreen(ServerPlayer player, ServerLevel level,
            BlockSelection sel, com.bannerbound.core.building.PenEnclosure.Result r) {
        String packed = sel.seedItemId();
        String animalId = com.bannerbound.core.entity.HerderWorkGoal.penAnimalId(packed);
        int kills = com.bannerbound.core.entity.HerderWorkGoal.penKills(packed);
        int keep = com.bannerbound.core.entity.HerderWorkGoal.penKeep(packed);
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(animalId);
        net.minecraft.world.entity.EntityType<?> type = rl == null ? null
            : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        int cap = com.bannerbound.core.building.PenEnclosure.stats(level, r)
            .capacity(com.bannerbound.core.entity.HerderWorkGoal.animalSize(type));
        int mature = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
            r.bounds().inflate(1.0, 2.0, 1.0),
            a -> a.isAlive() && !a.isBaby() && type != null && a.getType() == type && inPenColumn(r, a)).size();
        BlockPos marker = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.OpenPenKeepPayload(marker, animalId, mature, cap, kills, keep));
    }

    private static boolean inPenColumn(com.bannerbound.core.building.PenEnclosure.Result r,
            net.minecraft.world.entity.Entity a) {
        // 1-block margin, not r.bounds(): a raw bbox over-counts wild stock on an L-shaped pen.
        int feetY = a.blockPosition().getY();
        int cx0 = (int) Math.floor(a.getX() - 1.0), cx1 = (int) Math.floor(a.getX() + 1.0);
        int cz0 = (int) Math.floor(a.getZ() - 1.0), cz1 = (int) Math.floor(a.getZ() + 1.0);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (r.interior().contains(new BlockPos(cx, feetY, cz))
                    || r.interior().contains(new BlockPos(cx, feetY - 1, cz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void reassignPen(ServerPlayer player, ItemStack stack, ServerLevel overworld, BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.pen_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.pen_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    private static void tryCommitMaterialDepositMarker(ServerPlayer player, ItemStack stack,
                                                       BlockPos clicked,
                                                       com.bannerbound.core.territory.ChunkResource type) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(clicked);
        if (settlement.workingClaims().contains(cp.toLong())) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.deposit_outpost_managed")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !DIGGER_TYPE.equals(sel.workstationType())) {
                continue;
            }
            if (!com.bannerbound.core.territory.MaterialDepositLayout.isMaterialPacked(sel.seedItemId())) {
                continue;
            }
            if (!cp.equals(new net.minecraft.world.level.ChunkPos(
                    new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) {
                continue;
            }
            if (player.isShiftKeyDown()) {
                reassignMaterialDeposit(player, stack, overworld, sel);
            } else {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.deposit_already_marked",
                        resourceLabel(com.bannerbound.core.territory.MaterialDepositLayout
                            .materialResource(sel.seedItemId())))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return;
        }
        if (!isWorkableChunk(settlement, clicked)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (com.bannerbound.core.territory.MaterialDepositLayout.isStoneBoulder(type)
                && !com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, com.bannerbound.core.social.WorkstationNames.FLAG_QUARRY)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_researched")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        int baseY = com.bannerbound.core.territory.MaterialDepositLayout.locateBaseY(level, cp, type)
            .orElseGet(() -> com.bannerbound.core.territory.MaterialDepositLayout.dress(level, cp));
        if (baseY == Integer.MIN_VALUE) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.deposit_unworkable")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        BlockPos anchor = new BlockPos(cp.getMinBlockX() + 8, baseY, cp.getMinBlockZ() + 8);
        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            anchor, anchor, DIGGER_TYPE, player.getUUID(),
            com.bannerbound.core.territory.MaterialDepositLayout.packDeposit(type, baseY));
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
            } catch (IllegalArgumentException ignored) { }
        }
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        if (registry.anyOverlapExcluding(candidate, selectionId)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.overlap")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(server);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.deposit_marked", resourceLabel(type))
                .withStyle(ChatFormatting.GREEN), true);
    }

    private static void reassignMaterialDeposit(ServerPlayer player, ItemStack stack,
                                                ServerLevel overworld, BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.deposit_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.deposit_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    private static void tryCommitMinerMarker(ServerPlayer player, ItemStack stack, BlockPos clicked) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(clicked);
        if (settlement.workingClaims().contains(cp.toLong())) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.mine_outpost_managed")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !MINER_TYPE.equals(sel.workstationType())) {
                continue;
            }
            if (!cp.equals(new net.minecraft.world.level.ChunkPos(
                    new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) {
                continue;
            }
            if (player.isShiftKeyDown()) {
                reassignMine(player, stack, overworld, sel);
            } else {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.mine_already_marked",
                        resourceLabel(com.bannerbound.core.entity.MinerWorkGoal.mineResource(sel.seedItemId())))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return;
        }
        if (!isWorkableChunk(settlement, clicked)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        com.bannerbound.core.territory.ChunkResource type =
            com.bannerbound.core.territory.ChunkResources.typeAt(level, cp);
        if (!com.bannerbound.core.territory.BoulderLayout.isOreChunk(type)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.mine_no_deposit")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (com.bannerbound.core.territory.BoulderLayout.dropFor(type).isEmpty()) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.mine_unworkable")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        int baseY = com.bannerbound.core.territory.BoulderLayout.locateBaseY(level, cp, type)
            .orElseGet(() -> com.bannerbound.core.territory.BoulderLayout.dress(level, cp));

        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            clicked, clicked, MINER_TYPE, player.getUUID(),
            com.bannerbound.core.entity.MinerWorkGoal.packMine(type, baseY));
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
            } catch (IllegalArgumentException ignored) { }
        }
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        if (registry.anyOverlapExcluding(candidate, selectionId)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.overlap")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(server);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.mine_marked", resourceLabel(type))
                .withStyle(ChatFormatting.GREEN), true);
    }

    private static void tryCommitGuardPost(ServerPlayer player, ItemStack stack, BlockPos clicked) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION || !GUARD_TYPE.equals(sel.workstationType())) {
                continue;
            }
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (anchor.distManhattan(clicked) > 2) continue;
            if (player.isShiftKeyDown()) {
                reassignGuardPost(player, stack, overworld, sel);
            } else {
                player.displayClientMessage(
                    Component.translatable("bannerbound.foremans_rod.guard_post_already_marked")
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return;
        }
        if (!settlement.claimedChunks().contains(
                new net.minecraft.world.level.ChunkPos(clicked).toLong())) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workstation(
            selectionId, settlement.id(), settlement.color().ordinal(),
            clicked, clicked, GUARD_TYPE, player.getUUID(), "");
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                candidate = candidate.withAssignedCitizen(UUID.fromString(targetStr));
            } catch (IllegalArgumentException ignored) { }
        }
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
        if (registry.anyOverlapExcluding(candidate, selectionId)) {
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.overlap")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(server);
        player.displayClientMessage(
            Component.translatable("bannerbound.foremans_rod.guard_post_marked")
                .withStyle(ChatFormatting.GREEN), true);
    }

    private static void reassignGuardPost(ServerPlayer player, ItemStack stack, ServerLevel overworld,
                                          BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.guard_post_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.guard_post_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    private static Component resourceLabel(com.bannerbound.core.territory.ChunkResource type) {
        return Component.translatable("bannerbound.resource." + type.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static void reassignMine(ServerPlayer player, ItemStack stack, ServerLevel overworld, BlockSelection sel) {
        String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
        java.util.UUID who = BlockSelection.NO_CITIZEN;
        net.minecraft.network.chat.MutableComponent msg =
            Component.translatable("bannerbound.foremans_rod.mine_assigned_all");
        if (targetStr != null && !targetStr.isEmpty()) {
            try {
                who = java.util.UUID.fromString(targetStr);
                String name = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
                msg = Component.translatable("bannerbound.foremans_rod.mine_assigned",
                    name != null && !name.isEmpty() ? name : "?");
            } catch (IllegalArgumentException ignored) { who = BlockSelection.NO_CITIZEN; }
        }
        BlockSelectionRegistry.get(overworld).register(sel.withAssignedCitizen(who));
        com.bannerbound.core.world.SelectionBroadcaster.broadcast(player.getServer());
        player.displayClientMessage(msg.withStyle(ChatFormatting.GREEN), true);
    }

    public static String penFailKey(com.bannerbound.core.building.PenEnclosure.FailReason reason) {
        return switch (reason) {
            case NO_GATE -> "bannerbound.foremans_rod.pen_no_gate";
            case NO_WATER -> "bannerbound.foremans_rod.pen_no_water";
            case NO_GRASS -> "bannerbound.foremans_rod.pen_no_grass";
            case TOO_LARGE -> "bannerbound.foremans_rod.pen_not_enclosed";
        };
    }

    private static String quarryReachFailKey(ServerLevel overworld, Settlement settlement,
                                             BlockPos a, BlockPos b) {
        SettlementData data = SettlementData.get(overworld);
        java.util.Set<Long> claimed = settlement.claimedChunks();
        int minCX = Math.min(a.getX(), b.getX()) >> 4;
        int maxCX = Math.max(a.getX(), b.getX()) >> 4;
        int minCZ = Math.min(a.getZ(), b.getZ()) >> 4;
        int maxCZ = Math.max(a.getZ(), b.getZ()) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                long packed = new net.minecraft.world.level.ChunkPos(cx, cz).toLong();
                if (claimed.contains(packed)) continue;
                Settlement owner = data.getByChunk(packed);
                if (owner != null) return "bannerbound.foremans_rod.foreign_territory";
                if (!withinReachOfClaim(claimed, cx, cz)) return "bannerbound.foremans_rod.quarry_too_far";
            }
        }
        return null;
    }

    private static boolean withinReachOfClaim(java.util.Set<Long> claimed, int cx, int cz) {
        for (int dx = -QUARRY_REACH_CHUNKS; dx <= QUARRY_REACH_CHUNKS; dx++) {
            for (int dz = -QUARRY_REACH_CHUNKS; dz <= QUARRY_REACH_CHUNKS; dz++) {
                if (claimed.contains(new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz).toLong())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWorkableChunk(Settlement settlement, BlockPos pos) {
        long packed = new net.minecraft.world.level.ChunkPos(pos).toLong();
        return settlement.claimedChunks().contains(packed)
            || settlement.workingClaims().contains(packed);
    }

    public static boolean isFullyWithinTerritory(Settlement settlement, BlockPos a, BlockPos b) {
        int minBlockX = Math.min(a.getX(), b.getX());
        int maxBlockX = Math.max(a.getX(), b.getX());
        int minBlockZ = Math.min(a.getZ(), b.getZ());
        int maxBlockZ = Math.max(a.getZ(), b.getZ());
        int minCX = minBlockX >> 4;
        int maxCX = maxBlockX >> 4;
        int minCZ = minBlockZ >> 4;
        int maxCZ = maxBlockZ >> 4;
        java.util.Set<Long> claimed = settlement.claimedChunks();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!claimed.contains(new net.minecraft.world.level.ChunkPos(cx, cz).toLong())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public Component getName(ItemStack stack) {
        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            return super.getName(stack);
        }
        return Component.translatable("item.bannerbound.foremans_rod.named", targetLabel(stack, wsType));
    }

    private static Component targetLabel(ItemStack stack, String wsType) {
        Component type = clientWorkerTypeName(wsType);
        String targetName = stack.get(BannerboundCore.FOREMAN_TARGET_NAME.get());
        if (isOrderedType(wsType) && targetName != null && !targetName.isEmpty()) {
            return Component.translatable("bannerbound.foremans_rod.one", type, targetName);
        }
        return type;
    }

    private static Component clientWorkerTypeName(String wsType) {
        // client-only ClientResearchState: guard so a dedicated server never touches it.
        if (DIGGER_TYPE.equals(wsType) && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                && com.bannerbound.core.client.ClientResearchState.hasFlag(
                    com.bannerbound.core.social.WorkstationNames.FLAG_QUARRY)) {
            return Component.translatable("bannerbound.workstation_type.quarryworker");
        }
        return Component.translatable("bannerbound.workstation_type." + wsType);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String wsType = stack.get(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
        if (wsType == null || wsType.isEmpty()) {
            tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.no_workstation")
                .withStyle(ChatFormatting.DARK_GRAY));
            super.appendHoverText(stack, context, tooltip, flag);
            return;
        }
        tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.workstation",
            targetLabel(stack, wsType))
            .withStyle(ChatFormatting.GRAY));

        BlockPos a = stack.get(BannerboundCore.FOREMAN_POINT_A.get());
        if (a != null) {
            tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.a_only",
                a.getX(), a.getY(), a.getZ()).withStyle(ChatFormatting.DARK_GRAY));
            if (isBoxType(wsType)) {
                tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.expand_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            tooltip.add(Component.translatable("bannerbound.foremans_rod.tooltip.no_selection")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        super.appendHoverText(stack, context, tooltip, flag);
    }
}
