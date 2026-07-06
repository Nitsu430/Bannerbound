package com.bannerbound.core.citystate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.DiplomacyManager;
import com.bannerbound.core.api.settlement.FactionBanner;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.barbarian.BarbarianCapability;
import com.bannerbound.core.barbarian.BarbarianTech;
import com.bannerbound.core.citystate.CityState.CityStateWar;
import com.bannerbound.core.entity.MercenaryEntity;
import com.bannerbound.core.network.DiplomacyActionPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * War with AI city-states (CITY_STATES plan section 2). Players declare; city-states only DEFEND --
 * they never raid. A city-state at war fields {@link MercenaryEntity} defenders that respawn far
 * slower than barbarians (attrition: one recruit back every MERC_RESPAWN_TICKS), so sustained
 * pressure drains the garrison and lets you break the banner. Breaking the banner does NOT capture:
 * it turns the standard into a carryable item that must be carried to YOUR town hall ({@link #tryScore})
 * to win. The victor then chooses the city-state's fate -- raze / vassal / annex (annex is locked
 * until a free settlement slot exists, far-future Feudalism); an ignored capture auto-razes after
 * CAPTURE_TIMEOUT.
 *
 * <p>Entry is government-gated via {@link #routeAction}, paralleling DiplomacyManager.routeAction:
 * NONE and an authorised chief act directly, COUNCIL sends declare/peace to a ChatVoteManager vote
 * while capture resolution acts directly (the war is already won). Mirrors the static-manager shape
 * of BarbarianCampManager and is driven from {@code ResearchEvents.onServerTick} via {@link #tickAll}.
 *
 * <p>Garrisons are transient (GARRISONS map, never saved) and respawn on player approach. Standard
 * bookkeeping reuses the settlement stolen-standard item plumbing -- DiplomacyManager delegates its
 * pickup / drop / score / logout hooks here. Merc-cap scales with prosperity so sacking a rich trade
 * partner costs more defenders than a starving hamlet.
 */
@ApiStatus.Internal
public final class CityStateWarManager {
    private static final int WAR_TICK_INTERVAL = 20;
    private static final int WAR_WARNING_TICKS = 20 * 60;
    private static final long REDECLARE_COOLDOWN = 20L * 60 * 30;
    private static final long CAPTURE_TIMEOUT = 20L * 60 * 30;
    private static final long MERC_RESPAWN_TICKS = 20L * 120;
    private static final double GARRISON_DIST_SQ = 64.0 * 64.0;
    private static final long DROPPED_RETURN_TICKS = 20L * 60 * 5;

    private static final Map<UUID, Garrison> GARRISONS = new HashMap<>();

    private static final class Garrison {
        final Set<UUID> ids = new HashSet<>();
        int killed;
        long lastRecruitTick;
    }

    private CityStateWarManager() {
    }

    public static void routeAction(ServerPlayer actor, int action, CityState cs) {
        if (!CityStateManager.enabled()) return;
        MinecraftServer server = actor.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(actor.getUUID());
        if (s == null || !s.members().contains(actor.getUUID())) return;

        switch (s.governmentType()) {
            case NONE -> performAction(server, s, action, cs, false);
            case CHIEFDOM -> {
                if (s.canActAsChief(actor.getUUID())) {
                    performAction(server, s, action, cs, false);
                } else {
                    actor.displayClientMessage(Component.translatable("bannerbound.suggest.sent")
                        .withStyle(ChatFormatting.GRAY), true);
                }
            }
            case COUNCIL -> {
                com.bannerbound.core.api.settlement.ChatVoteManager.Kind kind = voteKind(action);
                if (kind != null) {
                    com.bannerbound.core.api.settlement.ChatVoteManager.start(
                        server, s, kind, actor, cs.id, cs.name, true);
                } else {
                    performAction(server, s, action, cs, false);
                }
            }
        }
    }

    private static com.bannerbound.core.api.settlement.ChatVoteManager.Kind voteKind(int action) {
        return switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR ->
                com.bannerbound.core.api.settlement.ChatVoteManager.Kind.DECLARE_WAR;
            case DiplomacyActionPayload.OFFER_PEACE ->
                com.bannerbound.core.api.settlement.ChatVoteManager.Kind.OFFER_PEACE;
            default -> null;
        };
    }

    public static void performCouncilAction(MinecraftServer server, Settlement s,
            com.bannerbound.core.api.settlement.ChatVoteManager.ChatVote vote) {
        CityState cs = CityStateData.get(server.overworld()).getById(vote.targetCitizen);
        if (cs == null) return;
        int action = switch (vote.kind) {
            case DECLARE_WAR -> DiplomacyActionPayload.DECLARE_WAR;
            case OFFER_PEACE -> DiplomacyActionPayload.OFFER_PEACE;
            default -> -1;
        };
        if (action >= 0) performAction(server, s, action, cs, true);
    }

    private static void performAction(MinecraftServer server, Settlement s, int action, CityState cs,
                                      boolean force) {
        switch (action) {
            case DiplomacyActionPayload.DECLARE_WAR -> declareWar(server, s, cs);
            case DiplomacyActionPayload.OFFER_PEACE -> offerPeace(server, s, cs);
            case DiplomacyActionPayload.RAZE -> resolveCapture(server, s, cs, DiplomacyActionPayload.RAZE);
            case DiplomacyActionPayload.VASSAL -> resolveCapture(server, s, cs, DiplomacyActionPayload.VASSAL);
            case DiplomacyActionPayload.ANNEX -> resolveCapture(server, s, cs, DiplomacyActionPayload.ANNEX);
            default -> { }
        }
    }

    public static boolean declareWar(MinecraftServer server, Settlement s, CityState cs) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        if (!cs.discoveredBy.contains(s.id())) return false;
        CityStateWar w = cs.warWith(s.id());
        if (w != null && (w.active || w.pendingTicks > 0 || w.capturedAt > 0)) return false;
        if (w != null && w.redeclareAfter > now) return false;
        w = cs.getOrCreateWar(s.id());
        w.pendingTicks = WAR_WARNING_TICKS;
        w.active = false;
        w.capturedAt = 0;
        CityStateData.get(level).setDirty();
        announce(server, s, Component.translatable("bannerbound.citystate.war.declared",
            cityName(cs), s.factionName()).withStyle(ChatFormatting.RED));
        DiplomacyManager.broadcastDiplomacyState(server, s);
        return true;
    }

    public static boolean offerPeace(MinecraftServer server, Settlement s, CityState cs) {
        CityStateWar w = cs.warWith(s.id());
        if (w == null || (!w.active && w.pendingTicks <= 0) || w.capturedAt > 0) return false;
        endWar(server, s, cs, w);
        announce(server, s, Component.translatable("bannerbound.citystate.war.peace",
            cityName(cs)).withStyle(ChatFormatting.GREEN));
        return true;
    }

    private static void endWar(MinecraftServer server, Settlement s, CityState cs, CityStateWar w) {
        long now = server.overworld().getGameTime();
        w.active = false;
        w.pendingTicks = 0;
        w.peaceOffered = false;
        w.capturedAt = 0;
        w.redeclareAfter = now + REDECLARE_COOLDOWN;
        if (cs.standardInPlay && !cs.isFrozen()) returnStandard(server, cs);
        disbandGarrisonIfNoWars(cs);
        CityStateData.get(server.overworld()).setDirty();
        DiplomacyManager.broadcastDiplomacyState(server, s);
    }

    public static boolean isSettlementAtWar(ServerLevel level, UUID settlementId) {
        for (CityState cs : CityStateData.get(level).all()) {
            CityStateWar w = cs.warWith(settlementId);
            if (w != null && (w.active || w.pendingTicks > 0 || w.capturedAt > 0)) return true;
        }
        return false;
    }

    public static void onSettlementRemoved(ServerLevel level, UUID settlementId) {
        boolean changed = false;
        for (CityState cs : CityStateData.get(level).all()) {
            if (cs.wars.remove(settlementId) != null) changed = true;
            if (cs.relScore.remove(settlementId) != null) changed = true;
            if (cs.discoveredBy.remove(settlementId)) changed = true;
            disbandGarrisonIfNoWars(cs);
        }
        if (changed) CityStateData.get(level).setDirty();
    }

    public static boolean resolveCapture(MinecraftServer server, Settlement s, CityState cs, int action) {
        CityStateWar w = cs.warWith(s.id());
        if (w == null || w.capturedAt <= 0) return false;
        ServerLevel level = server.overworld();
        CityStateData data = CityStateData.get(level);
        switch (action) {
            case DiplomacyActionPayload.RAZE -> {
                discardGarrison(level, cs);
                removeStandardItems(level, cs.id);
                java.util.Set<Long> area = data.razeVillage(cs);
                com.bannerbound.core.ruin.RuinManager.queue(level, area);
                announce(server, s, Component.translatable("bannerbound.citystate.razed",
                    cityName(cs)).withStyle(ChatFormatting.RED));
            }
            case DiplomacyActionPayload.VASSAL -> {
                cs.vassalOf = s.id();
                cs.wars.clear();
                cs.bannerStamped = false;
                cs.relScore.put(s.id(), 100);
                disbandGarrisonIfNoWars(cs);
                data.setDirty();
                announce(server, s, Component.translatable("bannerbound.citystate.vassal",
                    cityName(cs), s.factionName()).withStyle(ChatFormatting.GOLD));
            }
            case DiplomacyActionPayload.ANNEX -> {
                return false;
            }
            default -> { return false; }
        }
        DiplomacyManager.broadcastDiplomacyState(server, s);
        return true;
    }

    public static boolean onBannerBroken(ServerLevel level, ServerPlayer breaker, BlockPos pos) {
        CityStateData data = CityStateData.get(level);
        CityState cs = data.bannerAt(pos);
        if (cs == null || cs.standardInPlay || cs.capturedBySettlement() != null) return false;
        Settlement s = SettlementData.get(level).getByPlayer(breaker.getUUID());
        if (s == null || !cs.isActiveEnemy(s.id())) return false;
        cs.bannerRazed = true;
        cs.bannerStamped = false;
        cs.standardInPlay = true;
        ItemStack stack = standardStack(cs);
        if (breaker.getInventory().add(stack)) {
            cs.standardCarrier = breaker.getUUID();
            cs.standardDroppedPos = null;
            cs.standardAutoReturnAt = 0;
        } else {
            spawnStandardItem(level, cs, pos, stack);
            cs.standardCarrier = null;
            cs.standardDroppedPos = pos.immutable();
            cs.standardDroppedAt = level.getGameTime();
            cs.standardAutoReturnAt = level.getGameTime() + DROPPED_RETURN_TICKS;
        }
        data.setDirty();
        announce(level.getServer(), s, Component.translatable("bannerbound.citystate.standard_taken",
            cityName(cs)).withStyle(ChatFormatting.GOLD));
        DiplomacyManager.broadcastDiplomacyState(level.getServer(), s);
        return true;
    }

    private static ItemStack standardStack(CityState cs) {
        DyeColor dye = DyeColor.byId((int) Math.floorMod(cs.languageSeed, 16));
        ItemStack stack = new ItemStack(BannerBlock.byColor(dye).asItem());
        stack.set(BannerboundCore.STOLEN_STANDARD_SETTLEMENT.get(), cs.id.toString());
        stack.set(BannerboundCore.STOLEN_STANDARD_NAME.get(), cs.name);
        stack.set(DataComponents.CUSTOM_NAME,
            Component.translatable("bannerbound.item.stolen_standard", cs.name));
        return stack;
    }

    private static void spawnStandardItem(ServerLevel level, CityState cs, BlockPos pos, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        DiplomacyManager.prepareStolenStandardItem(item);
        level.addFreshEntity(item);
    }

    public static boolean canPickup(MinecraftServer server, ServerPlayer player, ItemEntity item, java.util.UUID csId) {
        ServerLevel level = server.overworld();
        CityState cs = CityStateData.get(level).getById(csId);
        if (cs == null || !cs.standardInPlay) { item.discard(); return false; }
        Settlement mine = SettlementData.get(level).getByPlayer(player.getUUID());
        if (mine != null && cs.isActiveEnemy(mine.id())) return true;
        player.displayClientMessage(Component.translatable(
            "bannerbound.diplomacy.standard.pickup_blocked", cs.name).withStyle(ChatFormatting.RED), true);
        return false;
    }

    public static void onPickedUp(MinecraftServer server, ServerPlayer player, java.util.UUID csId) {
        ServerLevel level = server.overworld();
        CityStateData data = CityStateData.get(level);
        CityState cs = data.getById(csId);
        if (cs == null) return;
        Settlement mine = SettlementData.get(level).getByPlayer(player.getUUID());
        if (mine == null || !cs.isActiveEnemy(mine.id())) {
            removeStandardFromInventory(player, csId);
            returnStandard(server, cs);
            return;
        }
        cs.standardCarrier = player.getUUID();
        cs.standardDroppedPos = null;
        cs.standardAutoReturnAt = 0;
        data.setDirty();
    }

    public static void onDropped(MinecraftServer server, ServerPlayer player, ItemEntity item, java.util.UUID csId) {
        ServerLevel level = server.overworld();
        CityState cs = CityStateData.get(level).getById(csId);
        if (cs == null || !cs.standardInPlay) { item.discard(); return; }
        if (player.level() != level) { item.discard(); returnStandard(server, cs); return; }
        cs.standardCarrier = null;
        cs.standardDroppedPos = item.blockPosition();
        cs.standardDroppedAt = level.getGameTime();
        cs.standardAutoReturnAt = level.getGameTime() + DROPPED_RETURN_TICKS;
        CityStateData.get(level).setDirty();
    }

    public static boolean tryScore(MinecraftServer server, ServerPlayer player, BlockPos clickedPos) {
        ServerLevel level = server.overworld();
        Settlement scorer = SettlementData.get(level).getByPlayer(player.getUUID());
        if (scorer == null || scorer.townHallPos() == null || !scorer.townHallPos().equals(clickedPos)) {
            return false;
        }
        ItemStack held = heldStandard(player);
        if (held.isEmpty()) return false;
        java.util.UUID csId = DiplomacyManager.stolenStandardTarget(held);
        CityStateData data = CityStateData.get(level);
        CityState cs = csId == null ? null : data.getById(csId);
        if (cs == null) return false;
        CityStateWar w = cs.warWith(scorer.id());
        if (w == null || !w.active) {
            player.sendSystemMessage(Component.translatable("bannerbound.diplomacy.standard.not_enemy")
                .withStyle(ChatFormatting.RED));
            return true;
        }
        if (!FactionBanner.requireRaised(level, player, scorer)) return true;
        removeStandardFromInventory(player, csId);
        removeStandardItems(level, csId);
        w.active = false;
        w.capturedAt = level.getGameTime();
        cs.standardInPlay = false;
        cs.standardCarrier = null;
        discardGarrison(level, cs);
        data.setDirty();
        announce(server, scorer, Component.translatable("bannerbound.citystate.captured",
            cityName(cs)).withStyle(ChatFormatting.GOLD));
        DiplomacyManager.broadcastDiplomacyState(server, scorer);
        return true;
    }

    public static void dropCarriedStandards(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        CityStateData data = CityStateData.get(level);
        for (CityState cs : data.all()) {
            if (!player.getUUID().equals(cs.standardCarrier)) continue;
            if (!playerHasStandard(player, cs.id)) continue;
            removeStandardFromInventory(player, cs.id);
            if (player.level() == level) {
                spawnStandardItem(level, cs, player.blockPosition(), standardStack(cs));
                cs.standardCarrier = null;
                cs.standardDroppedPos = player.blockPosition();
                cs.standardDroppedAt = level.getGameTime();
                cs.standardAutoReturnAt = level.getGameTime() + DROPPED_RETURN_TICKS;
                data.setDirty();
            } else {
                returnStandard(server, cs);
            }
        }
    }

    private static void reconcileStandards(ServerLevel level) {
        CityStateData data = CityStateData.get(level);
        MinecraftServer server = level.getServer();
        for (CityState cs : data.all()) {
            if (!cs.standardInPlay) continue;
            ServerPlayer carrier = cs.standardCarrier == null ? null
                : server.getPlayerList().getPlayer(cs.standardCarrier);
            if (carrier != null && playerHasStandard(carrier, cs.id)) {
                carrier.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 45, 0, true, false, true));
                continue;
            }
            // Defer until the drop chunk is loaded: an unloaded standard reads as "lost" -> banner re-raises -> breaking it again mints a duplicate.
            if (cs.standardDroppedPos != null) {
                net.minecraft.world.level.ChunkPos dropChunk =
                    new net.minecraft.world.level.ChunkPos(cs.standardDroppedPos);
                if (!level.hasChunk(dropChunk.x, dropChunk.z)) continue;
            }
            boolean onGround = false;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(-3.0e7, level.getMinBuildHeight(), -3.0e7,
                        3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
                if (cs.id.equals(DiplomacyManager.stolenStandardTarget(item.getItem()))) { onGround = true; break; }
            }
            if (!onGround) { returnStandard(server, cs); continue; }
            if (cs.standardAutoReturnAt > 0 && level.getGameTime() >= cs.standardAutoReturnAt) {
                returnStandard(server, cs);
            }
        }
    }

    public static void returnStandard(MinecraftServer server, CityState cs) {
        if (cs == null) return;
        ServerLevel level = server.overworld();
        removeStandardItems(level, cs.id);
        cs.standardInPlay = false;
        cs.standardCarrier = null;
        cs.standardDroppedPos = null;
        cs.standardAutoReturnAt = 0;
        cs.bannerStamped = false;
        cs.bannerRazed = false;
        CityStateData.get(level).setDirty();
        net.minecraft.network.chat.Component msg = Component.translatable(
            "bannerbound.citystate.standard_returned", cityName(cs)).withStyle(ChatFormatting.GREEN);
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(cs.center, 200.0)) p.displayClientMessage(msg, false);
        }
    }

    private static ItemStack heldStandard(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (DiplomacyManager.isStolenStandard(main)) return main;
        ItemStack off = player.getOffhandItem();
        return DiplomacyManager.isStolenStandard(off) ? off : ItemStack.EMPTY;
    }

    private static boolean playerHasStandard(ServerPlayer player, java.util.UUID csId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (csId.equals(DiplomacyManager.stolenStandardTarget(inv.getItem(i)))) return true;
        }
        return csId.equals(DiplomacyManager.stolenStandardTarget(player.containerMenu.getCarried()));
    }

    private static void removeStandardFromInventory(ServerPlayer player, java.util.UUID csId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (csId.equals(DiplomacyManager.stolenStandardTarget(stack))) {
                stack.shrink(1);
                inv.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (csId.equals(DiplomacyManager.stolenStandardTarget(carried))) {
            carried.shrink(1);
            player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }
        player.containerMenu.broadcastChanges();
    }

    private static void removeStandardItems(ServerLevel level, java.util.UUID csId) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(-3.0e7, level.getMinBuildHeight(), -3.0e7,
                    3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
            if (csId.equals(DiplomacyManager.stolenStandardTarget(item.getItem()))) item.discard();
        }
    }

    public static void tickAll(MinecraftServer server) {
        if (!CityStateManager.enabled()) return;
        ServerLevel level = server.overworld();
        if (level.getGameTime() % WAR_TICK_INTERVAL != 0) return;
        CityStateData data = CityStateData.get(level);
        if (data.all().isEmpty()) return;
        long now = level.getGameTime();
        for (CityState cs : data.all()) {
            boolean anyActive = false;
            for (Map.Entry<UUID, CityStateWar> e : new HashMap<>(cs.wars).entrySet()) {
                CityStateWar w = e.getValue();
                Settlement s = SettlementData.get(level).getById(e.getKey());
                if (w.pendingTicks > 0) {
                    w.pendingTicks -= WAR_TICK_INTERVAL;
                    if (w.pendingTicks <= 0) {
                        w.pendingTicks = 0;
                        w.active = true;
                        w.startedAt = now;
                        if (s != null) {
                            announce(server, s, Component.translatable("bannerbound.citystate.war.started",
                                cityName(cs)).withStyle(ChatFormatting.RED));
                            DiplomacyManager.broadcastDiplomacyState(server, s);
                        }
                    }
                    data.setDirty();
                }
                if (w.active) anyActive = true;
                if (w.capturedAt > 0 && now - w.capturedAt >= CAPTURE_TIMEOUT && s != null) {
                    resolveCapture(server, s, cs, DiplomacyActionPayload.RAZE);
                }
            }
            if (anyActive) tickGarrison(level, cs, now);
            else discardGarrison(level, cs);
        }
        reconcileStandards(level);
    }

    private static void tickGarrison(ServerLevel level, CityState cs, long now) {
        if (nearestPlayerHorizSq(level, cs.center) > GARRISON_DIST_SQ) {
            discardGarrison(level, cs); // off-screen discard; not counted as killed (no attrition)
            return;
        }
        Garrison g = GARRISONS.computeIfAbsent(cs.id, k -> new Garrison());
        g.ids.removeIf(u -> {
            Entity e = level.getEntity(u);
            if (e == null || !e.isAlive()) { g.killed++; return true; }
            return false;
        });
        int cap = mercCap(cs);
        if (g.killed > 0 && now - g.lastRecruitTick >= MERC_RESPAWN_TICKS) {
            g.killed--;
            g.lastRecruitTick = now;
        }
        int target = Math.max(0, cap - g.killed);
        while (g.ids.size() < target) {
            MercenaryEntity m = spawnMercenary(level, cs);
            if (m == null) break;
            g.ids.add(m.getUUID());
        }
    }

    private static void discardGarrison(ServerLevel level, CityState cs) {
        Garrison g = GARRISONS.get(cs.id);
        if (g == null) return;
        for (UUID u : g.ids) {
            Entity e = level.getEntity(u);
            if (e != null) e.discard();
        }
        g.ids.clear();
    }

    private static void disbandGarrisonIfNoWars(CityState cs) {
        boolean anyWar = false;
        for (CityStateWar w : cs.wars.values()) {
            if (w.active || w.pendingTicks > 0 || w.capturedAt > 0) { anyWar = true; break; }
        }
        if (!anyWar) GARRISONS.remove(cs.id);
    }

    private static int mercCap(CityState cs) {
        int cap = 3 + (int) (cs.believedPop * (0.15 + 0.10 * cs.prosperity) * cs.difficulty.factor());
        return Math.max(3, Math.min(16, cap));
    }

    private static MercenaryEntity spawnMercenary(ServerLevel level, CityState cs) {
        MercenaryEntity m = BannerboundCore.MERCENARY.get().create(level);
        if (m == null) return null;
        RandomSource rng = level.getRandom();
        Era era = BarbarianTech.techEra(cs.knownTech);
        BarbarianCapability cap = BarbarianTech.memberCapability(cs.knownTech, rng);
        CitizenGender g = rng.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        m.initializeCitizen(null, CitizenNameLoader.randomName(rng, era, g), g, era, ChatFormatting.DARK_RED);
        m.markSimulated();
        double ang = rng.nextDouble() * Math.PI * 2.0;
        double r = 3.0 + rng.nextDouble() * 5.0;
        int px = cs.center.getX() + (int) Math.round(Math.cos(ang) * r);
        int pz = cs.center.getZ() + (int) Math.round(Math.sin(ang) * r);
        int py = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
        m.moveTo(px + 0.5, py, pz + 0.5, rng.nextFloat() * 360.0F, 0.0F);
        if (!level.addFreshEntity(m)) return null;
        String meleeId = cap.meleeWeaponItem().isEmpty() ? cap.weaponItem() : cap.meleeWeaponItem();
        Item melee = meleeId.isEmpty() ? Items.AIR
            : BuiltInRegistries.ITEM.get(ResourceLocation.parse(meleeId));
        m.markMercenary(cs.center, cs.id, cap.damage(), cap.attackSpeed(), melee);
        return m;
    }

    private static double nearestPlayerHorizSq(ServerLevel level, BlockPos center) {
        double best = Double.MAX_VALUE;
        double cx = center.getX() + 0.5, cz = center.getZ() + 0.5;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = cx - p.getX(), dz = cz - p.getZ();
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    private static Component cityName(CityState cs) {
        return Component.literal(cs.name).withStyle(ChatFormatting.AQUA);
    }

    private static void announce(MinecraftServer server, Settlement s, Component msg) {
        ServerLevel level = server.overworld();
        for (ServerPlayer p : level.players()) {
            if (s.members().contains(p.getUUID())) p.displayClientMessage(msg, false);
        }
    }

    public static boolean forceWarNearest(ServerLevel level, ServerPlayer player) {
        Settlement s = SettlementData.get(level).getByPlayer(player.getUUID());
        if (s == null) return false;
        CityStateData data = CityStateData.get(level);
        CityState nearest = null;
        double best = Double.MAX_VALUE;
        for (CityState cs : data.all()) {
            if (!cs.discoveredBy.contains(s.id())) continue;
            double d = cs.center.distSqr(player.blockPosition());
            if (d < best) { best = d; nearest = cs; }
        }
        if (nearest == null) return false;
        CityStateWar w = nearest.getOrCreateWar(s.id());
        w.pendingTicks = 0;
        w.active = true;
        w.capturedAt = 0;
        w.startedAt = level.getGameTime();
        data.setDirty();
        DiplomacyManager.broadcastDiplomacyState(level.getServer(), s);
        return true;
    }
}
