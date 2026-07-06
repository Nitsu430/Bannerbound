package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

/**
 * Server-side helper for the shift-right-click food deposit at a settlement's town-hall (campfire)
 * block. tryDepositFood validates ownership (the clicker's settlement must own the clicked chunk
 * and the click must land on the town-hall pos), converts the held stack to larder food value, tops
 * the settlement food store up to its cap, plays feedback, and broadcasts immigration state. It
 * returns true whenever the click is consumed -- INCLUDING rejected deposits (wrong owner, store
 * full, non-larder item) -- so the interaction never falls through to vanilla eating; only a
 * non-food item or a click that is not the town-hall block returns false to let vanilla proceed.
 */
@ApiStatus.Internal
public final class TownHallFoodDeposits {
    private TownHallFoodDeposits() {
    }

    public static boolean tryDepositFood(ServerPlayer serverPlayer, ServerLevel level,
                                         BlockPos pos, ItemStack stack) {
        float baseValue = com.bannerbound.core.api.settlement.data.FoodValueLoader.base(stack.getItem());
        if (baseValue <= 0f) return false;
        if (!com.bannerbound.core.api.settlement.food.LarderHooks.counts(stack, level)) {
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.townhall.food_deposit.rejected")
                    .withStyle(ChatFormatting.RED),
                true);
            return true;
        }
        MinecraftServer server = serverPlayer.getServer();
        if (server == null) return false;

        SettlementData data = SettlementData.get(server.overworld());
        Settlement chunkOwner = data.getByChunk(new ChunkPos(pos).toLong());
        Settlement playerSettlement = data.getByPlayer(serverPlayer.getUUID());
        if (chunkOwner == null || playerSettlement == null
                || !chunkOwner.id().equals(playerSettlement.id())) {
            serverPlayer.sendSystemMessage(Component.translatable("bannerbound.townhall.food_deposit.not_yours")
                .withStyle(ChatFormatting.RED));
            return true;
        }

        BlockPos townHallPos = chunkOwner.townHallPos();
        if (townHallPos == null || !townHallPos.equals(pos)) {
            return false;
        }

        float value = com.bannerbound.core.api.settlement.data.FoodValueLoader.effective(
            stack.getItem(), chunkOwner);
        if (value <= 0f) return false;

        double remaining = chunkOwner.foodCap() - chunkOwner.foodStored();
        if (remaining <= 0.0) {
            serverPlayer.displayClientMessage(
                Component.translatable("bannerbound.townhall.food_deposit.full")
                    .withStyle(ChatFormatting.RED),
                true);
            return true;
        }

        int count = stack.getCount();
        int itemsToFit = (int) Math.ceil(remaining / value);
        int itemsConsumed = Math.min(count, itemsToFit);
        double valueAdded = Math.min(itemsConsumed * value, remaining);
        chunkOwner.setFoodStored(chunkOwner.foodStored() + valueAdded);
        data.setDirty();
        ImmigrationManager.broadcastState(server, chunkOwner);

        if (!serverPlayer.getAbilities().instabuild) {
            stack.shrink(itemsConsumed);
        }

        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(),
            SoundSource.BLOCKS, 0.8f, 1.0f);
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 1.2;
        double cz = pos.getZ() + 0.5;
        level.sendParticles(ParticleTypes.FLAME, cx, cy, cz, 12, 0.3, 0.2, 0.3, 0.02);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, cx, cy, cz, 12, 0.3, 0.2, 0.3, 0.02);

        serverPlayer.displayClientMessage(Component.translatable(
                "bannerbound.townhall.food_deposit.success", String.format("%.2f", valueAdded))
            .withStyle(ChatFormatting.GREEN), true);
        return true;
    }
}
