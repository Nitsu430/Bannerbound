package com.bannerbound.core;

import com.bannerbound.core.api.settlement.Citizen;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.client.CitizenRenderer;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only mod entry (dist = CLIENT, so this class never loads on a dedicated server and may
 * safely touch client-side code). Registers the NeoForge config screen extension point, the entity
 * renderers, and the Stockpile menu screen; static methods are auto-subscribed via
 * {@code @EventBusSubscriber}. Client setup also extends the vanilla fishing-rod "cast" item
 * predicate so a citizen who is currently fishing renders the bent rod variant.
 * <p>
 * Renderer notes: CitizenRenderer uses vanilla ModelLayers.PLAYER (+ PLAYER_INNER/OUTER_ARMOR),
 * which Mojang pre-registers, so no layer-definition registration is needed here. Barbarians and
 * mercenaries reuse the same renderer - they are distinct logical entity types only, with the same
 * body/model/skin as a citizen.
 */
@Mod(value = BannerboundCore.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public class BannerboundCoreClient {
    public BannerboundCoreClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BannerboundCore.LOGGER.info("HELLO FROM CLIENT SETUP");
        BannerboundCore.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        event.enqueueWork(com.bannerbound.core.client.FishingRodCastOverride::register);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(BannerboundCore.CITIZEN.get(), CitizenRenderer::new);
        event.registerEntityRenderer(BannerboundCore.BARBARIAN.get(),
            ctx -> new CitizenRenderer(ctx));
        event.registerEntityRenderer(BannerboundCore.MERCENARY.get(),
            ctx -> new CitizenRenderer(ctx));
        event.registerEntityRenderer(BannerboundCore.FISHER_BOBBER.get(),
            com.bannerbound.core.client.FisherBobberRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(BannerboundCore.STOCKPILE_MENU.get(),
            com.bannerbound.core.client.StockpileScreen::new);
    }
}
