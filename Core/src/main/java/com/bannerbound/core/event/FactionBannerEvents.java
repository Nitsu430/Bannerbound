package com.bannerbound.core.event;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.FactionBanner;
import com.bannerbound.core.api.settlement.Outpost;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * World-event side of the FactionBanner system, driving four flows off vanilla block/craft/tick
 * events. Registered on the mod event bus; every handler is server-side.
 *
 * Break tracking: a player mining THE registered banner is the one case with a known culprit, so it
 * gets the nuanced toll (member = quiet relocation note, anyone/anything else = full alarm) via
 * FactionBanner.lose. A non-member who is not at war and not op is blocked like any protected block;
 * a war enemy converts it into a stolen standard instead; op-level 2+ falls through to lose(). EVERY
 * other way a banner can vanish (support knocked out, piston, water, /setblock, command block, another
 * mod, explosion) fires no break event, so it is caught cause-agnostically by the once-a-second sweep
 * (SWEEP_INTERVAL ticks). Only loaded positions are judged; an unloaded banner is presumed standing.
 *
 * Placement tracking: a member placing any banner inside their own territory while the faction banner
 * is down raises it as THE banner (this is also the relocation flow -- break yours, carry it, plant it
 * again). validate() runs first so a silently-vanished registration does not block the replacement.
 *
 * Outposts: a faction banner planted on an UNCLAIMED working-claim chunk is an outpost; breaking the
 * banner that ESTABLISHED it drops the working claim (conquest v1, since the banner sits on
 * unprotected land). onOutpostBannerBroken is disjoint from onBannerBroken by chunk state (unclaimed
 * working claim vs fully claimed territory), so exactly one handler ever acts on a given banner; only
 * the recorded establishing banner counts (legacy claims have no recorded pos, so their lone banner
 * falls through, which is correct). Empty-hand right-click opens the outpost screen ("place then
 * confirm": a planted banner is always plain decoration until turned into an outpost from that screen).
 *
 * Craft conversion: a plain (pattern-less) banner crafted while standing in your own settlement comes
 * out in the faction color; loom-designed banners (non-empty patterns) are deliberate art and are
 * never touched. The swap is queued and applied one tick later on ServerTickEvent.Post because the
 * crafted stack is not yet in the inventory while ItemCraftedEvent fires (mid-click) and the item TYPE
 * cannot be mutated in place. Stack identity is lost to merging, so matching plain stacks are converted
 * up to the crafted count -- over-matching only ever hits identical plain banners. Cursor is scanned
 * before the inventory (normal result pickup vs shift-click).
 */
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundCore.MODID)
public final class FactionBannerEvents {

    private FactionBannerEvents() {}

    @SubscribeEvent
    public static void onBannerBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!FactionBanner.isBanner(event.getState())) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement owner = data.getByChunk(new ChunkPos(event.getPos()).toLong());
        if (owner == null || !event.getPos().equals(owner.bannerPos())) return;
        Player breaker = event.getPlayer();
        boolean member = breaker != null && owner.members().contains(breaker.getUUID());
        String name = breaker != null ? breaker.getGameProfile().getName() : "";
        if (member) {
            if (!com.bannerbound.core.api.settlement.DiplomacyManager.canOwnerBreakStandard(data, owner)) {
                event.setCanceled(true);
                breaker.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.diplomacy.standard.locked").withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
            FactionBanner.lose(level, owner, event.getPos(), true, name);
            return;
        }
        if (breaker instanceof ServerPlayer sp) {
            Settlement breakerSettlement = data.getByPlayer(sp.getUUID());
            if (breakerSettlement != null && com.bannerbound.core.api.settlement.DiplomacyManager
                    .isActiveWarEnemy(data, breakerSettlement.id(), owner.id())) {
                event.setCanceled(true);
                level.removeBlock(event.getPos(), false);
                com.bannerbound.core.api.settlement.DiplomacyManager.createStolenStandard(
                    level, owner, event.getPos(), sp, breakerSettlement, name);
                return;
            }
            com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                level.getServer(), breakerSettlement, owner, "standard");
            if (!com.bannerbound.core.api.settlement.ChunkProtection.shouldBypass(sp)) {
                event.setCanceled(true);
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.protection.cannot_break", owner.factionName())
                    .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
        }
        FactionBanner.lose(level, owner, event.getPos(), false, name);
    }

    @SubscribeEvent
    public static void onBannerPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!FactionBanner.isBanner(event.getPlacedBlock())) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) return;
        Settlement chunkOwner = data.getByChunk(new ChunkPos(event.getPos()).toLong());
        if (chunkOwner == null || !chunkOwner.id().equals(mine.id())) return;
        FactionBanner.validate(level, mine);
        if (mine.hasFactionBanner()) return;
        if (com.bannerbound.core.api.settlement.DiplomacyManager.hasStolenOrCapturedStandard(
                data, mine.id())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "bannerbound.diplomacy.standard.cannot_reraise").withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        if (!com.bannerbound.core.api.settlement.DiplomacyManager.isPublicStandardValidAt(
                level, mine, event.getPos())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "bannerbound.banner.required").withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        FactionBanner.raise(level, mine, event.getPos());
    }

    @SubscribeEvent
    public static void onOutpostBannerBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!FactionBanner.isBanner(event.getState())) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        ChunkPos cp = new ChunkPos(event.getPos());
        Settlement owner = data.getByWorkingClaim(cp.toLong());
        if (owner == null) return;
        BlockPos recorded = owner.outpostBannerPos(cp.toLong());
        if (recorded != null && !recorded.equals(event.getPos())) return;
        Player breaker = event.getPlayer();
        boolean member = breaker != null && owner.members().contains(breaker.getUUID());
        if (breaker instanceof ServerPlayer sp) {
            if (member) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.outpost.dismantled").withStyle(net.minecraft.ChatFormatting.YELLOW));
            } else {
                com.bannerbound.core.api.settlement.DiplomacyManager.discoverFromContact(
                    level.getServer(), data.getByPlayer(sp.getUUID()), owner, "outpost");
            }
        }
        Outpost.loseOutpost(level, owner, event.getPos(), member);
    }

    @SubscribeEvent
    public static void onBannerRightClicked(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).isEmpty()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        net.minecraft.core.BlockPos pos = event.getPos();
        if (!FactionBanner.isBanner(level.getBlockState(pos))) return;
        SettlementData data = SettlementData.get(level.getServer().overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) return;
        ChunkPos cp = new ChunkPos(pos);
        if (data.getByChunk(cp.toLong()) != null) return;
        Settlement workOwner = data.getByWorkingClaim(cp.toLong());
        if (workOwner != null) {
            if (workOwner.id().equals(mine.id())) {
                Outpost.openScreen(level, player, pos);
            } else {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "bannerbound.outpost.belongs_to", workOwner.factionName())
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            }
            event.setCanceled(true);
            return;
        }
        if (!ResearchManager.hasFlag(mine, Outpost.FLAG_OUTPOST)) return;
        if (!Outpost.withinRange(mine, cp)) return;
        Outpost.openEstablishScreen(level, player, pos);
        event.setCanceled(true);
    }

    private record PendingConvert(UUID playerId, Item crafted, int count) {}

    private static final Queue<PendingConvert> PENDING = new ConcurrentLinkedQueue<>();

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack crafted = event.getCrafting();
        if (!(crafted.getItem() instanceof BannerItem) || crafted.isEmpty()) return;
        if (hasPatterns(crafted)) return;
        SettlementData data = SettlementData.get(player.server.overworld());
        Settlement mine = data.getByPlayer(player.getUUID());
        if (mine == null) return;
        Settlement here = data.getByChunk(new ChunkPos(player.blockPosition()).toLong());
        if (here == null || !here.id().equals(mine.id())) return;
        if (crafted.getItem() == FactionBanner.itemFor(mine.color())
                && mine.bannerDesign().isEmpty()) {
            return;
        }
        PENDING.add(new PendingConvert(player.getUUID(), crafted.getItem(), crafted.getCount()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        convertCraftedBanners(event);
        sweepBannerPositions(event);
    }

    private static void convertCraftedBanners(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        PendingConvert pending;
        while ((pending = PENDING.poll()) != null) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(pending.playerId());
            if (player == null) continue;
            SettlementData data = SettlementData.get(event.getServer().overworld());
            Settlement mine = data.getByPlayer(player.getUUID());
            if (mine == null) continue;
            int remaining = pending.count();
            ItemStack carried = player.containerMenu.getCarried();
            if (isConvertible(carried, pending.crafted())) {
                player.containerMenu.setCarried(FactionBanner.designedItem(
                    mine, event.getServer().registryAccess(), carried.getCount()));
                remaining -= carried.getCount();
            }
            for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!isConvertible(stack, pending.crafted())) continue;
                player.getInventory().setItem(slot, FactionBanner.designedItem(
                    mine, event.getServer().registryAccess(), stack.getCount()));
                remaining -= stack.getCount();
            }
            player.containerMenu.broadcastChanges();
        }
    }

    private static final int SWEEP_INTERVAL = 20;
    private static int sweepTick;

    private static void sweepBannerPositions(ServerTickEvent.Post event) {
        if (++sweepTick < SWEEP_INTERVAL) return;
        sweepTick = 0;
        ServerLevel overworld = event.getServer().overworld();
        SettlementData data = SettlementData.get(overworld);
        for (Settlement settlement : data.all()) {
            if (settlement.hasFactionBanner()) {
                FactionBanner.validate(overworld, settlement);
            }
            Outpost.validateOutposts(overworld, settlement);
        }
    }

    private static boolean isConvertible(ItemStack stack, Item craftedItem) {
        return !stack.isEmpty() && stack.getItem() == craftedItem && !hasPatterns(stack);
    }

    private static boolean hasPatterns(ItemStack stack) {
        BannerPatternLayers layers = stack.get(DataComponents.BANNER_PATTERNS);
        return layers != null && !layers.layers().isEmpty();
    }
}
