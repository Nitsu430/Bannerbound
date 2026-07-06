package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.RopeFenceEvents;
import com.bannerbound.antiquity.SpearFishing;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Antiquity's payload registration, mirroring Core's {@code BannerboundNetwork}; every handler hops
 * to the game thread via {@code context.enqueueWork}. The C2S payloads share one pattern: the client
 * only reports minigame input (fletching / pottery / mortar grind / knapping / carpentry saw /
 * tannery scrape / masonry chisel / stone-anvil cold-hammer steps, plus the empty-hand spear reel,
 * rope-post left-clicks, and workstation ghost-preview clicks - the ghost targets are client-side
 * billboards, so clicks can only arrive as payloads) while the SERVER owns the session, ingredient
 * consumption, quality rolls, and output. CarveCommitPayload exists because a previewed carve block
 * is hidden (air) client-side and can't be ray-clicked, so the use-key press is sent up and the
 * server replays the carve at the anchor (Carves.commit re-fires the real RightClickBlock handler).
 * S2C: ArrowPartsSyncPayload replaces the datapack arrow-part registry on the client so modular
 * arrows render with tooltips; the Open* payloads pop the minigame screens; HammerArmPayload drives
 * the third-person hammer-arm raise; DecoChunkSync/DecoUpdate sync face decorations (full chunk on
 * watch, single-face deltas on edit); OpenArmorerPayload opens the player-designed-armor screen
 * (ARMOR_PLAN.md). GOTCHA (haschannel-false-negative): any S2C payload whose handler touches
 * client-only classes must be registered in BOTH dist branches - real handler on the client, and
 * TYPE + CODEC with a no-op handler on the server (which still needs the codec to encode it);
 * skip the server-side registration and single-player throws.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class AntiquityNetwork {
    private AntiquityNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
            ReelTetherPayload.TYPE,
            ReelTetherPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    SpearFishing.startReel(player);
                }
            }));
        registrar.playToServer(
            RopeFenceActionPayload.TYPE,
            RopeFenceActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    RopeFenceEvents.serverHandle(player, payload.pos());
                }
            }));
        registrar.playToServer(
            FletchingActionPayload.TYPE,
            FletchingActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Fletching.handleAction(player, payload);
                }
            }));
        registrar.playToServer(
            PotteryActionPayload.TYPE,
            PotteryActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Pottery.handleAction(player, payload);
                }
            }));

        registrar.playToServer(
            MortarGrindActionPayload.TYPE,
            MortarGrindActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.MortarGrind.handleAction(player, payload);
                }
            }));

        registrar.playToServer(
            KnappingActionPayload.TYPE,
            KnappingActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Knapping.handleAction(player, payload);
                }
            }));

        registrar.playToServer(
            GhostActionPayload.TYPE,
            GhostActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.GhostWorkstationActions.serverHandle(
                        player, payload.pos(), payload.action());
                }
            }));

        registrar.playToServer(
            CarveCommitPayload.TYPE,
            CarveCommitPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Carves.commit(player, payload.anchor());
                }
            }));

        registrar.playToServer(
            CarpentryActionPayload.TYPE,
            CarpentryActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Carpentry.handleAction(player, payload);
                }
            }));

        registrar.playToServer(
            TanningActionPayload.TYPE,
            TanningActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Tannery.handleAction(player, payload);
                }
            }));

        registrar.playToServer(
            MasonryActionPayload.TYPE,
            MasonryActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Masonry.handleAction(player, payload);
                }
            }));

        registrar.playToServer(
            HammerActionPayload.TYPE,
            HammerActionPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    com.bannerbound.antiquity.Hammer.handleAction(player, payload);
                }
            }));
        // Handler touches only the COMMON ArrowPartRegistry -> safe to register unguarded on both dists.
        registrar.playToClient(
            ArrowPartsSyncPayload.TYPE,
            ArrowPartsSyncPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() ->
                com.bannerbound.antiquity.recipe.ArrowPartRegistry.replace(payload.parts())));
        // Client-only handlers: every payload registered here MUST also be no-op registered in the else branch.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registrar.playToClient(
                OpenFletchingPayload.TYPE,
                OpenFletchingPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.FletchingClientHandler.open(payload)));
            registrar.playToClient(
                OpenPotteryPayload.TYPE,
                OpenPotteryPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.PotteryClientHandler.open(payload)));
            registrar.playToClient(
                OpenCarpentrySawPayload.TYPE,
                OpenCarpentrySawPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.CarpentryClientHandler.open(payload)));
            registrar.playToClient(
                OpenTanningPayload.TYPE,
                OpenTanningPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.TanningClientHandler.open(payload)));
            registrar.playToClient(
                OpenKnappingPayload.TYPE,
                OpenKnappingPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.KnappingClientHandler.open(payload)));
            registrar.playToClient(
                OpenMortarGrindPayload.TYPE,
                OpenMortarGrindPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.MortarGrindClientHandler.open(payload)));
            registrar.playToClient(
                OpenMasonChiselPayload.TYPE,
                OpenMasonChiselPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.MasonryClientHandler.open(payload)));
            registrar.playToClient(
                OpenHammerPayload.TYPE,
                OpenHammerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.HammerClientHandler.open(payload)));
            registrar.playToClient(
                HammerArmPayload.TYPE,
                HammerArmPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.HammerArmState.setActive(payload.player(), payload.active())));
            registrar.playToClient(
                DecoChunkSyncPayload.TYPE,
                DecoChunkSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.DecoClientHandler.applyChunk(payload)));
            registrar.playToClient(
                DecoUpdatePayload.TYPE,
                DecoUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.DecoClientHandler.applyUpdate(payload)));
            registrar.playToClient(
                OpenArmorerPayload.TYPE,
                OpenArmorerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.bannerbound.antiquity.client.ArmorerClientHandler.open(payload)));
        } else {
            // Server dist still registers TYPE + CODEC (no-op handler) or single-player throws at send time.
            registrar.playToClient(
                OpenFletchingPayload.TYPE,
                OpenFletchingPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenPotteryPayload.TYPE,
                OpenPotteryPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenCarpentrySawPayload.TYPE,
                OpenCarpentrySawPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenTanningPayload.TYPE,
                OpenTanningPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenKnappingPayload.TYPE,
                OpenKnappingPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenMortarGrindPayload.TYPE,
                OpenMortarGrindPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenMasonChiselPayload.TYPE,
                OpenMasonChiselPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenHammerPayload.TYPE,
                OpenHammerPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                HammerArmPayload.TYPE,
                HammerArmPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                DecoChunkSyncPayload.TYPE,
                DecoChunkSyncPayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                DecoUpdatePayload.TYPE,
                DecoUpdatePayload.STREAM_CODEC,
                (payload, context) -> { });
            registrar.playToClient(
                OpenArmorerPayload.TYPE,
                OpenArmorerPayload.STREAM_CODEC,
                (payload, context) -> { });
        }
    }
}
