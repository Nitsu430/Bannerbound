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
 * Merged vanilla-content gate event handlers (storage, villager trading, wandering trader spawns).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class VanillaGates {

    /*
     * Gates opening vanilla chests and barrels behind research, so an early settlement relies on
     * baskets / the stockpile (unlocked by {@code storage_logistics}) and can't crack open structure
     * loot chests for free. Only active when vanilla content is stripped
     * ({@link VanillaContentState#isEnabled()} is false — always so under Antiquity).
     *
     * <ul>
     *   <li>Barrel → {@code bannerbound.unlock.barrel} (the {@code barrel_making} research, Antiquity era).</li>
     *   <li>Chest / trapped chest → {@code bannerbound.unlock.chest} (the {@code joinery} research,
     *       Medieval, iron-gated).</li>
     * </ul>
     *
     * <p>Mirrors {@code VanillaGates}: per-settlement flag check, unsettled players are gated too.
     * The block stays breakable/placeable — only the GUI-open gesture is blocked. Sneaking is left
     * alone so a held block can be placed against a chest.
     */
    private static final String FLAG_BARREL = "bannerbound.unlock.barrel";
    private static final String FLAG_CHEST = "bannerbound.unlock.chest";

    private VanillaGates() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown()) return; // sneaking never opens the GUI; allow place-against

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

    /*
     * Vanilla villager trading is permanently disabled in the Antiquity expansion — villages are now AI
     * city-states, traded with via the Town Hall diplomacy tab (after the Bartering research), not by
     * clicking individual villagers (see the CITY_STATES plan §1G). Right-clicking a villager cancels the
     * vanilla trade GUI and gives the villager's "can't trade" reaction (the unhappy head-shake + "no"
     * sound), like clicking a nitwit.
     *
     * <p>Only active when vanilla content is stripped ({@link VanillaContentState#isEnabled()} false —
     * always so under Antiquity). We never touch the villager itself (mod compatibility); we only block
     * the interaction. Name tags / other item interactions are left alone.
     */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getTarget() instanceof Villager villager)) return;
        // Leave name-tag naming alone; only the bare trade interaction is blocked.
        if (event.getItemStack().is(Items.NAME_TAG)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        if (!event.getLevel().isClientSide()) {
            Player player = event.getEntity();
            villager.setUnhappyCounter(40); // the vanilla "can't trade" head-shake / angry puff
            player.level().playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }

    /*
     * Wandering traders (and their trader llamas) no longer appear in the Antiquity expansion — vanilla
     * trading is replaced by the city-state diplomacy/trade system (see the CITY_STATES plan §1G).
     *
     * <p>Cancels them at {@link EntityJoinLevelEvent} rather than a spawn event, because the wandering
     * trader is placed by a {@code CustomSpawner} (not the normal mob-spawn path), and this also evicts
     * any already-saved trader on world reload — mirroring {@code VanillaGates}'s join hook. Only
     * active when vanilla content is stripped ({@link VanillaContentState#isEnabled()} false).
     */
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
