package com.bannerbound.antiquity;

import com.bannerbound.antiquity.client.BasketRenderer;
import com.bannerbound.antiquity.client.BasketScreen;
import com.bannerbound.antiquity.client.BloomeryModel;
import com.bannerbound.antiquity.client.BloomeryRenderer;
import com.bannerbound.antiquity.client.ChoppingStumpRenderer;
import com.bannerbound.antiquity.client.CraftingStoneRenderer;
import com.bannerbound.antiquity.client.KilnRenderer;
import com.bannerbound.antiquity.client.MortarAndPestleModel;
import com.bannerbound.antiquity.client.MortarAndPestleRenderer;
import com.bannerbound.antiquity.client.RaftModel;
import com.bannerbound.antiquity.client.RaftRenderer;
import com.bannerbound.antiquity.client.RopedPostKnotRenderer;
import com.bannerbound.antiquity.client.RopeFenceGateRenderer;
import com.bannerbound.antiquity.client.RopeFencePostRenderer;
import com.bannerbound.antiquity.client.BloodDropParticle;
import com.bannerbound.antiquity.client.GroundDecalRenderer;
import com.bannerbound.antiquity.client.SpearHandFlipModel;
import com.bannerbound.antiquity.client.SpearProjectileRenderer;
import com.bannerbound.antiquity.client.SpearedFishEntityRenderer;
import com.bannerbound.antiquity.client.StuckSpearLayer;

import java.util.Map;

import com.bannerbound.antiquity.client.model.WormBaitModel;
import com.bannerbound.antiquity.client.model.WormBaitRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import com.bannerbound.antiquity.workshop.MetalworkingItems;

/**
 * Client-only registration hub for the Antiquity mod. The whole class is gated to {@link Dist#CLIENT}
 * via its @Mod/@EventBusSubscriber annotations, so it never loads on a dedicated server and touching
 * client render code here is safe. Each @SubscribeEvent wires one client subsystem into NeoForge:
 * block-entity + entity renderers, particle/shader/GUI-layer registration, item-model property
 * predicates (bow/slingshot draw progress, crucible/grog "filled", blowgun "drawing"), tooltip and
 * menu screens, item-colour tints, and the cold-hammer minigame's camera/FOV/arm feedback.
 *
 * Design notes worth keeping:
 * - ClientDatapackRecipes exists because custom-recipe managers are SERVER-data reload listeners, so
 *   on a remote client their static lists never fill (no server in this JVM) and JEI/ghost previews
 *   blank out for everyone but the host; it re-reads the same JSON client-side and no-ops when an
 *   integrated server owns the data.
 * - onAddLayers uses the standard raw-cast AddLayers idiom (getRenderer/getSkin hand back wildcard
 *   renderers; addLayer just appends to the renderer's own typed list -- runtime-safe).
 * - onModifyBakingResult wraps each spear model in SpearHandFlipModel (180-degree flip during the
 *   throw wind-up); it re-runs every resource reload and stays idempotent via the instanceof guard.
 * - Item-colour handlers only tint index 1 (molten metal / grog alcohol); index 0 and empties stay -1.
 */
@Mod(value = BannerboundAntiquity.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public class BannerboundAntiquityClient {
    public BannerboundAntiquityClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new com.bannerbound.antiquity.client.ClientDatapackRecipes());
    }

    @SubscribeEvent
    static void onRegisterGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "poison_overlay"),
            com.bannerbound.antiquity.client.PoisonHudOverlay::render);
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "bloomery_state"),
            com.bannerbound.antiquity.client.BloomeryStateHudLayer.INSTANCE);
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "crucible_state"),
            com.bannerbound.antiquity.client.CrucibleStateHudLayer.INSTANCE);
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "cooking_pot_state"),
            com.bannerbound.antiquity.client.CookingPotStateHudLayer.INSTANCE);
    }

    @SubscribeEvent
    static void onRegisterShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(
                    event.getResourceProvider(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        BannerboundAntiquity.MODID, "poison_vision"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX),
                com.bannerbound.antiquity.client.PoisonPostProcessor::setShader);
        } catch (java.io.IOException e) {
            BannerboundAntiquity.LOGGER.warn("poison_vision shader failed to load", e);
        }
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BannerboundAntiquity.LOGGER.info("Bannerbound: Antiquity client setup.");
        com.bannerbound.core.client.ClientFoodValueState.addModifier(stack -> {
            com.bannerbound.antiquity.item.FoodSpoilage fs =
                stack.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
            return fs == null ? 1.0 : fs.foodMultiplier();
        });
        com.bannerbound.core.client.UnknownItemHelper.registerStackGate(
            stack -> com.bannerbound.antiquity.item.ArrowParts.partsKnown(
                stack, com.bannerbound.core.client.UnknownItemHelper::isKnown));
        // ItemProperties.register isn't thread-safe -> enqueueWork.
        event.enqueueWork(() -> {
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.PRIMITIVE_BOW.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pull"),
                (stack, level, entity, seed) -> {
                    if (entity == null || entity.getUseItem() != stack) return 0.0F;
                    return (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()) / 20.0F;
                });
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.PRIMITIVE_BOW.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pulling"),
                (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.PRIMITIVE_BOW.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    BannerboundAntiquity.MODID, "flint_ammo"),
                (stack, level, entity, seed) -> {
                    if (entity == null) return 0.0F;
                    net.minecraft.world.item.ItemStack ammo = entity.getProjectile(stack);
                    return ammo.getItem() instanceof com.bannerbound.antiquity.item.CompositeArrowItem
                        && com.bannerbound.antiquity.item.ArrowParts.tip(ammo).equals("flint")
                        ? 1.0F : 0.0F;
                });
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.SLINGSHOT.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pull"),
                (stack, level, entity, seed) -> {
                    if (entity == null || entity.getUseItem() != stack) return 0.0F;
                    return (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()) / 20.0F;
                });
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.SLINGSHOT.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pulling"),
                (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.CRUCIBLE.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    BannerboundAntiquity.MODID, "filled"),
                (stack, level, entity, seed) -> {
                    com.bannerbound.antiquity.item.CrucibleContents c =
                        stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
                    return c != null && c.molten() ? 1.0F : 0.0F;
                });
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.BLOWGUN.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    BannerboundAntiquity.MODID, "drawing"),
                (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            net.minecraft.resources.ResourceLocation grogFilled =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "filled");
            for (net.minecraft.world.item.Item vessel : new net.minecraft.world.item.Item[] {
                    BannerboundAntiquity.MUG.get(), BannerboundAntiquity.GOAT_HORN.get() }) {
                net.minecraft.client.renderer.item.ItemProperties.register(vessel, grogFilled,
                    (stack, level, entity, seed) ->
                        stack.has(BannerboundAntiquity.GROG_CONTENTS.get()) ? 1.0F : 0.0F);
            }
        });
        // Create is a SOFT dep: gate the Ponder bridge behind isLoaded, else PonderBootstrap's create imports NoClassDefFoundError when Create is absent (the lambda defers class resolution).
        if (ModList.get().isLoaded("create")) {
            com.bannerbound.antiquity.client.ponder.PonderBootstrap.init();
        }
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BannerboundAntiquity.MORTAR_AND_PESTLE_BE.get(),
            MortarAndPestleRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.DRYING_RACK_BE.get(),
            com.bannerbound.antiquity.client.DryingRackRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.FERMENTATION_TROUGH_BE.get(),
            com.bannerbound.antiquity.client.FermentationTroughRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.STONE_ANVIL_BE.get(),
            com.bannerbound.antiquity.client.StoneAnvilRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.BELLOWS_BLOCK_BE.get(),
            com.bannerbound.antiquity.client.BellowsRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.CRUCIBLE_BLOCK_BE.get(),
            com.bannerbound.antiquity.client.CrucibleRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.STONE_COOKING_POT_BE.get(),
            com.bannerbound.antiquity.client.StoneCookingPotRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.BASKET_BE.get(),
            BasketRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.BLOOMERY_BE.get(),
            BloomeryRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.KILN_BE.get(),
            KilnRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.CLAY_TANK_BE.get(),
            com.bannerbound.antiquity.client.ClayTankRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.TANNING_RACK_BE.get(),
            com.bannerbound.antiquity.client.TanningRackRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.CRAFTING_STONE_BE.get(),
            CraftingStoneRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.FLETCHING_STATION_BE.get(),
            com.bannerbound.antiquity.client.FletchingStationRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.POTTERY_SLAB_BE.get(),
            com.bannerbound.antiquity.client.PotterySlabRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.WOODWORKING_TABLE_BE.get(),
            com.bannerbound.antiquity.client.WoodworkingTableRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.MASONS_BENCH_BE.get(),
            com.bannerbound.antiquity.client.MasonsBenchRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.CHOPPING_STUMP_BE.get(),
            ChoppingStumpRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.ROPE_FENCE_POST_BE.get(),
            RopeFencePostRenderer::new);
        event.registerBlockEntityRenderer(BannerboundAntiquity.ROPE_FENCE_GATE_BE.get(),
            RopeFenceGateRenderer::new);
        event.registerEntityRenderer(BannerboundAntiquity.SPEAR_PROJECTILE.get(),
            SpearProjectileRenderer::new);
        event.registerEntityRenderer(BannerboundAntiquity.ARROW_ENTITY.get(),
            com.bannerbound.antiquity.client.CompositeArrowRenderer::new);
        event.registerEntityRenderer(BannerboundAntiquity.BLOWDART_PROJECTILE.get(),
            com.bannerbound.antiquity.client.BlowdartRenderer::new);
        event.registerEntityRenderer(BannerboundAntiquity.THROWN_ROCK.get(),
            net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        event.registerEntityRenderer(BannerboundAntiquity.GROUND_DECAL.get(),
            GroundDecalRenderer::new);
        event.registerEntityRenderer(BannerboundAntiquity.SPEARED_FISH.get(),
            SpearedFishEntityRenderer::new);
        event.registerEntityRenderer(BannerboundAntiquity.RAFT.get(),
            RaftRenderer::new);

        event.registerEntityRenderer(BannerboundAntiquity.WORM_BAIT.get(),
            WormBaitRenderer::new);
        event.registerEntityRenderer(net.minecraft.world.entity.EntityType.LEASH_KNOT,
            RopedPostKnotRenderer::new);
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        ItemRenderer itemRenderer = event.getContext().getItemRenderer();
        for (EntityType<?> type : event.getEntityTypes()) {
            EntityRenderer<?> renderer = event.getRenderer(type);
            if (renderer instanceof LivingEntityRenderer ler) {
                ler.addLayer(new StuckSpearLayer(ler, itemRenderer));
            }
        }
        for (PlayerSkin.Model skin : event.getSkins()) {
            EntityRenderer<?> renderer = event.getSkin(skin);
            if (renderer instanceof LivingEntityRenderer ler) {
                ler.addLayer(new StuckSpearLayer(ler, itemRenderer));
            }
        }
    }

    @SubscribeEvent
    static void onRegisterParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(BannerboundAntiquity.BLOOD_DROP.get(), BloodDropParticle.Provider::new);
    }

    @SubscribeEvent
    static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (String shape : com.bannerbound.antiquity.workshop.MetalworkingItems.MOLD_SHAPES) {
            event.register(com.bannerbound.antiquity.client.StoneAnvilRenderer.placedMoldModel(shape));
        }
    }

    @SubscribeEvent
    static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        wrapSpearModel(models, BannerboundAntiquity.WOODEN_SPEAR.get());
        wrapSpearModel(models, BannerboundAntiquity.BONE_SPEAR.get());
        wrapSpearModel(models, BannerboundAntiquity.STONE_SPEAR.get());
    }

    private static void wrapSpearModel(Map<ModelResourceLocation, BakedModel> models, Item item) {
        ModelResourceLocation key = ModelResourceLocation.inventory(BuiltInRegistries.ITEM.getKey(item));
        BakedModel original = models.get(key);
        if (original != null && !(original instanceof SpearHandFlipModel)) {
            models.put(key, new SpearHandFlipModel(original));
        }
    }

    @SubscribeEvent
    static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
            MortarAndPestleModel.LAYER_LOCATION, MortarAndPestleModel::createBodyLayer);
        event.registerLayerDefinition(
            BloomeryModel.LAYER_LOCATION, BloomeryModel::createBodyLayer);
        event.registerLayerDefinition(
            RaftModel.LAYER_LOCATION, RaftModel::createBodyLayer);
        event.registerLayerDefinition(
            com.bannerbound.antiquity.client.BellowsModel.LAYER_LOCATION,
            com.bannerbound.antiquity.client.BellowsModel::createBodyLayer);
        event.registerLayerDefinition(
            com.bannerbound.antiquity.client.HelmetModel.LAYER,
            com.bannerbound.antiquity.client.HelmetModel::createBodyLayer);

        event.registerLayerDefinition(
            WormBaitModel.LAYER_LOCATION, WormBaitModel::createBodyLayer
        );
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(BannerboundAntiquity.BASKET_MENU.get(), BasketScreen::new);
    }

    @SubscribeEvent
    static void onRegisterTooltipComponents(
            net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(com.bannerbound.antiquity.item.BasketTooltip.class,
            com.bannerbound.antiquity.client.ClientBasketTooltip::new);
    }

    @SubscribeEvent
    static void onComputeCameraAngles(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        float roll = com.bannerbound.antiquity.client.HammerScreen.cameraShake();
        if (roll != 0F) {
            event.setRoll(event.getRoll() + roll);
        }
    }

    @SubscribeEvent
    static void onHammerComputeFov(net.neoforged.neoforge.client.event.ComputeFovModifierEvent event) {
        float fov = com.bannerbound.antiquity.client.HammerScreen.fovEffect();
        if (fov != 1.0F) {
            event.setNewFovModifier(event.getNewFovModifier() * fov);
        }
    }

    @SubscribeEvent
    static void onClientTickArmRaise(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        java.util.UUID local = mc.player != null ? mc.player.getUUID() : null;
        com.bannerbound.antiquity.client.HammerArmState.tick(local,
            com.bannerbound.antiquity.client.HammerScreen.MINIGAME_ACTIVE);
    }

    @SubscribeEvent
    static void onHammerRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (!com.bannerbound.antiquity.client.HammerScreen.MINIGAME_ACTIVE) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        float raise = com.bannerbound.antiquity.client.HammerScreen.handRaise();
        if (raise <= 0.001F) return;
        com.mojang.blaze3d.vertex.PoseStack ps = event.getPoseStack();
        ps.translate(0.0F, raise * 0.45F, raise * 0.12F);
        ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-raise * 55F));
    }

    @SubscribeEvent
    static void onRegisterItemColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            if (tintIndex != 1) return -1;
            com.bannerbound.antiquity.item.CrucibleContents contents =
                stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
            return contents == null ? -1 : (0xFF000000 | contents.tintColor());
        }, BannerboundAntiquity.CRUCIBLE.get());

        event.register((stack, tintIndex) -> {
            if (tintIndex != 1) return -1;
            com.bannerbound.antiquity.item.GrogContents grog =
                stack.get(BannerboundAntiquity.GROG_CONTENTS.get());
            return grog == null ? -1 : (0xFF000000 | (grog.tint() & 0xFFFFFF));
        }, BannerboundAntiquity.MUG.get(), BannerboundAntiquity.GOAT_HORN.get());
    }

}
