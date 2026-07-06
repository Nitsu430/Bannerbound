package com.bannerbound.core.citystate;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Claim protection for AI city-states -- the parallel to FactionEvents' settlement protection
 * (CITY_STATES plan). Players cannot break/place blocks, harm entities, fire projectiles, or open
 * containers inside a city-state's claimed territory UNLESS their settlement is at active war with it
 * (then it is free to sack), exactly like enemy settlements. City-state chunks live in a separate store
 * (CityStateData) that FactionEvents' settlement protection does not cover, so this fills that gap.
 * protectingAt is the shared gate every handler runs: it returns null (player may act) when the system
 * is disabled -- so stale claims protect nothing -- on op bypass (ChunkProtection.shouldBypass), when
 * no city-state owns the chunk, or when the player's settlement is at active war with it.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CityStateProtection {
    private CityStateProtection() {
    }

    private static CityState protectingAt(MinecraftServer server, ServerPlayer player, BlockPos pos) {
        if (!CityStateManager.enabled()) return null;
        if (server == null || ChunkProtection.shouldBypass(player)) return null;
        ServerLevel overworld = server.overworld();
        CityState cs = CityStateData.get(overworld).getByChunk(new ChunkPos(pos).toLong());
        if (cs == null) return null;
        Settlement mine = SettlementData.get(overworld).getByPlayer(player.getUUID());
        if (mine != null && cs.isActiveEnemy(mine.id())) return null;
        return cs;
    }

    private static void deny(ServerPlayer player, CityState cs, String key) {
        player.sendSystemMessage(Component.translatable(key, cs.name).withStyle(ChatFormatting.RED));
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        CityState cs = protectingAt(player.getServer(), player, event.getPos());
        if (cs != null) {
            event.setCanceled(true);
            deny(player, cs, "bannerbound.protection.cannot_break");
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CityState cs = protectingAt(player.getServer(), player, event.getPos());
        if (cs != null) {
            event.setCanceled(true);
            deny(player, cs, "bannerbound.protection.cannot_place");
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isShiftKeyDown()) return; // sneaking = placing against a block; handled by place event
        if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof Container)) return;
        CityState cs = protectingAt(player.getServer(), player, event.getPos());
        if (cs != null) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            deny(player, cs, "bannerbound.protection.cannot_open");
        }
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        Entity source = event.getSource().getEntity();
        if (!(source instanceof ServerPlayer player)) return;
        LivingEntity victim = event.getEntity();
        if (victim == player) return;
        CityState cs = protectingAt(player.getServer(), player, victim.blockPosition());
        if (cs != null) {
            event.setCanceled(true);
            deny(player, cs, "bannerbound.protection.cannot_attack");
        }
    }

    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile.getOwner() instanceof ServerPlayer player)) return;
        HitResult hit = event.getRayTraceResult();
        BlockPos pos;
        if (hit instanceof BlockHitResult bhr) pos = bhr.getBlockPos();
        else if (hit instanceof EntityHitResult ehr) pos = ehr.getEntity().blockPosition();
        else return;
        CityState cs = protectingAt(player.getServer(), player, pos);
        if (cs != null) {
            event.setCanceled(true);
            projectile.discard();
            deny(player, cs, "bannerbound.protection.projectile_blocked");
        }
    }
}
