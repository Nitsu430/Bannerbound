package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.research.OreDisguise;
import com.bannerbound.core.api.research.data.OreDisguiseLoader;
import com.bannerbound.core.api.research.ResearchManager;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * Server-side enforcement for disguised ores: an ore a player's settlement hasn't researched looks
 * and behaves like plain rock until unlocked. Three hooks, all keyed on OreDisguiseLoader (server)
 * / ClientOreState (client):
 *
 * - onBlockDrops (server): when a still-hidden ore is broken, clear its real drops/XP and run the
 *   DISGUISE block's loot table instead (stone -> cobblestone, deepslate -> cobbled_deepslate, silk
 *   touch still yields the smooth variant). Running the real loot table with the player's held tool
 *   keeps every vanilla rule (silk touch, fortune) intact.
 * - onHarvestCheck (both sides): report the block as harvestable by whatever tool can mine its
 *   disguise (e.g. wooden pickaxe for stone). CRITICAL -- without this, breaking a disguised ore
 *   with the "wrong" tool silently destroys the block AND skips the whole drop path
 *   (BlockDropsEvent never fires, player gets nothing).
 * - onBreakSpeed (both sides): rescale mining speed to the disguise block by the hardness/divisor
 *   ratio -- this preserves Efficiency, Haste, water/onGround penalties, since digSpeed is
 *   identical for stone and the ore under the same #mineable/pickaxe rule -- then cut to 1/3 speed
 *   so a disguised ore reads as "a slightly tougher rock" rather than suspiciously snappy stone.
 *
 * Together with the client BlockModelShaper mixin, a hidden ore is indistinguishable from regular
 * rock until the settlement researches its reveal flag.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class OreBreakHandler {
    private OreBreakHandler() {
    }

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (event.getLevel() == null || event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getBreaker() instanceof ServerPlayer sp)) {
            return;
        }
        BlockState state = event.getState();
        OreDisguise disguise = OreDisguiseLoader.getDisguiseFor(state.getBlock());
        if (disguise == null) {
            return;
        }
        if (isOreRevealedForPlayer(sp, disguise)) {
            return;
        }

        Block disguiseBlock = resolveBlock(disguise.disguiseId());
        BlockPos pos = event.getPos();
        ServerLevel level = event.getLevel();
        event.getDrops().clear();
        event.setDroppedExperience(0);
        if (disguiseBlock == null || disguiseBlock == Blocks.AIR) {
            return;
        }
        BlockState disguiseState = disguiseBlock.defaultBlockState();

        List<ItemStack> disguiseDrops;
        try {
            disguiseDrops = Block.getDrops(disguiseState, level, pos, null, sp, sp.getMainHandItem());
        } catch (Exception ex) {
            return;
        }
        for (ItemStack stack : disguiseDrops) {
            if (stack.isEmpty()) continue;
            ItemEntity drop = new ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                stack);
            event.getDrops().add(drop);
        }
    }

    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        Player player = event.getEntity();
        BlockState state = event.getTargetBlock();
        Block block = state.getBlock();

        OreDisguise disguise = resolveDisguiseForBoth(player, block);
        if (disguise == null) {
            return;
        }
        if (!isDisguisedForPlayer(player, block, disguise)) {
            return;
        }

        Block disguiseBlock = resolveBlock(disguise.disguiseId());
        if (disguiseBlock == null) {
            return;
        }
        BlockState disguiseState = disguiseBlock.defaultBlockState();
        // Check the held tool directly -- do NOT call canHarvestBlock (would re-fire this event).
        boolean canHarvestDisguise = !disguiseState.requiresCorrectToolForDrops()
            || player.getMainHandItem().isCorrectToolForDrops(disguiseState);
        event.setCanHarvest(canHarvestDisguise);
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        BlockState state = event.getState();
        Block block = state.getBlock();

        OreDisguise disguise = resolveDisguiseForBoth(player, block);
        if (disguise == null) {
            return;
        }
        if (!isDisguisedForPlayer(player, block, disguise)) {
            return;
        }

        Block disguiseBlock = resolveBlock(disguise.disguiseId());
        if (disguiseBlock == null) return;
        BlockState disguiseState = disguiseBlock.defaultBlockState();
        BlockPos pos = event.getPosition().orElse(player.blockPosition());

        float oreHardness = state.getDestroySpeed(player.level(), pos);
        float disguiseHardness = disguiseState.getDestroySpeed(player.level(), pos);
        if (oreHardness <= 0.0f || disguiseHardness <= 0.0f) return;
        ItemStack tool = player.getMainHandItem();
        int oreDivisor = (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state)) ? 30 : 100;
        int disguiseDivisor = (!disguiseState.requiresCorrectToolForDrops()
            || tool.isCorrectToolForDrops(disguiseState)) ? 30 : 100;

        float scale = (oreHardness / disguiseHardness) * ((float) oreDivisor / (float) disguiseDivisor);
        // Feel: cut to 1/3 of raw disguise-block speed so it reads as a tougher rock, not stone.
        scale /= 3f;
        event.setNewSpeed(event.getOriginalSpeed() * scale);
    }

    private static OreDisguise resolveDisguiseForBoth(Player player, Block block) {
        if (player.level().isClientSide()) {
            return com.bannerbound.core.client.ClientOreState.getDisguiseFor(block);
        }
        return OreDisguiseLoader.getDisguiseFor(block);
    }

    private static boolean isDisguisedForPlayer(Player player, Block block, OreDisguise disguise) {
        if (player.level().isClientSide()) {
            return com.bannerbound.core.client.ClientOreState.isCurrentlyDisguised(block);
        }
        if (!(player instanceof ServerPlayer sp)) return false;
        return !isOreRevealedForPlayer(sp, disguise);
    }

    private static boolean isOreRevealedForPlayer(ServerPlayer player, OreDisguise disguise) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement s = data.getByPlayer(player.getUUID());
            return ResearchManager.hasFlag(s, disguise.flag());
        } catch (Exception ex) {
            return false;
        }
    }

    private static Block resolveBlock(String id) {
        try {
            return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        } catch (Exception ex) {
            return null;
        }
    }
}
