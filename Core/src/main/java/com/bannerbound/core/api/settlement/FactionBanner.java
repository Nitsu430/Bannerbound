package com.bannerbound.core.api.settlement;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.CloseSettlementScreensPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The FACTION BANNER - the block a settlement is bound to (it's the mod's name). One main
 * banner per settlement, auto-raised beside the campfire at founding in the faction's color.
 * While it is down (taken down by a member, or struck down by an enemy/explosion) the town hall
 * menu refuses to open ({@link #requireRaised}), every open settlement menu is force-closed,
 * and ALL citizen labor halts - no banner, no command. Members may relocate it freely (break ->
 * re-place); war-time relocation locks and the bedwars-style capture flow come with the war
 * system. The banner is a plain vanilla banner block (color from {@link SettlementColor}), so
 * it needs no custom block/BE, and the Heraldry editor layers vanilla banner patterns onto the
 * same block. See FACTION_BANNER_PLAN.md.
 *
 * <p>Design decisions and behaviours folded from around the class:
 * <ul>
 * <li>Banner detection is a {@code BlockTags.BANNERS} tag check and design conversion uses
 *     block-state property checks (ROTATION/FACING), never instanceof - survives block-class
 *     swaps. Whatever banner gets planted (command-given white, a looted foreign color) converts
 *     to the faction-color block on registration, keeping its rotation (standing) or facing
 *     (wall). Vanilla has no {@code byColor} for wall banners, hence the explicit switch.</li>
 * <li>{@link #placeFoundingBanner} tries the four cardinal spots two blocks out first (one block
 *     out, the banner's hitbox shadows campfire clicks), then diagonals, then flush cardinals,
 *     each with +/-1 Y flex for uneven ground, front face turned toward the campfire. Returns
 *     null when no spot takes - the founder then places one by hand (starting items include
 *     banners precisely so this cannot soft-lock).</li>
 * <li>{@link #applyDesignToBlock} (called on raise and on every design edit) is a no-op while
 *     the banner chunk is unloaded: the design is data, so the next raise/edit in a loaded chunk
 *     catches the block up. {@link #patternsFor} skips unresolvable pattern ids (datapack
 *     removed) instead of failing - the rest of the design survives.</li>
 * <li>{@link #identityDyes} is the "don't fight it" rule: paint the design layer by layer -
 *     {@code PATTERN_COVERAGE} holds hand-estimated per-pattern cloth coverage (keys are pattern
 *     PATHS with namespace stripped; unknown/modded patterns default to 0.25; the model only
 *     needs to rank colors, not be pixel-exact), each layer's coverage claims its share and
 *     proportionally hides what's underneath - then rank every dye holding at least
 *     {@code IDENTITY_SHARE_FLOOR} (5%) of the cloth, most-present first. The list is as long as
 *     the design is colorful; {@code [0]} is the settlement's primary color and the rest are its
 *     accents, used for gradients and trim across the settlement GUI. Dye your banner mostly
 *     magenta and you ARE a magenta settlement. Pure data math - safe on both sides, shared by
 *     the server (save) and the editor's live identity preview. {@link #formattingFor} then maps
 *     each dye to the nearest of the 16 {@code ChatFormatting} entries, so a couple share
 *     (pink -> light_purple, brown -> gold) and unreadable ones go grey (black -> dark_gray).</li>
 * <li>{@link #raise} assumes the caller already verified the placement is in the settlement's
 *     own territory with no live banner registered; it clears the stale "banner lost" ALERT and
 *     broadcasts so an open Statuses tab drops the entry instead of waiting out the 1h timeout,
 *     and plays a bright chime (the inverse of the loss toll). {@link #lose}: a MEMBER takedown
 *     (relocation) gets the quiet yellow note; anything else - enemy hand, creeper, piston - is
 *     an attack: on-site toll, faction-wide toll for members out of earshot, red broadcast, and
 *     an ALERT status entry so offline members still learn of it. Both force-close all open
 *     settlement menus faction-wide (no cheesing menus through a war).</li>
 * <li>{@link #validate} is the stale-registration sweep for removals that fire no player break
 *     event (explosion, piston): it only checks when the chunk is loaded - an unloaded banner is
 *     presumed standing - and runs from the gates (town hall open, banner re-placement), which
 *     is cheap and exactly where staleness matters.</li>
 * </ul>
 */
public final class FactionBanner {

    private FactionBanner() {}

    public static DyeColor dyeFor(SettlementColor color) {
        return switch (color) {
            case WHITE -> DyeColor.WHITE;
            case RED -> DyeColor.RED;
            case GOLD -> DyeColor.ORANGE;
            case YELLOW -> DyeColor.YELLOW;
            case GREEN -> DyeColor.LIME;
            case AQUA -> DyeColor.LIGHT_BLUE;
            case BLUE -> DyeColor.BLUE;
            case LIGHT_PURPLE -> DyeColor.MAGENTA;
        };
    }

    public static BannerBlock standingBlockFor(SettlementColor color) {
        return (BannerBlock) BannerBlock.byColor(dyeFor(color));
    }

    public static Item itemFor(SettlementColor color) {
        return standingBlockFor(color).asItem();
    }

    public static net.minecraft.world.level.block.Block wallBlockFor(SettlementColor color) {
        return switch (dyeFor(color)) {
            case WHITE -> net.minecraft.world.level.block.Blocks.WHITE_WALL_BANNER;
            case RED -> net.minecraft.world.level.block.Blocks.RED_WALL_BANNER;
            case ORANGE -> net.minecraft.world.level.block.Blocks.ORANGE_WALL_BANNER;
            case YELLOW -> net.minecraft.world.level.block.Blocks.YELLOW_WALL_BANNER;
            case LIME -> net.minecraft.world.level.block.Blocks.LIME_WALL_BANNER;
            case LIGHT_BLUE -> net.minecraft.world.level.block.Blocks.LIGHT_BLUE_WALL_BANNER;
            case BLUE -> net.minecraft.world.level.block.Blocks.BLUE_WALL_BANNER;
            case MAGENTA -> net.minecraft.world.level.block.Blocks.MAGENTA_WALL_BANNER;
            // Unreachable today (dyeFor returns only the 8 above) but the switch must stay exhaustive; fail soft, not crash.
            default -> net.minecraft.world.level.block.Blocks.WHITE_WALL_BANNER;
        };
    }

    private static void convertToFactionDesign(ServerLevel level, Settlement settlement, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (!isBanner(current)) return;
        BlockState converted = null;
        if (current.hasProperty(BannerBlock.ROTATION)) {
            converted = standingBlockFor(settlement.color()).defaultBlockState()
                .setValue(BannerBlock.ROTATION, current.getValue(BannerBlock.ROTATION));
        } else if (current.hasProperty(net.minecraft.world.level.block.WallBannerBlock.FACING)) {
            converted = wallBlockFor(settlement.color()).defaultBlockState()
                .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING,
                    current.getValue(net.minecraft.world.level.block.WallBannerBlock.FACING));
        }
        if (converted != null && !current.is(converted.getBlock())) {
            level.setBlock(pos, converted, 3);
        }
    }

    @Nullable
    public static BlockPos placeFoundingBanner(ServerLevel level, Settlement settlement, BlockPos campfire) {
        BlockState banner = standingBlockFor(settlement.color()).defaultBlockState();
        int[][] offsets = {
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        };
        for (int[] off : offsets) {
            for (int dy : new int[] {0, 1, -1}) {
                BlockPos pos = campfire.offset(off[0], dy, off[1]);
                if (!level.getBlockState(pos).canBeReplaced()) continue;
                if (!banner.canSurvive(level, pos)) continue;
                int rot = RotationSegment.convertToSegment(yawToward(pos, campfire));
                level.setBlock(pos, banner.setValue(BannerBlock.ROTATION, rot), 3);
                settlement.setBannerPos(pos.immutable());
                return pos;
            }
        }
        return null;
    }

    public static net.minecraft.world.level.block.entity.BannerPatternLayers patternsFor(
            Settlement settlement, net.minecraft.core.RegistryAccess registries) {
        if (settlement.bannerDesign().isEmpty()) {
            return net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY;
        }
        net.minecraft.core.Registry<net.minecraft.world.level.block.entity.BannerPattern> reg =
            registries.registryOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN);
        java.util.List<net.minecraft.world.level.block.entity.BannerPatternLayers.Layer> layers =
            new java.util.ArrayList<>();
        for (Settlement.BannerLayer layer : settlement.bannerDesign()) {
            net.minecraft.resources.ResourceLocation rl =
                net.minecraft.resources.ResourceLocation.tryParse(layer.patternId());
            if (rl == null) continue;
            var holder = reg.getHolder(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.BANNER_PATTERN, rl));
            if (holder.isEmpty()) continue;
            layers.add(new net.minecraft.world.level.block.entity.BannerPatternLayers.Layer(
                holder.get(), DyeColor.byId(layer.colorId())));
        }
        return layers.isEmpty()
            ? net.minecraft.world.level.block.entity.BannerPatternLayers.EMPTY
            : new net.minecraft.world.level.block.entity.BannerPatternLayers(java.util.List.copyOf(layers));
    }

    public static net.minecraft.world.item.ItemStack designedItem(
            Settlement settlement, net.minecraft.core.RegistryAccess registries, int count) {
        net.minecraft.world.item.ItemStack stack =
            new net.minecraft.world.item.ItemStack(itemFor(settlement.color()), count);
        var patterns = patternsFor(settlement, registries);
        if (!patterns.layers().isEmpty()) {
            stack.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, patterns);
        }
        return stack;
    }

    public static void applyDesignToBlock(ServerLevel level, Settlement settlement) {
        BlockPos pos = settlement.bannerPos();
        if (pos == null || !level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!isBanner(state)) return;
        if (level.getBlockEntity(pos)
                instanceof net.minecraft.world.level.block.entity.BannerBlockEntity be) {
            be.fromItem(designedItem(settlement, level.registryAccess(), 1),
                dyeFor(settlement.color()));
            be.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    private static final java.util.Map<String, Float> PATTERN_COVERAGE = java.util.Map.ofEntries(
        java.util.Map.entry("base", 1.0f),
        java.util.Map.entry("half_vertical", 0.5f),
        java.util.Map.entry("half_vertical_right", 0.5f),
        java.util.Map.entry("half_horizontal", 0.5f),
        java.util.Map.entry("half_horizontal_bottom", 0.5f),
        java.util.Map.entry("diagonal_left", 0.5f),
        java.util.Map.entry("diagonal_right", 0.5f),
        java.util.Map.entry("diagonal_up_left", 0.5f),
        java.util.Map.entry("diagonal_up_right", 0.5f),
        java.util.Map.entry("gradient", 0.4f),
        java.util.Map.entry("gradient_up", 0.4f),
        java.util.Map.entry("bricks", 0.35f),
        java.util.Map.entry("small_stripes", 0.4f),
        java.util.Map.entry("cross", 0.35f),
        java.util.Map.entry("straight_cross", 0.3f),
        java.util.Map.entry("curly_border", 0.3f),
        java.util.Map.entry("border", 0.25f),
        java.util.Map.entry("triangles_bottom", 0.25f),
        java.util.Map.entry("triangles_top", 0.25f),
        java.util.Map.entry("triangle_bottom", 0.2f),
        java.util.Map.entry("triangle_top", 0.2f),
        java.util.Map.entry("rhombus", 0.2f),
        java.util.Map.entry("stripe_center", 0.15f),
        java.util.Map.entry("stripe_middle", 0.15f),
        java.util.Map.entry("circle", 0.15f),
        java.util.Map.entry("stripe_bottom", 0.12f),
        java.util.Map.entry("stripe_top", 0.12f),
        java.util.Map.entry("stripe_left", 0.12f),
        java.util.Map.entry("stripe_right", 0.12f),
        java.util.Map.entry("stripe_downleft", 0.18f),
        java.util.Map.entry("stripe_downright", 0.18f),
        java.util.Map.entry("square_bottom_left", 0.1f),
        java.util.Map.entry("square_bottom_right", 0.1f),
        java.util.Map.entry("square_top_left", 0.1f),
        java.util.Map.entry("square_top_right", 0.1f));

    private static float coverageOf(String patternId) {
        int colon = patternId.indexOf(':');
        String path = colon >= 0 ? patternId.substring(colon + 1) : patternId;
        Float known = PATTERN_COVERAGE.get(path);
        return known != null ? known : 0.25f;
    }

    private static final float IDENTITY_SHARE_FLOOR = 0.05f;

    public static java.util.List<DyeColor> identityDyes(DyeColor base,
            java.util.List<Settlement.BannerLayer> layers) {
        float[] share = new float[16];
        share[base.getId()] = 1f;
        for (Settlement.BannerLayer layer : layers) {
            float coverage = coverageOf(layer.patternId());
            for (int i = 0; i < share.length; i++) share[i] *= (1f - coverage);
            share[layer.colorId() & 15] += coverage;
        }
        java.util.List<Integer> ranked = new java.util.ArrayList<>();
        for (int i = 0; i < share.length; i++) {
            if (share[i] >= IDENTITY_SHARE_FLOOR) ranked.add(i);
        }
        final float[] shares = share;
        ranked.sort((a, b) -> Float.compare(shares[b], shares[a]));
        java.util.List<DyeColor> out = new java.util.ArrayList<>(ranked.size());
        for (int id : ranked) out.add(DyeColor.byId(id));
        if (out.isEmpty()) out.add(base);
        return out;
    }

    public static ChatFormatting formattingFor(DyeColor dye) {
        return switch (dye) {
            case WHITE -> ChatFormatting.WHITE;
            case ORANGE -> ChatFormatting.GOLD;
            case MAGENTA -> ChatFormatting.LIGHT_PURPLE;
            case LIGHT_BLUE -> ChatFormatting.AQUA;
            case YELLOW -> ChatFormatting.YELLOW;
            case LIME -> ChatFormatting.GREEN;
            case PINK -> ChatFormatting.LIGHT_PURPLE;
            case GRAY -> ChatFormatting.DARK_GRAY;
            case LIGHT_GRAY -> ChatFormatting.GRAY;
            case CYAN -> ChatFormatting.DARK_AQUA;
            case PURPLE -> ChatFormatting.DARK_PURPLE;
            case BLUE -> ChatFormatting.BLUE;
            case BROWN -> ChatFormatting.GOLD;
            case GREEN -> ChatFormatting.DARK_GREEN;
            case RED -> ChatFormatting.RED;
            case BLACK -> ChatFormatting.DARK_GRAY;
        };
    }

    private static float yawToward(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        // Minecraft yaw convention: 0 = south, hence the -90 offset on the atan2 angle.
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }

    public static boolean isBanner(BlockState state) {
        return state.is(BlockTags.BANNERS);
    }

    public static void raise(ServerLevel level, Settlement settlement, BlockPos pos) {
        convertToFactionDesign(level, settlement, pos);
        settlement.setBannerPos(pos.immutable());
        applyDesignToBlock(level, settlement);
        if (settlement.removeStatusEffectsByKey("bannerbound.status.banner_lost")) {
            SettlementManager.broadcastStatusEffectsToMembers(level.getServer(), settlement);
        }
        SettlementData.get(level.getServer().overworld()).setDirty();
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BELL_BLOCK,
            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.4f);
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = level.getServer().getPlayerList().getPlayer(memberId);
            if (member == null) continue;
            member.sendSystemMessage(Component.translatable("bannerbound.banner.raised",
                settlement.factionName()).withStyle(settlement.identityFormatting()));
        }
    }

    public static void lose(ServerLevel level, Settlement settlement, BlockPos pos,
                            boolean memberBreak, String breakerName) {
        MinecraftServer server = level.getServer();
        settlement.setBannerPos(null);
        SettlementData.get(server.overworld()).setDirty();

        if (!memberBreak) {
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.6f);
            settlement.addStatusEffect(new StatusEffect(
                UUID.randomUUID(), "bannerbound.status.banner_lost",
                java.util.List.of(), StatusEffectIcon.ALERT, 0, 72_000));
            SettlementManager.broadcastStatusEffectsToMembers(server, settlement);
        }
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member == null) continue;
            PacketDistributor.sendToPlayer(member, CloseSettlementScreensPayload.INSTANCE);
            if (memberBreak) {
                member.sendSystemMessage(Component.translatable("bannerbound.banner.taken_down",
                    breakerName).withStyle(ChatFormatting.YELLOW));
            } else {
                member.sendSystemMessage(Component.translatable("bannerbound.banner.fallen")
                    .withStyle(ChatFormatting.RED));
                boolean heardOnSite = member.level() == level
                    && member.blockPosition().closerThan(pos, 48);
                if (!heardOnSite) {
                    member.playNotifySound(net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                        net.minecraft.sounds.SoundSource.AMBIENT, 1.0f, 0.6f);
                }
            }
        }
    }

    public static void validate(ServerLevel level, Settlement settlement) {
        BlockPos pos = settlement.bannerPos();
        if (pos == null || !level.isLoaded(pos)) return;
        if (!isBanner(level.getBlockState(pos))) {
            if (DiplomacyManager.consumeSupportLossAsTheft(level, settlement, pos)) {
                return;
            }
            lose(level, settlement, pos, false, "");
            return;
        }
        if (!DiplomacyManager.isPublicStandardValid(level, settlement)) {
            lose(level, settlement, pos, false, "");
        }
    }

    public static boolean requireRaised(ServerLevel level, ServerPlayer player, Settlement settlement) {
        validate(level, settlement);
        if (settlement.hasFactionBanner()
                && DiplomacyManager.isPublicStandardValid(level, settlement)) {
            return true;
        }
        player.sendSystemMessage(Component.translatable("bannerbound.banner.required")
            .withStyle(ChatFormatting.RED));
        return false;
    }
}
