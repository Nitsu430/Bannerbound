package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.TownHallFoodDeposits;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Converts a shift-right-click with food on the settlement's own town hall into a food deposit.
 * Fires at EventPriority.HIGH for two ordering reasons: it must beat vanilla's sneak-skip-block-use
 * rule (which would route the click to the food's use() = eating and silently lose the deposit),
 * and it must beat FactionEvents.onCampfireRightClick (the campfire-promote flow) -- when the
 * campfire is already this player's town hall and they hold food, this handler takes precedence.
 *
 * The town hall is a vanilla CampfireBlock at Settlement.townHallPos(); the deposit math runs
 * through TownHallFoodDeposits.tryDepositFood, which is block-agnostic and only requires the click
 * pos to match the settlement's town hall pos. A shift-click-food on someone else's campfire (or a
 * non-food item) falls through untouched so campfire promote / vanilla eating still work.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class TownHallFoodDepositEvents {
    private TownHallFoodDepositEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        float value = com.bannerbound.core.api.settlement.data.FoodValueLoader.base(stack.getItem());
        if (value <= 0f) return;

        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        if (level.isClientSide) {
            // Client: mirror the server's cancel so the eat-animation never starts on a deposit click.
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!(level instanceof ServerLevel sl)) return;
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) return;

        SettlementData data = SettlementData.get(server.overworld());
        Settlement playerSettlement = data.getByPlayer(serverPlayer.getUUID());
        if (playerSettlement == null) return;
        BlockPos townHallPos = playerSettlement.townHallPos();
        if (townHallPos == null || !townHallPos.equals(pos)) return;
        Settlement chunkOwner = data.getByChunk(new ChunkPos(pos).toLong());
        if (chunkOwner == null || !chunkOwner.id().equals(playerSettlement.id())) return;

        if (TownHallFoodDeposits.tryDepositFood(serverPlayer, sl, pos, stack)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }
}
