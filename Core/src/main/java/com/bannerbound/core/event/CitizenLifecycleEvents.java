package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Server-side reaction to citizen death. When a {@link CitizenEntity} dies it is pruned from its
 * settlement roster, then a cascade of eager cleanups run and population state is rebroadcast so
 * the next-citizen cost recomputes from the lower population.
 *
 * Cleanup steps, in a deliberate order: apply death thoughts to survivors (tiered mourning), forget
 * the dead UUID from every survivor's Relationships map, release the bed if the citizen died asleep,
 * evict from any Home.residents list, then broadcast the vanilla death message to members.
 *
 * Design notes / gotchas:
 * - A trade courier can die ANYWHERE (wilderness, foreign land), so its cargo spill / deal-leg
 *   failure must fire before the settlement-roster guards below can early-return.
 * - Death thoughts read each survivor's relationship entry for the deceased, so they MUST run
 *   before the forget pass wipes those entries. Order-dependent.
 * - Mourning is tiered (family/friend-for-life/close-friend/friend/generic); negative-tier
 *   survivors (rivals/enemies) get nothing. STRANGERS maps to a generic "died_recently" so anyone
 *   who at least met the deceased still mourns lightly. The dead citizen's bare name is captured
 *   into the thought because the entity is discarded and later UUID resolution would return null.
 * - Use raw getCitizenName() (no gender/pregnancy PUA glyph): getCustomName().getString() flattens
 *   the styled component and the glyph codepoint renders as tofu once wrapped back into a literal.
 * - Bed release and its SleepGoal reservation clear are needed because death short-circuits the
 *   goal lifecycle (entity removed before the next tick), so SleepGoal.stop() may never run and the
 *   bed would stay permanently OCCUPIED / reserved.
 * - Forgetting stale UUIDs is cheap (O(N) per death) and prevents silent soft bugs once Lover /
 *   Best Friend overflow slots exist.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CitizenLifecycleEvents {
    private CitizenLifecycleEvents() {
    }

    @SubscribeEvent
    public static void onCitizenDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity citizen)) {
            return;
        }
        if (!(citizen.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Couriers die off-territory: spill cargo before the roster guards below can early-return.
        if (citizen.isOnTradeJourney()) {
            com.bannerbound.core.trade.TradeCourierManager.onCourierDied(serverLevel, citizen);
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null || citizen.getSettlementId() == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getById(citizen.getSettlementId());
        if (settlement == null) {
            return;
        }
        if (settlement.removeCitizen(citizen.getUUID())) {
            // Must precede forget: it reads the relationship entries forget would wipe.
            applyDeathThoughtsToSurvivors(serverLevel, settlement, citizen);
            forgetDeadCitizenInRelationships(serverLevel, settlement, citizen.getUUID());
            releaseBedIfSleeping(serverLevel, citizen);
            evictDeadCitizenFromHome(settlement, citizen.getUUID());
            data.setDirty();
            ImmigrationManager.broadcastState(server, settlement);
            broadcastDeathMessage(server, settlement, citizen, event.getSource());
        }
    }

    private static void applyDeathThoughtsToSurvivors(ServerLevel sl, Settlement settlement,
                                                       CitizenEntity dead) {
        long now = sl.getGameTime();
        UUID deadId = dead.getUUID();
        String deadName = dead.getCitizenName() != null
            ? dead.getCitizenName()
            : "Someone";
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (c.entityId().equals(deadId)) continue;
            if (!(sl.getEntity(c.entityId()) instanceof CitizenEntity survivor)) continue;
            com.bannerbound.core.social.Relationship rel = survivor.getRelationships().get(deadId);
            com.bannerbound.core.social.ThoughtKind kind =
                com.bannerbound.core.social.ThoughtKind.deathThoughtFor(rel.tier());
            if (kind == null) continue;
            survivor.getThoughts().add(kind, deadId, deadName, now, sl.random);
            survivor.recomputeHappiness();
        }
    }

    private static void releaseBedIfSleeping(ServerLevel sl, CitizenEntity citizen) {
        if (!citizen.isSleeping()) return;
        citizen.getSleepingPos().ifPresent(pos -> {
            net.minecraft.world.level.block.state.BlockState bs = sl.getBlockState(pos);
            if (bs.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && bs.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                sl.setBlock(pos,
                    bs.setValue(net.minecraft.world.level.block.BedBlock.OCCUPIED, false),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
            com.bannerbound.core.entity.SleepGoal.releaseReservation(pos);
        });
    }

    private static void evictDeadCitizenFromHome(Settlement settlement, UUID dead) {
        com.bannerbound.core.api.settlement.Home home = settlement.getHomeFor(dead);
        if (home != null) home.removeResident(dead);
    }

    private static void forgetDeadCitizenInRelationships(ServerLevel sl, Settlement settlement, UUID dead) {
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (!(sl.getEntity(c.entityId()) instanceof CitizenEntity other)) continue;
            other.getRelationships().forget(dead);
        }
    }

    private static void broadcastDeathMessage(MinecraftServer server, Settlement settlement,
                                              CitizenEntity citizen, DamageSource source) {
        Component vanilla = source.getLocalizedDeathMessage(citizen);
        MutableComponent line = Component.empty()
            .append(vanilla)
            .withStyle(ChatFormatting.GRAY);
        for (UUID memberId : settlement.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                m.sendSystemMessage(line);
            }
        }
    }
}
