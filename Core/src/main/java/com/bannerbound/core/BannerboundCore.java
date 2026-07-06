package com.bannerbound.core;

import org.jetbrains.annotations.ApiStatus;

import org.slf4j.Logger;

import com.bannerbound.core.block.StockpileBlock;
import com.bannerbound.core.block.entity.StockpileBlockEntity;
import com.bannerbound.core.menu.StockpileMenu;
import com.bannerbound.core.entity.BarbarianEntity;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.FisherBobber;
import com.bannerbound.core.item.ForemansRodItem;
import com.bannerbound.core.item.HousingOrdersItem;
import com.bannerbound.core.item.RegistrationTabletItem;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * Mod entry point and registration hub: owns the {@link #MODID} constant and every common-side
 * DeferredRegister (blocks, items, data components, block entities, menus, entity types, sounds,
 * command argument types, entity attachments, creative tab). Client-only registration lives in
 * {@link BannerboundCoreClient}; the architecture overview lives in {@code ARCHITECTURE.md}.
 * <p>
 * Design decisions baked into what is (and is not) registered here:
 * <ul>
 * <li>No workstation/home/outpost anchor blocks. Jobs are assigned via the citizen Job tab; homes
 *     and workshops are rod-drawn regions whose committed boxes live server-side in
 *     BlockSelectionRegistry, so the Housing/Workshop Orders rods bind by UUID-string components
 *     (BOUND_HOME_ID / BOUND_WORKSHOP_ID, "" = unbound), never by position. Outpost claims are made
 *     by planting a plain faction banner (see api.settlement.Outpost + FactionBannerEvents). The
 *     Stockpile is the lone surviving block/BE/menu: a community-storage anchor whose BE scans its
 *     enclosure and aggregates the containers inside.</li>
 * <li>Foreman's Rod components track only the NEXT-click state (workstation type, in-progress A/B,
 *     optional bound digger via FOREMAN_TARGET_CITIZEN where empty/absent = all diggers, plus its
 *     display name); one rod can author many independent selections. MARKER_POINT_A is the shared
 *     in-progress point for both Orders rods.</li>
 * <li>REGISTRATION_PAPER is the Medieval-and-later reskin of REGISTRATION_TABLET: same item class
 *     and components, only issue era and texture differ (see handleGetRegistrationTablet).</li>
 * <li>TOOL_QUALITY is the canonical cross-suite craftsmanship component (Crude..Masterwork plus
 *     guild-only Perfect/Legendary), attached by minigames and Crafter NPCs, read by stat hooks
 *     and tooltips (api.quality + FLETCHING_PLAN.md Part 4).</li>
 * <li>Barbarian and Mercenary are distinct entity types subclassing CitizenEntity so alert-others
 *     rallies only their own kind and citizen targeting stays a clean instanceof.</li>
 * <li>Custom brigadier argument types (EraGameRuleArgument backing /gamerule forceMaxAge) must be
 *     registered here or the command tree's tab-complete suggestions fail to sync to clients.</li>
 * </ul>
 * Common setup registers the Hunter/Guard/Miner/Builder/Crafter/Stocker jobs through the public
 * CitizenJobRegistry API (the same path an expansion uses, never the legacy hardcoded job sites),
 * inside enqueueWork so registration runs on the main thread before any citizen spawns.
 * gatherer(true) marks anarchy self-organizing roles; guard/miner/builder/crafter/stocker are
 * government-assigned or ordered and never self-organize. All jobs are research-gated via
 * bannerbound.unlock.* flags; tool-optional jobs auto-install their tool-age weapon from storage.
 * Core also installs a fallback pottery workshop rule (furnace + crafting table) and a
 * crafting-table crafter icon baseline, both overridable by era expansions. On ServerStarted,
 * force-load is re-applied to every settlement-claimed chunk: setChunkForced persists in the save,
 * but externally-edited or older saves may lack the flag.
 */
@Mod(BannerboundCore.MODID)
@ApiStatus.Internal
public class BannerboundCore {
    public static final String MODID = "bannerbound";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<StockpileBlock> STOCKPILE = BLOCKS.register("stockpile",
        () -> new StockpileBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f)
            .sound(SoundType.STONE)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> STOCKPILE_ITEM = ITEMS.registerSimpleBlockItem("stockpile", STOCKPILE);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StockpileBlockEntity>> STOCKPILE_BE =
        BLOCK_ENTITY_TYPES.register("stockpile",
            () -> BlockEntityType.Builder.of(StockpileBlockEntity::new, STOCKPILE.get())
                .build(null));

    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<StockpileMenu>> STOCKPILE_MENU =
        MENUS.register("stockpile", () -> IMenuTypeExtension.create(StockpileMenu::new));

    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(MODID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SETTLEMENT_REF =
        COMPONENTS.registerComponentType("settlement_ref",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TABLET_CHARGES =
        COMPONENTS.registerComponentType("tablet_charges",
            builder -> builder
                .persistent(Codec.INT)
                .networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TABLET_MAX_CHARGES =
        COMPONENTS.registerComponentType("tablet_max_charges",
            builder -> builder
                .persistent(Codec.INT)
                .networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> STOLEN_STANDARD_SETTLEMENT =
        COMPONENTS.registerComponentType("stolen_standard_settlement",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> STOLEN_STANDARD_NAME =
        COMPONENTS.registerComponentType("stolen_standard_name",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOREMAN_WORKSTATION_TYPE =
        COMPONENTS.registerComponentType("foreman_workstation_type",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> FOREMAN_POINT_A =
        COMPONENTS.registerComponentType("foreman_point_a",
            builder -> builder
                .persistent(BlockPos.CODEC)
                .networkSynchronized(BlockPos.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> FOREMAN_POINT_B =
        COMPONENTS.registerComponentType("foreman_point_b",
            builder -> builder
                .persistent(BlockPos.CODEC)
                .networkSynchronized(BlockPos.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOREMAN_TARGET_CITIZEN =
        COMPONENTS.registerComponentType("foreman_target_citizen",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOREMAN_TARGET_NAME =
        COMPONENTS.registerComponentType("foreman_target_name",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> MARKER_POINT_A =
        COMPONENTS.registerComponentType("marker_point_a",
            builder -> builder
                .persistent(BlockPos.CODEC)
                .networkSynchronized(BlockPos.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BOUND_HOME_ID =
        COMPONENTS.registerComponentType("bound_home_id",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BOUND_WORKSHOP_ID =
        COMPONENTS.registerComponentType("bound_workshop_id",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.bannerbound.core.api.quality.QualityTier>> TOOL_QUALITY =
        COMPONENTS.registerComponentType("tool_quality",
            builder -> builder
                .persistent(com.bannerbound.core.api.quality.QualityTier.CODEC)
                .networkSynchronized(com.bannerbound.core.api.quality.QualityTier.STREAM_CODEC));

    public static final DeferredItem<RegistrationTabletItem> REGISTRATION_TABLET = ITEMS.registerItem("registration_tablet",
        props -> new RegistrationTabletItem(props.stacksTo(16)));

    public static final DeferredItem<RegistrationTabletItem> REGISTRATION_PAPER = ITEMS.registerItem("registration_paper",
        props -> new RegistrationTabletItem(props.stacksTo(16)));

    public static final DeferredItem<ForemansRodItem> FOREMANS_ROD = ITEMS.registerItem("foremans_rod",
        props -> new ForemansRodItem(props.stacksTo(1)));

    public static final DeferredItem<HousingOrdersItem> HOUSING_ORDERS = ITEMS.registerItem("housing_orders",
        props -> new HousingOrdersItem(props.stacksTo(1)));

    public static final DeferredItem<com.bannerbound.core.item.WorkshopRodItem> WORKSHOP_ROD =
        ITEMS.registerItem("workshop_rod",
            props -> new com.bannerbound.core.item.WorkshopRodItem(props.stacksTo(1)));

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN =
        ENTITY_TYPES.register("citizen",
            () -> EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.CREATURE)
                .sized(0.6f, 1.95f)
                .clientTrackingRange(10)
                .build("citizen"));

    public static final DeferredHolder<EntityType<?>, EntityType<BarbarianEntity>> BARBARIAN =
        ENTITY_TYPES.register("barbarian",
            () -> EntityType.Builder.<BarbarianEntity>of(BarbarianEntity::new, MobCategory.CREATURE)
                .sized(0.6f, 1.95f)
                .clientTrackingRange(10)
                .build("barbarian"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.core.entity.MercenaryEntity>> MERCENARY =
        ENTITY_TYPES.register("mercenary",
            () -> EntityType.Builder.<com.bannerbound.core.entity.MercenaryEntity>of(
                    com.bannerbound.core.entity.MercenaryEntity::new, MobCategory.CREATURE)
                .sized(0.6f, 1.95f)
                .clientTrackingRange(10)
                .build("mercenary"));

    public static final DeferredHolder<EntityType<?>, EntityType<FisherBobber>> FISHER_BOBBER =
        ENTITY_TYPES.register("fisher_bobber",
            () -> EntityType.Builder.<FisherBobber>of(FisherBobber::new, MobCategory.MISC)
                .sized(0.25f, 0.25f)
                .clientTrackingRange(8)
                .updateInterval(2)
                .build("fisher_bobber"));

    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUND_SETTLEMENT_SOUND = SOUNDS.register(
        "found_settlement",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "found_settlement")));
    public static final DeferredHolder<SoundEvent, SoundEvent> MEDIEVAL_SETTLEMENT_SOUND = SOUNDS.register(
        "medieval_settlement",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "medieval_settlement")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BUBBLE_POP_SOUND = SOUNDS.register(
        "bubble_pop",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "bubble_pop")));
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUND_RELIGION_SOUND = SOUNDS.register(
        "found_religion",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "found_religion")));
    public static final DeferredHolder<SoundEvent, SoundEvent> INSIGHT_SOUND = SOUNDS.register(
        "insight",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "insight")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CRISIS_MENU_OPEN_SOUND = SOUNDS.register(
        "crisis_menu_open",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "crisis_menu_open")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CRISIS_MENU_CLOSE_SOUND = SOUNDS.register(
        "crisis_menu_close",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "crisis_menu_close")));

    public static SoundEvent getAgeAdvanceSound(com.bannerbound.core.api.settlement.Era era) {
        return switch (era) {
            case MEDIEVAL -> MEDIEVAL_SETTLEMENT_SOUND.get();
            default -> net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
        };
    }

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BANNERBOUND_TAB = CREATIVE_MODE_TABS.register("bannerbound_core", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.bannerbound"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> REGISTRATION_TABLET.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(REGISTRATION_TABLET.get());
                output.accept(REGISTRATION_PAPER.get());
                output.accept(FOREMANS_ROD.get());
                output.accept(STOCKPILE_ITEM.get());
                output.accept(HOUSING_ORDERS.get());
                output.accept(WORKSHOP_ROD.get());
            }).build());

    public static final DeferredRegister<net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>> COMMAND_ARGUMENT_TYPES =
        DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, MODID);
    public static final DeferredHolder<net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>,
            net.minecraft.commands.synchronization.SingletonArgumentInfo<com.bannerbound.core.command.EraGameRuleArgument>> ERA_GAMERULE_ARG =
        COMMAND_ARGUMENT_TYPES.register("era_gamerule",
            () -> net.minecraft.commands.synchronization.ArgumentTypeInfos.registerByClass(
                com.bannerbound.core.command.EraGameRuleArgument.class,
                net.minecraft.commands.synchronization.SingletonArgumentInfo.contextFree(
                    com.bannerbound.core.command.EraGameRuleArgument::era)));

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);
    // Synced so the client can draw the herder rope; transient by design - never serialized, re-claimed after reload.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> HERDED_BY =
        ATTACHMENT_TYPES.register("herded_by",
            () -> AttachmentType.<Integer>builder(() -> 0).sync(ByteBufCodecs.VAR_INT).build());

    public BannerboundCore(IEventBus modEventBus, ModContainer modContainer) {
        // Custom game rules (e.g. globalChat) must be registered before any level loads.
        com.bannerbound.core.chat.BannerboundGameRules.register();

        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        COMPONENTS.register(modEventBus);
        SOUNDS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::registerEntityAttributes);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);
        ATTACHMENT_TYPES.register(modEventBus);
        COMMAND_ARGUMENT_TYPES.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void registerEntityAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(CITIZEN.get(), CitizenEntity.createAttributes().build());
        event.put(BARBARIAN.get(), BarbarianEntity.createAttributes().build());
        event.put(MERCENARY.get(), com.bannerbound.core.entity.MercenaryEntity.createAttributes().build());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");

        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.HunterWorkGoal.JOB_TYPE_ID)
                .gatherer(true)
                .anarchyOrder(4)   // after forester / fishers / forager
                .unit("hunter")
                .icon(com.bannerbound.core.social.JobIcons.ROLE_HUNT,
                    net.minecraft.world.item.Items.WOODEN_SWORD)
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.HunterWorkGoal(c, s))
                .build()));

        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.GuardWorkGoal.JOB_TYPE_ID)
                .gatherer(false)
                .unit("guard")
                .icon("guard", net.minecraft.world.item.Items.WOODEN_SWORD)
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.GuardWorkGoal(c, s))
                .build()));

        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.MinerWorkGoal.JOB_TYPE_ID)
                .gatherer(false)
                .unit("miner")
                .icon("pickaxe", net.minecraft.world.item.Items.WOODEN_PICKAXE)
                .toolRequired(true)
                .goal((c, s) -> new com.bannerbound.core.entity.MinerWorkGoal(c, s))
                .build()));

        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.BuilderWorkGoal.JOB_TYPE_ID)
                .gatherer(false)
                .unit("builder")
                .icon("builder", net.minecraft.world.item.Items.BRICK)
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.BuilderWorkGoal(c, s))
                .build()));

        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID)
                .gatherer(false)
                .unit("fletcher")
                .toolRequired(false)
                .workshopBound(null)
                .goal((c, s) -> new com.bannerbound.core.entity.CrafterWorkGoal(c, s))
                .build()));

        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.StockerWorkGoal.JOB_TYPE_ID)
                .gatherer(false)
                .unit("stocker")
                .icon("stocker", net.minecraft.world.item.Items.BUNDLE)
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.StockerWorkGoal(c, s))
                .build()));

        event.enqueueWork(() -> com.bannerbound.core.api.workshop.WorkBlockRegistry
            .registerRequirementIfAbsent("pottery", BannerboundCore::validateDefaultPotteryWorkshop));

        event.enqueueWork(() -> com.bannerbound.core.api.workshop.WorkBlockRegistry
            .setDefaultCrafterIconBaseline(net.minecraft.world.item.Items.CRAFTING_TABLE));

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    private static com.bannerbound.core.api.settlement.Workshop.Status validateDefaultPotteryWorkshop(
            net.minecraft.server.level.ServerLevel sl,
            com.bannerbound.core.api.settlement.Workshop workshop,
            java.util.Set<BlockPos> marked,
            java.util.List<BlockPos> reachableWork,
            java.util.List<BlockPos> reachableStorage) {
        if (!containsMarkedBlock(sl, marked, Blocks.FURNACE)) {
            return com.bannerbound.core.api.settlement.Workshop.Status.MISSING_HEAT_SOURCE;
        }
        if (!containsMarkedBlock(sl, marked, Blocks.CRAFTING_TABLE)) {
            return com.bannerbound.core.api.settlement.Workshop.Status.MISSING_CRAFTING_SURFACE;
        }
        return null;
    }

    private static boolean containsMarkedBlock(net.minecraft.server.level.ServerLevel sl,
                                               java.util.Set<BlockPos> marked,
                                               net.minecraft.world.level.block.Block block) {
        for (BlockPos pos : marked) {
            if (sl.getBlockState(pos).is(block)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    @SubscribeEvent
    public void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        com.bannerbound.core.faction.ChunkForceLoader.reapplyAll(event.getServer());
    }
}
