package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Merged vanilla-content gate handlers: three server-side event hooks that strip vanilla storage,
 * villager trading, and wandering-trader spawns out of the Antiquity expansion. Every hook is a no-op
 * unless vanilla content is stripped (VanillaContentState.isEnabled() is false -- always so under
 * Antiquity); read that state, never the raw config.
 *
 * <p>Storage gate (onRightClickBlock): opening vanilla chests and barrels is locked behind research so
 * an early settlement leans on baskets / the stockpile (unlocked by storage_logistics) and can't crack
 * open structure loot chests for free. Barrel -> bannerbound.unlock.barrel (barrel_making, Antiquity
 * era); chest / trapped chest -> bannerbound.unlock.chest (joinery, Medieval, iron-gated). The check is
 * per-settlement and unsettled players are gated too; with no server context we fail open. Only the
 * GUI-open gesture is blocked -- the block stays breakable/placeable, and sneaking is left alone so a
 * held block can be placed against a chest.
 *
 * <p>Villager trading (onEntityInteract): permanently disabled -- villages are AI city-states traded
 * with via the Town Hall diplomacy tab (after the Bartering research), not by clicking individual
 * villagers (CITY_STATES plan section 1G). The bare trade interaction is cancelled and the villager
 * gives its "can't trade" reaction (unhappy head-shake + VILLAGER_NO sound); we never mutate the
 * villager type (mod compatibility) and leave name-tag naming alone.
 *
 * <p>Wandering traders (onEntityJoin): the trader and its trader llamas are cancelled at
 * EntityJoinLevelEvent rather than a spawn event, because the wandering trader is placed by a
 * CustomSpawner off the normal mob-spawn path; the join hook also evicts any already-saved trader on
 * world reload.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class VanillaGates {

    private static final String FLAG_BARREL = "bannerbound.unlock.barrel";
    private static final String FLAG_CHEST = "bannerbound.unlock.chest";

    private VanillaGates() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown()) return;

        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        final String flag;
        if (block == Blocks.BARREL) {
            flag = FLAG_BARREL;
        } else if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            flag = FLAG_CHEST;
        } else {
            return;
        }

        if (settlementHasFlag(player, flag)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        player.displayClientMessage(
            Component.translatable("bannerbound.vanilla.storage_locked").withStyle(ChatFormatting.RED),
            true);
    }

    private static boolean settlementHasFlag(ServerPlayer player, String flag) {
        MinecraftServer server = player.getServer();
        if (server == null) return true; // fail-open if no server context
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, flag);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getTarget() instanceof Villager villager)) return;
        if (event.getItemStack().is(Items.NAME_TAG)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        if (!event.getLevel().isClientSide()) {
            Player player = event.getEntity();
            villager.setUnhappyCounter(40); // 40 ticks = vanilla "can't trade" head-shake duration
            player.level().playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (VanillaContentState.isEnabled()) return;
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        if (e instanceof WanderingTrader || e instanceof TraderLlama) {
            event.setCanceled(true);
        }
    }
}
