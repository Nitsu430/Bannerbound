package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Server-side gate for vanilla "use the world" interactions that a from-scratch Bannerbound
 * settlement should not get for free. Every handler blocks a GESTURE, never an item: items stay
 * craftable and holdable, only the act of applying them is cancelled until the owning settlement
 * has researched the granting flag. Cancelled interactions push a red action-bar "locked" message;
 * creative players bypass. Unsettled players (no settlement) are gated the same as settled-but-
 * unresearched ones; settlement lookups fail-open only when there is no server context.
 *
 * <p>Research gates (flag -> gesture blocked until granted):
 * <ul>
 *   <li>allow_animal_breeding: feeding an ADULT, not-already-in-love animal its breeding food (runs
 *       at HIGH priority). Babies eating to grow and animals already in love are left alone, as are
 *       other animal interactions (healing wolves, taming horses).</li>
 *   <li>allow_fertilizing: applying a fertilizer to a block. Membership is by the
 *       bannerbound:fertilizer item tag (Core ships minecraft:bone_meal; Antiquity merges its dung)
 *       so the gate is self-contained and any addon fertilizer is covered. Player-side mirror of the
 *       flag FarmerWorkGoal checks.</li>
 *   <li>allow_planting: planting a sapling, tilling grass/dirt with a hoe, or sowing a known seed
 *       onto farmland. isSeedItem is a small vanilla-seed literal set (v1; extend for modded crops).</li>
 *   <li>allow_paving: shovel-flattening grass into a path.</li>
 * </ul>
 *
 * <p>Vanilla-content stripping (active only when VanillaContentState.isEnabled() is false; when
 * vanilla content is on, all of the following is skipped):
 * <ul>
 *   <li>Lightning: cancels the strike ONLY for the four entities whose vanilla thunderHit converts
 *       them (Pig -> zombified piglin, Villager -> witch, MushroomCow variant toggle, Creeper ->
 *       charged); everything else takes normal lightning damage/fire so storms still feel real.</li>
 *   <li>Nether/End sealing: PortalSpawnEvent is a source-agnostic backstop (cancels any forming
 *       nether portal, no message); the RightClickBlock path cancels lighting obsidian and seating an
 *       ender eye, with a locked message. Creative/op bypass so the dimensions stay test-reachable.</li>
 *   <li>Hostile spawns: onFinalizeSpawn cancels only WORLD-DRIVEN sources (natural, chunk-gen,
 *       structure, spawner, patrol, reinforcement, jockey) and leaves deliberate ones (spawn egg,
 *       /summon, conversion, breeding) alone; it is the broad counterpart to
 *       ResourceChunkPopulator.onFinalizeSpawn, which handles managed farm animals. onEntityJoin
 *       catches hostiles saved into a chunk before the flag took effect (loadedFromDisk true):
 *       disk-loaded mobs carry no spawn reason, so a /summoned-then-saved hostile is also cleared on
 *       the next reload -- the accepted trade for closing that leak. isHostile = Enemy marker OR
 *       MONSTER category.</li>
 * </ul>
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class VanillaGates {

    public static final String FLAG = "bannerbound.allow_animal_breeding";

    private VanillaGates() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (sp.isCreative()) {
            return;
        }
        if (!(event.getTarget() instanceof Animal animal)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !animal.isFood(stack)) {
            return;
        }
        if (animal.getAge() != 0 || animal.isInLove()) {
            return;
        }

        if (hasBreedingFlag(sp)) {
            return;
        }

        event.setCanceled(true);
        sp.sendSystemMessage(Component.translatable("bannerbound.feature.cant_do_yet")
            .withStyle(ChatFormatting.RED));
    }

    private static boolean hasBreedingFlag(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            return ResearchManager.hasFlag(settlement, FLAG);
        } catch (Exception ex) {
            return false;
        }
    }

    private static final String FERTILIZING_FLAG = "bannerbound.allow_fertilizing";
    private static final TagKey<Item> FERTILIZER = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "fertilizer"));

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getItemStack().is(FERTILIZER)) return;
        if (settlementAllows(player)) return;
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.fertilizing.locked").withStyle(ChatFormatting.RED),
            true);
    }

    private static boolean settlementAllows(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, FERTILIZING_FLAG);
    }

    private static final String PLANTING_FLAG = "bannerbound.allow_planting";

    @SubscribeEvent
    public static void onPlantingRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isCreative()) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        boolean isSapling = stack.is(ItemTags.SAPLINGS);
        boolean isSeed = isSeedItem(stack.getItem())
            && event.getLevel().getBlockState(event.getPos()).is(Blocks.FARMLAND);
        if (!isSapling && !isSeed) return;
        if (plantingSettlementAllows(player)) return;
        event.setCanceled(true);
        sendLockedMessage(player);
    }

    @SubscribeEvent
    public static void onHoeTill(BlockEvent.BlockToolModificationEvent event) {
        if (event.getItemAbility() != ItemAbilities.HOE_TILL) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.isCreative()) return;
        if (plantingSettlementAllows(player)) return;
        event.setCanceled(true);
        sendLockedMessage(player);
    }

    private static boolean isSeedItem(Item item) {
        return item == Items.WHEAT_SEEDS
            || item == Items.BEETROOT_SEEDS
            || item == Items.MELON_SEEDS
            || item == Items.PUMPKIN_SEEDS
            || item == Items.CARROT
            || item == Items.POTATO
            || item == Items.TORCHFLOWER_SEEDS
            || item == Items.PITCHER_POD;
    }

    private static boolean plantingSettlementAllows(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, PLANTING_FLAG);
    }

    private static void sendLockedMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("bannerbound.planting.locked").withStyle(ChatFormatting.RED),
            true);
    }

    @SubscribeEvent
    public static void onToolModify(BlockEvent.BlockToolModificationEvent event) {
        if (event.getItemAbility() != ItemAbilities.SHOVEL_FLATTEN) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (player.isCreative()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement != null && ResearchManager.hasFlag(settlement, "bannerbound.allow_paving")) {
            return;
        }
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.paving.locked").withStyle(ChatFormatting.RED),
            true);
    }

    @SubscribeEvent
    public static void onStruckByLightning(EntityStruckByLightningEvent event) {
        if (VanillaContentState.isEnabled()) return;
        if (transforms(event.getEntity())) event.setCanceled(true);
    }

    private static boolean transforms(Entity e) {
        return e instanceof Pig || e instanceof Villager || e instanceof MushroomCow
            || e instanceof Creeper;
    }

    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (VanillaContentState.isEnabled()) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPortalRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockState bs = event.getLevel().getBlockState(event.getPos());
        ItemStack held = event.getItemStack();

        boolean igniting = (held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE))
            && bs.is(Blocks.OBSIDIAN);
        boolean seatingEye = held.is(Items.ENDER_EYE)
            && bs.is(Blocks.END_PORTAL_FRAME)
            && !bs.getValue(BlockStateProperties.EYE);
        if (!igniting && !seatingEye) return;

        if (bypass(player)) return;
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.vanilla.portal_locked").withStyle(ChatFormatting.RED),
            true);
    }

    private static boolean bypass(ServerPlayer player) {
        return player.hasPermissions(2) || player.getAbilities().instabuild;
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (VanillaContentState.isEnabled()) return;
        if (!isHostile(event.getEntity())) return;
        switch (event.getSpawnType()) {
            case NATURAL, CHUNK_GENERATION, STRUCTURE, SPAWNER, PATROL, REINFORCEMENT, JOCKEY ->
                event.setSpawnCancelled(true);
            default -> {
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (VanillaContentState.isEnabled()) return;
        if (!event.loadedFromDisk()) return;
        if (!isHostile(event.getEntity())) return;
        event.setCanceled(true);
    }

    private static boolean isHostile(Entity e) {
        if (e instanceof Enemy) return true;
        return e instanceof Mob mob && mob.getType().getCategory() == MobCategory.MONSTER;
    }
}
