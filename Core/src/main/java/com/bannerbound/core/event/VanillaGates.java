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
 * Merged vanilla-interaction gate event handlers (placeholder — full doc pass to follow).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class VanillaGates {

    /*
     * Gates animal breeding behind the {@code bannerbound.allow_animal_breeding} flag.
     * Even if the player has wheat (a starting item), they can't use it to push an adult animal
     * into love mode unless their settlement has researched a node that grants the flag.
     *
     * The check fires when the player right-clicks an adult, non-loving animal with its breeding food.
     * Other animal interactions (healing wolves, taming horses, etc.) aren't blocked.
     */
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
        // Only intervene when the food would actually trigger breeding: adult animal, not
        // already in love. (Babies eat to grow faster; loving animals are already committed.)
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

    /*
     * Gates a player's own use of bone meal behind the {@code bannerbound.allow_fertilizing} flag
     * (granted by the Fertilization research). Bone meal stays craftable and holdable — only the
     * gesture of applying it to a block is blocked until researched, exactly as {@link VanillaGates}
     * gates tilling/sowing and {@link VanillaGates} gates path-making. This is the player-side mirror
     * of the same flag {@code FarmerWorkGoal} checks before letting a farmer fertilize crops.
     * Unsettled players (no settlement) are gated too — same rule as paving and planting.
     */
    private static final String FERTILIZING_FLAG = "bannerbound.allow_fertilizing";
    /** Fertilizer items whose APPLICATION is gated by the Fertilization research. Core ships this tag with
     *  {@code minecraft:bone_meal}; Antiquity merges in its {@code dung} (the bone-meal-style fertilizer) —
     *  recognised by tag so the gate stays self-contained and any addon's fertilizer is covered. */
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
        if (server == null) return true; // fail-open if no server context
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, FERTILIZING_FLAG);
    }

    /*
     * Blocks three vanilla "use the land" interactions until the player's settlement has researched
     * Agricultural Revolution (flag {@code bannerbound.allow_planting}):
     * <ul>
     *   <li>Right-clicking a sapling onto soil to plant it.</li>
     *   <li>Right-clicking a hoe on grass/dirt to till it into farmland.</li>
     *   <li>Right-clicking a known seed/crop item onto farmland to sow it.</li>
     * </ul>
     * Items themselves stay unlocked (the recipe is craftable) — only the placement gesture is gated.
     * Mirrors {@link VanillaGates}'s pattern: cancel the event, push a red action-bar message to the
     * player. Unsettled players (no settlement) are also gated — same rule as paving.
     */
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

    /** Vanilla seed-style items. Small literal set is fine for v1 — modded crops can be added
     *  later if they're called out. Excluding wheat/bread/etc. that aren't placed on farmland. */
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
        if (server == null) return true; // fail-open if no server context
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, PLANTING_FLAG);
    }

    private static void sendLockedMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("bannerbound.planting.locked").withStyle(ChatFormatting.RED),
            true);
    }

    /*
     * Blocks the vanilla shovel-flatten-to-path interaction until the player's settlement has
     * researched Paving (flag {@code bannerbound.allow_paving}). Pre-research, right-clicking grass
     * with a shovel does nothing — even if shovels are otherwise unlocked.
     */
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
        // No settlement or no paving flag — cancel the modification.
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.paving.locked").withStyle(ChatFormatting.RED),
            true);
    }

    /*
     * Suppresses vanilla lightning-strike transformations when vanilla content is stripped
     * ({@link VanillaContentState#isEnabled()} is false). In a from-scratch settlement a thunderstorm
     * shouldn't quietly turn a farmer's pig into a zombified piglin or a villager into a witch — those
     * are vanilla-mob plumbing the settlement doesn't speak.
     *
     * <p>This is deliberately narrow: it cancels the strike <i>only</i> for the four entities whose
     * vanilla {@code thunderHit} converts them — {@link Pig} → zombified piglin, {@link Villager} →
     * witch, {@link MushroomCow} variant toggle, {@link Creeper} → charged. Every other entity (players,
     * cows, item frames, structures) is left to take normal lightning damage and fire, so the storm
     * still feels real; it just won't mutate our livestock and townsfolk into hostiles. Parallels
     * {@link VanillaGates}, which strips the spawning side of the same vanilla layer.
     */
    @SubscribeEvent
    public static void onStruckByLightning(EntityStruckByLightningEvent event) {
        if (VanillaContentState.isEnabled()) return; // vanilla untouched
        if (transforms(event.getEntity())) event.setCanceled(true);
    }

    private static boolean transforms(Entity e) {
        return e instanceof Pig || e instanceof Villager || e instanceof MushroomCow
            || e instanceof Creeper;
    }

    /*
     * Seals the Nether and the End when vanilla content is stripped
     * ({@link VanillaContentState#isEnabled()} is false): nether-portal frames can't be lit and end
     * portals can't be completed in survival. Creative/op players bypass (so the dimensions stay
     * reachable for testing).
     *
     * <ul>
     *   <li>{@link BlockEvent.PortalSpawnEvent} — a source-agnostic backstop that cancels any nether
     *       portal forming (fire spread, etc.), with no nice message.</li>
     *   <li>{@link PlayerInteractEvent.RightClickBlock} — the player-facing path: cancels lighting
     *       obsidian and seating an ender eye into an empty frame, with a "locked" actionbar.</li>
     * </ul>
     */
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

        if (bypass(player)) return; // creative or op may still force a portal for testing
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.vanilla.portal_locked").withStyle(ChatFormatting.RED),
            true);
    }

    private static boolean bypass(ServerPlayer player) {
        return player.hasPermissions(2) || player.getAbilities().instabuild;
    }

    /*
     * Cancels hostile-mob spawning when vanilla content is stripped
     * ({@link VanillaContentState#isEnabled()} is false). A from-scratch settlement faces its own
     * threats (barbarians, raiders) — not vanilla zombies welling up out of the dark.
     *
     * <p>Only <i>world-driven</i> spawn sources are cancelled (natural, chunk-gen, structure, spawner,
     * patrol, reinforcement, jockey). Deliberate sources — spawn eggs, {@code /summon}, conversions,
     * breeding, etc. — are left alone so admins/creative can still place a hostile to test. Passive
     * animals (and Antiquity's AI-converted wolves/ocelots, which stay {@code CREATURE}) are never
     * touched. This is the broad counterpart to {@code ResourceChunkPopulator.onFinalizeSpawn}, which
     * suppresses only managed farm animals — the two coexist, each cancelling its own targets.
     *
     * <p>{@link #onFinalizeSpawn} only catches <i>fresh</i> spawns — it never fires for a hostile that
     * was written into a saved chunk before the flag took effect (a world played as vanilla first, or
     * mobs that spawned in a border chunk and persisted). Those re-enter the world through
     * {@link #onEntityJoin} with {@link EntityJoinLevelEvent#loadedFromDisk()} true and would otherwise
     * slip through, so we discard them there too. Disk-loaded mobs carry no spawn reason, so the
     * deliberate-placement carve-out can't apply — anything {@code /summon}ed and then saved will be
     * cleared on the next reload, which is the acceptable trade for closing the leak.
     */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (VanillaContentState.isEnabled()) return; // vanilla untouched
        if (!isHostile(event.getEntity())) return;   // leave passive animals alone
        switch (event.getSpawnType()) {
            case NATURAL, CHUNK_GENERATION, STRUCTURE, SPAWNER, PATROL, REINFORCEMENT, JOCKEY ->
                event.setSpawnCancelled(true);
            default -> {
                // SPAWN_EGG, COMMAND, MOB_SUMMONED, BUCKET, DISPENSER, BREEDING, CONVERSION, EVENT,
                // TRIAL_SPAWNER, SPAWNER_EGG, etc. — deliberate placements, left to player/admin.
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (VanillaContentState.isEnabled()) return;     // vanilla untouched
        if (!event.loadedFromDisk()) return;             // fresh spawns handled by onFinalizeSpawn
        if (!isHostile(event.getEntity())) return;       // leave passive animals alone
        event.setCanceled(true);                         // persisted hostile — never let it rejoin
    }

    private static boolean isHostile(Entity e) {
        if (e instanceof Enemy) return true; // marker on zombies, skeletons, creepers, illagers, ...
        return e instanceof Mob mob && mob.getType().getCategory() == MobCategory.MONSTER;
    }
}
