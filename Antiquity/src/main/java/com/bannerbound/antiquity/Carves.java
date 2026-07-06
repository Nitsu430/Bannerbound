package com.bannerbound.antiquity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.CraftingStoneBlock;
import com.bannerbound.antiquity.block.MasonsBenchBlock;
import com.bannerbound.antiquity.block.TanningRackBlock;
import com.bannerbound.antiquity.block.WoodworkingTableBlock;
import com.bannerbound.antiquity.item.KnifeItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The canonical "right-click a block with a tool -> it becomes another block" table -- the single
 * source of truth for the world-interaction carves that {@link AntiquityEvents} executes (knife on
 * cobble -> Crafting Stone, bone blade on a log -> Drying Rack, axe on a lone log -> Chopping
 * Stump, ...). Each provider does only detection and result-state construction -- no placement,
 * sounds, durability, or research gate -- answering "if the player committed this gesture right
 * here, which positions would become which blockstates?". The client ghost preview
 * (CarveClientEvents) consumes this directly, so the silhouette the player sees is computed from
 * the exact logic that performs the carve; the like-named handlers in {@link AntiquityEvents}
 * still own execution (placement + side effects + authoritative gating), so until a handler is
 * folded in to read its target state from here, keep its detection in lockstep with the provider
 * of the same name. Provider order matters: the Tanning Rack (a 2x2 log square) is tried before
 * the single-log carves, mirroring how the drying-rack/fermentation-trough handlers defer to it.
 * commit() is the server landing point for CarveCommitPayload -- the previewed block is
 * client-side air and can't be ray-clicked, so the use-key press arrives as a payload and is
 * replayed through the normal useItemOn pipeline, re-firing the real AntiquityEvents handler with
 * all of its gating, sounds, particles and durability intact. Result.gateItem is the item whose
 * unlock state gates the preview; the clayed-cobblestone carve is really gated on the Kilns
 * research, so its preview only approximates via item unlock. Block-entity-rendered results
 * (Chopping Stump wood skin, Bloomery) preview with their default/base model only.
 */
public final class Carves {

    public record Result(Map<BlockPos, BlockState> blocks, Item gateItem) {}

    @FunctionalInterface
    public interface Provider {
        @Nullable Result resolve(Level level, BlockPos pos, Player player, ItemStack held);
    }

    private static final List<Provider> PROVIDERS = List.of(
        Carves::craftingStone,
        Carves::tanningRack,          // 2x2 log square -- must precede the single-log carves
        Carves::dryingRack,
        Carves::fermentationTrough,
        Carves::choppingStump,
        Carves::woodworkingTable,
        Carves::masonsBench,
        Carves::bloomery,
        Carves::clayedCobblestone
    );

    private Carves() {}

    public static @Nullable Result resolve(Level level, BlockPos pos, Player player, ItemStack held) {
        if (held.isEmpty()) {
            return null;
        }
        for (Provider provider : PROVIDERS) {
            Result result = provider.resolve(level, pos, player, held);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    public static void commit(ServerPlayer player, BlockPos anchor) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(anchor)) {
            return;
        }
        // Reach sanity bound -- the client supplies the anchor, so never act on a distant one.
        if (player.distanceToSqr(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5) > 64.0) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        // Anti-spoof: replay the use-on only if the server agrees this is a carve, else a forged payload could right-click anything here.
        if (resolve(level, anchor, player, held) == null) {
            return;
        }
        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(anchor), player.getDirection().getOpposite(), anchor, false);
        player.gameMode.useItemOn(player, level, held, InteractionHand.MAIN_HAND, hit);
    }

    private static @Nullable Result craftingStone(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!(held.getItem() instanceof KnifeItem) && !held.is(BannerboundAntiquity.FLINT_BLADE.get())) {
            return null;
        }
        CraftingStoneBlock.Material material = KnifeItem.materialFor(level.getBlockState(pos));
        if (material == null) {
            return null;
        }
        BlockState result = BannerboundAntiquity.CRAFTING_STONE.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, player.getDirection().getOpposite())
            .setValue(CraftingStoneBlock.MATERIAL, material);
        return single(pos, result, BannerboundAntiquity.CRAFTING_STONE.get().asItem());
    }

    private static @Nullable Result tanningRack(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(AntiquityEvents.CUTTING_TOOLS) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
            return null;
        }
        Direction facing = player.getDirection().getOpposite();
        Direction width = facing.getClockWise();
        BlockPos master = findLogSquare(level, pos, width);
        if (master == null) {
            return null;
        }
        BlockState base = BannerboundAntiquity.TANNING_RACK.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, facing);
        BlockPos w = master.relative(width);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(master, base.setValue(TanningRackBlock.PART, 0));
        blocks.put(w, base.setValue(TanningRackBlock.PART, 1));
        blocks.put(master.above(), base.setValue(TanningRackBlock.PART, 2));
        blocks.put(w.above(), base.setValue(TanningRackBlock.PART, 3));
        return new Result(blocks, BannerboundAntiquity.TANNING_RACK.get().asItem());
    }

    private static @Nullable Result dryingRack(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.BONE_BLADE.get())) {
            return null;
        }
        BlockState logState = level.getBlockState(pos);
        if (!logState.is(BlockTags.LOGS)) {
            return null;
        }
        Direction facing = player.getDirection().getOpposite();
        if (findLogSquare(level, pos, facing.getClockWise()) != null) {
            return null; // a full 2x2 square is a Tanning Rack
        }
        var rack = BannerboundAntiquity.DRYING_RACK_BY_WOOD.getOrDefault(
            woodKey(logState.getBlock(), BannerboundAntiquity.DRYING_RACK_BY_WOOD),
            BannerboundAntiquity.DRYING_RACK_BY_WOOD.get("oak"));
        BlockState result = rack.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, facing);
        return single(pos, result, rack.get().asItem());
    }

    private static @Nullable Result fermentationTrough(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.BONE_KNIFE.get())) {
            return null;
        }
        BlockState logState = level.getBlockState(pos);
        if (!logState.is(BlockTags.LOGS)) {
            return null;
        }
        Direction facing = player.getDirection().getOpposite();
        if (findLogSquare(level, pos, facing.getClockWise()) != null) {
            return null; // a full 2x2 square is a Tanning Rack
        }
        var trough = BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD.getOrDefault(
            woodKey(logState.getBlock(), BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD),
            BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD.get("oak"));
        BlockState result = trough.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, facing);
        return single(pos, result, trough.get().asItem());
    }

    private static @Nullable Result choppingStump(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!(held.getItem() instanceof AxeItem) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
            return null;
        }
        for (Direction d : Direction.values()) {
            BlockState logState = level.getBlockState(pos.relative(d));
            if (logState.is(BlockTags.LOGS) || logState.is(BlockTags.LEAVES)) {
                return null;
            }
        }
        return single(pos, BannerboundAntiquity.CHOPPING_STUMP.get().defaultBlockState(),
            BannerboundAntiquity.CHOPPING_STUMP.get().asItem());
    }

    private static @Nullable Result woodworkingTable(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.BONE_SAW.get()) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
            return null;
        }
        Direction dir = firstHorizontalNeighbour(level, pos, p -> level.getBlockState(p).is(BlockTags.LOGS));
        if (dir == null) {
            return null;
        }
        BlockState main = BannerboundAntiquity.WOODWORKING_TABLE.get().defaultBlockState()
            .setValue(WoodworkingTableBlock.FACING, dir)
            .setValue(WoodworkingTableBlock.MAIN, true);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, main);
        blocks.put(pos.relative(dir), main.setValue(WoodworkingTableBlock.MAIN, false));
        return new Result(blocks, BannerboundAntiquity.WOODWORKING_TABLE.get().asItem());
    }

    private static @Nullable Result masonsBench(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.STONE_CHISEL.get()) || !isBenchStone(level.getBlockState(pos))) {
            return null;
        }
        Direction dir = firstHorizontalNeighbour(level, pos, p -> isBenchStone(level.getBlockState(p)));
        if (dir == null) {
            return null;
        }
        BlockState main = BannerboundAntiquity.MASONS_BENCH.get().defaultBlockState()
            .setValue(MasonsBenchBlock.FACING, dir)
            .setValue(MasonsBenchBlock.MAIN, true);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, main);
        blocks.put(pos.relative(dir), main.setValue(MasonsBenchBlock.MAIN, false));
        return new Result(blocks, BannerboundAntiquity.MASONS_BENCH.get().asItem());
    }

    private static @Nullable Result bloomery(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(Items.COAL_BLOCK)) {
            return null;
        }
        BlockPos top = pos.above();
        if (!level.getBlockState(pos).is(Blocks.MUD_BRICKS) || !level.getBlockState(top).is(Blocks.MUD_BRICKS)) {
            return null;
        }
        BlockState lower = BannerboundAntiquity.BLOOMERY.get().defaultBlockState()
            .setValue(BloomeryBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(BloomeryBlock.FACING, player.getDirection().getOpposite());
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, lower);
        blocks.put(top, lower.setValue(BloomeryBlock.HALF, DoubleBlockHalf.UPPER));
        return new Result(blocks, BannerboundAntiquity.BLOOMERY.get().asItem());
    }

    private static @Nullable Result clayedCobblestone(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(Items.CLAY_BALL) || !level.getBlockState(pos).is(Blocks.COBBLESTONE)) {
            return null;
        }
        return single(pos, BannerboundAntiquity.CLAYED_COBBLESTONE.get().defaultBlockState(),
            BannerboundAntiquity.CLAYED_COBBLESTONE.get().asItem());
    }

    public static @Nullable BlockPos findLogSquare(Level level, BlockPos clicked, Direction width) {
        BlockPos[] candidates = {
            clicked,
            clicked.relative(width.getOpposite()),
            clicked.below(),
            clicked.below().relative(width.getOpposite())
        };
        for (BlockPos m : candidates) {
            if (isLog(level, m) && isLog(level, m.relative(width))
                    && isLog(level, m.above()) && isLog(level, m.relative(width).above())) {
                return m;
            }
        }
        return null;
    }

    private static boolean isLog(Level level, BlockPos p) {
        return level.getBlockState(p).is(BlockTags.LOGS);
    }

    private static boolean isBenchStone(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
    }

    @FunctionalInterface
    private interface PosTest {
        boolean test(BlockPos pos);
    }

    private static @Nullable Direction firstHorizontalNeighbour(Level level, BlockPos pos, PosTest test) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (test.test(pos.relative(d))) {
                return d;
            }
        }
        return null;
    }

    private static String woodKey(Block log, Map<String, ?> available) {
        String path = BuiltInRegistries.BLOCK.getKey(log).getPath();
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        for (String suffix : new String[] { "_log", "_wood", "_stem", "_hyphae" }) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        return available.containsKey(path) ? path : "oak";
    }

    private static Result single(BlockPos pos, BlockState state, Item gateItem) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, state);
        return new Result(blocks, gateItem);
    }
}
