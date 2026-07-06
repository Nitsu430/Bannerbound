package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
import com.bannerbound.antiquity.recipe.GrogRecipeManager;
import com.bannerbound.antiquity.recipe.MortarRecipe;
import com.bannerbound.antiquity.recipe.MortarRecipeManager;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * NPC driver for the Brewery workshop (the generic Crafter staffs it). The work block is a
 * fermentation-trough POOL anchor (a connected run counts as one station); the brewer keeps every
 * pool producing: PESTLE raw fermentables (berries) at the workshop's Mortar and Pestle, FILL the
 * pool with hand-scooped water, CHARGE it with a pestled item - then the trough's own ferment
 * timer runs while the brewer walks away, and citizens drink straight from the pool
 * ({@code GrogDrinkGoal}), so there is no COLLECT step and no orderable "grog" item.
 *
 * <p>Waiting-stage contract (see {@code Workshops.wantsAnother}): the ferment is the unattended
 * wait, but its in-flight gate is structural - {@code chargePool} refuses an already-charged pool
 * and finished grog never spoils, so CHARGE is idempotent per pool and can never overproduce.
 * A charged pool needs nothing: citizens self-serve and the empty pool re-enters the loop after
 * the last serving drains. Tending is FILL-first (more water = more servings; top-up is blocked
 * once charged), CHARGE only once no more water can be fetched - so a rain-fed pool with enough
 * water for the recipe still brews rather than stalling forever. Only PESTLE makes an item; it
 * follows the normal orders -> min-stock governor (two passes, ordered first) plus one standing
 * demand: a charge item for each of this workshop's uncharged pools, and not a berry more.
 * The pestle repertoire is fully data-driven: dry mortar recipes whose ground output is a grog
 * input, so a modpack grog with a new pestled input auto-extends the brewer.
 *
 * <p>Craft steps are discriminated by shape, the Tannery idiom: FILL has no inputs and no result,
 * CHARGE has one input and no result, PESTLE is the only step with a result. Work spots: PESTLE
 * at the mortar, FILL at the pool cell nearest water, CHARGE at the anchor. possibleOutputs lists
 * only the pestled charge items (players can stockpile them as trade goods) - grog itself is
 * standing availability in the pool, never an item, deliberately absent from min-stock rows.
 * Stockers SUPPLY raw fermentables as a small rolling buffer while any pestle demand stands, and
 * retainedItems KEEPs raws plus every pestled charge item so a batch waiting for its pool to
 * drain is not hauled back to the stockpile between crafts.
 */
public class BrewerExecutor implements WorkExecutor {
    private static final int BEATS = 3;
    private static final int FILL_TICKS = 40;
    private static final int CHARGE_TICKS = 40;
    private static final int PESTLE_TICKS = 60;

    @Nullable
    @Override
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        if (sl.getBlockState(workBlock).getBlock() instanceof FermentationTroughBlock
                && !FermentationTroughBlock.isPoolCharged(sl, workBlock)) {
            int units = FermentationTroughBlock.poolUnits(sl, workBlock);
            int capacity = FermentationTroughBlock.poolCapacity(sl, workBlock);
            boolean waterReachable = FermentationTroughBlock.findScoopWater(sl, workBlock) != null;
            // Order matters: FILL before CHARGE, or partially-filled pools brew thin batches.
            if (units < capacity && waterReachable) {
                return new Craft(List.of(), ItemStack.EMPTY, FILL_TICKS, BEATS);
            }
            Item charge = chargeItemFor(sl, workshop, units);
            if (charge != null) {
                return new Craft(List.of(new ItemStack(charge)), ItemStack.EMPTY, CHARGE_TICKS, BEATS);
            }
        }
        Craft ordered = tryPestle(sl, settlement, workshop, workBlock, true);
        if (ordered != null) return ordered;
        return tryPestle(sl, settlement, workshop, workBlock, false);
    }

    @Nullable
    private static Item chargeItemFor(ServerLevel sl, Workshop workshop, int units) {
        for (Item input : GrogRecipeManager.inputs()) {
            if (WorkshopStorage.count(sl, workshop, input) <= 0) continue;
            var match = GrogRecipeManager.findForInput(input);
            if (match != null && units >= match.getValue().minWaterUnits()) return input;
        }
        return null;
    }

    @Nullable
    private Craft tryPestle(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock,
                            boolean ordersOnly) {
        for (MortarRecipe recipe : brewablePestles()) {
            Item result = recipe.resultItem().getItem();
            if (!CraftGating.canProduceAt(sl, workBlock, result)) continue;
            boolean wanted = ordersOnly
                ? Workshops.orderedCraftCount(workshop, result) > 0
                : Workshops.wantedByMinStock(sl, settlement, workshop, recipe.resultItem())
                    || standingPoolDemand(sl, workshop);
            if (!wanted) continue;
            Item raw = stockedIngredient(sl, workshop, recipe);
            if (raw == null) continue;
            return new Craft(List.of(new ItemStack(raw)), recipe.resultItem().copy(), PESTLE_TICKS, BEATS + 1);
        }
        return null;
    }

    private static boolean standingPoolDemand(ServerLevel sl, Workshop workshop) {
        int pools = BreweryWorkshopRules.unchargedPools(sl, workshop);
        if (pools <= 0) return false;
        int stocked = 0;
        for (Item input : GrogRecipeManager.inputs()) {
            stocked += WorkshopStorage.count(sl, workshop, input);
        }
        return stocked < pools;
    }

    private static List<MortarRecipe> brewablePestles() {
        Set<Item> grogInputs = GrogRecipeManager.inputs();
        List<MortarRecipe> out = new ArrayList<>();
        for (MortarRecipe r : MortarRecipeManager.all()) {
            if (r.baseLiquid().isEmpty() && !r.resultItem().isEmpty()
                    && grogInputs.contains(r.resultItem().getItem())) {
                out.add(r);
            }
        }
        return out;
    }

    @Nullable
    private static Item stockedIngredient(ServerLevel sl, Workshop workshop, MortarRecipe recipe) {
        for (ItemStack option : recipe.ingredient().getItems()) {
            if (WorkshopStorage.count(sl, workshop, option.getItem()) > 0) return option.getItem();
        }
        return null;
    }

    private static boolean isFill(Craft craft) {
        return craft.inputs().isEmpty() && craft.result().isEmpty();
    }

    private static boolean isCharge(Craft craft) {
        return !craft.inputs().isEmpty() && craft.result().isEmpty();
    }

    private static boolean isPestle(Craft craft) {
        return !craft.result().isEmpty();
    }

    @Override
    public BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                               BlockPos workBlock, Craft craft) {
        if (isPestle(craft)) {
            BlockPos mortar = BreweryWorkshopRules.findMortar(sl, workshop);
            return mortar != null ? mortar : workBlock;
        }
        if (isFill(craft)) {
            BlockPos cell = FermentationTroughBlock.findScoopWater(sl, workBlock);
            return cell != null ? cell : workBlock;
        }
        return workBlock;
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isFill(craft)) {
            FermentationTroughBlock.npcAddWater(sl, workBlock, FermentationTroughBlockEntity.UNITS_PER_CELL);
            return ItemStack.EMPTY;
        }
        if (isCharge(craft)) {
            ItemStack input = craft.inputs().get(0);
            // npcCharge false = a player charged/drained the pool mid-walk; return the item so it goes back to storage, never void it.
            return FermentationTroughBlock.npcCharge(sl, workBlock, input.getItem())
                ? ItemStack.EMPTY : input.copy();
        }
        return craft.result().copy();
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft, int beatIndex) {
        BlockPos at = citizen.blockPosition();
        if (isFill(craft)) {
            sl.playSound(null, at, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.6F, 1.1F);
            splash(sl, citizen, 6);
        } else if (isCharge(craft)) {
            sl.playSound(null, at, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.6F, 0.8F);
            splash(sl, citizen, 8);
        } else {
            sl.playSound(null, at, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.4F, 0.6F);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIMSON_SPORE,
                citizen.getX(), citizen.getY() + 1.0, citizen.getZ(), 4, 0.15, 0.1, 0.15, 0.0);
        }
    }

    private static void splash(ServerLevel sl, CitizenEntity citizen, int count) {
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
            citizen.getX(), citizen.getY() + 0.8, citizen.getZ(), count, 0.25, 0.1, 0.25, 0.0);
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (MortarRecipe r : brewablePestles()) {
            if (CraftGating.canProduceAt(sl, workBlock, r.resultItem().getItem())) {
                out.add(r.resultItem().copy());
            }
        }
        return out;
    }

    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (MortarRecipe recipe : brewablePestles()) {
            ItemStack result = recipe.resultItem();
            boolean wanted = Workshops.orderedCraftCount(workshop, result.getItem()) > 0
                || Workshops.wantedByMinStock(sl, settlement, workshop, result)
                || standingPoolDemand(sl, workshop);
            if (!wanted) continue;
            for (ItemStack option : recipe.ingredient().getItems()) {
                addDeficit(out, sl, workshop, option.getItem(), 4);
            }
        }
        return out;
    }

    private static void addDeficit(List<ItemStack> out, ServerLevel sl, Workshop workshop, Item item, int buffer) {
        int have = WorkshopStorage.count(sl, workshop, item);
        if (have < buffer) out.add(new ItemStack(item, buffer - have));
    }

    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        Set<Item> keep = new LinkedHashSet<>(GrogRecipeManager.inputs());
        for (MortarRecipe recipe : brewablePestles()) {
            for (ItemStack option : recipe.ingredient().getItems()) {
                keep.add(option.getItem());
            }
        }
        return keep;
    }
}
