package com.bannerbound.core.api.research;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.data.DropOverrideLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Shared chokepoint deciding whether an individual dropped stack may reach the world or a
 * worker's drop-off, given the civ that caused the drop. Per stack: a never_drop override
 * (DropOverrideLoader, optionally scoped to the broken-block / killed-entity source id)
 * always strips it; an always_drop override always keeps it; otherwise it survives only if
 * the settlement knows the item ({@link ItemKnowledge}). A null settlement means no civ
 * context, so only globally-known starting items pass. Both the player-facing drop events
 * ({@link com.bannerbound.core.event.DropGatingEvents}) and the worker collection sites
 * (forester / digger / fisher, which compute drops via Block.getDrops and never fire those
 * events) route through here. {@link #settlementOf} resolves the owning civ from the causing
 * entity, following a projectile to its owner; wild mobs and settlement-less players yield
 * null.
 */
public final class SettlementDropFilter {
    private SettlementDropFilter() {
    }

    public static boolean shouldDrop(@Nullable Settlement settlement, @Nullable ResourceLocation sourceId,
                                     ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String source = sourceId == null ? null : sourceId.toString();
        DropOverrideLoader.Decision decision = itemId == null
            ? DropOverrideLoader.Decision.DEFAULT
            : DropOverrideLoader.decide(itemId.toString(), source);
        return switch (decision) {
            case NEVER_DROP -> false;
            case ALWAYS_DROP -> true;
            case DEFAULT -> ItemKnowledge.isKnown(settlement, stack.getItem());
        };
    }

    public static void filterStacks(@Nullable Settlement settlement, @Nullable ResourceLocation sourceId,
                                    List<ItemStack> drops) {
        drops.removeIf(stack -> !shouldDrop(settlement, sourceId, stack));
    }

    public static void filterEntities(@Nullable Settlement settlement, @Nullable ResourceLocation sourceId,
                                      java.util.Collection<ItemEntity> drops) {
        drops.removeIf(item -> !shouldDrop(settlement, sourceId, item.getItem()));
    }

    @Nullable
    public static Settlement settlementOf(@Nullable Entity entity) {
        if (entity instanceof Projectile projectile && projectile.getOwner() != null) {
            return settlementOf(projectile.getOwner());
        }
        if (entity instanceof CitizenEntity citizen) {
            return citizen.getSettlement();
        }
        if (entity instanceof ServerPlayer player) {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return null;
            }
            try {
                return SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }
}
