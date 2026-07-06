package com.bannerbound.core.event;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.ChunkProtection;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Claim protection for the vanilla mechanics FactionEvents doesn't cover: explosions,
 * container/redstone/door interaction, farmland trampling, piston pushes across claim borders,
 * and fluid flow into claims. Same conventions as FactionEvents: overworld-keyed
 * {@link SettlementData}, foreignness via {@link ChunkProtection#isProtected}, op bypass via
 * {@link ChunkProtection#shouldBypass}, red denial messages.
 *
 * Per-hook rules: explosions strip affected block positions inside foreign claims from the block
 * list but still damage entities (walls protected, defenders not); sourceless blasts spare every
 * claimed chunk. Containers, redstone controls, and gated doors are members-only inside a claim.
 * Pistons may not move blocks across a claim border - base, moved, destination, and destroyed
 * positions must all share one chunk owner (chunk-owner lookups only, no membership resolution or
 * block scans). Fluids may not flow-place into a claim from land the owner doesn't hold.
 *
 * Doors/trapdoors/fence gates are gated in {@link #isGatedDoor}, kept separate from containers and
 * redstone controls so it stays easy to relax for visiting traders/allies without touching the
 * priority protections.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ClaimProtectionEvents {
    private ClaimProtectionEvents() {
    }

    private static SettlementData data(ServerLevel level) {
        return SettlementData.get(level.getServer().overworld());
    }

    private static UUID ownerId(SettlementData data, BlockPos pos) {
        Settlement owner = data.getByChunk(new ChunkPos(pos).toLong());
        return owner == null ? null : owner.id();
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SettlementData data = data(level);
        Entity source = event.getExplosion().getIndirectSourceEntity();
        ServerPlayer player = source instanceof ServerPlayer sp ? sp : null;
        if (player != null && ChunkProtection.shouldBypass(player)) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> {
            ChunkPos chunk = new ChunkPos(pos);
            if (player != null) {
                return ChunkProtection.isProtected(data, chunk, player.getUUID());
            }
            return data.getByChunk(chunk.toLong()) != null;
        });
    }

    private static boolean isContainer(Level level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container || blockEntity instanceof MenuProvider;
    }

    private static boolean isRedstoneControl(BlockState state) {
        Block block = state.getBlock();
        // Deliberately NOT pressure plates / tripwires: those fire from ordinary movement.
        return block instanceof LeverBlock || block instanceof ButtonBlock
            || block instanceof DiodeBlock;
    }

    private static boolean isGatedDoor(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!isContainer(level, pos) && !isRedstoneControl(state) && !isGatedDoor(state)) {
            return;
        }
        if (ChunkProtection.shouldBypass(player)) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        ChunkPos chunk = new ChunkPos(pos);
        if (!ChunkProtection.isProtected(data, chunk, player.getUUID())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        Settlement owner = data.getByChunk(chunk.toLong());
        player.sendSystemMessage(Component.translatable(
            "bannerbound.protection.cannot_open", owner.factionName())
            .withStyle(ChatFormatting.RED));
    }

    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (ChunkProtection.shouldBypass(player)) {
            return;
        }
        if (ChunkProtection.isProtected(data(level), new ChunkPos(event.getPos()), player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPistonPre(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SettlementData data = data(level);
        UUID baseOwner = ownerId(data, event.getPos());
        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) {
            return;
        }
        var pushDir = resolver.getPushDirection();
        for (BlockPos moved : resolver.getToPush()) {
            if (!java.util.Objects.equals(baseOwner, ownerId(data, moved))
                    || !java.util.Objects.equals(baseOwner, ownerId(data, moved.relative(pushDir)))) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos destroyed : resolver.getToDestroy()) {
            if (!java.util.Objects.equals(baseOwner, ownerId(data, destroyed))) {
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SettlementData data = data(level);
        UUID targetOwner = ownerId(data, event.getPos());
        if (targetOwner == null) {
            return;
        }
        if (!java.util.Objects.equals(targetOwner, ownerId(data, event.getLiquidPos()))) {
            event.setCanceled(true);
        }
    }
}
