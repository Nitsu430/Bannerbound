package com.bannerbound.core.compat.jei;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.Config;
import com.bannerbound.core.client.ClientResearchState;
import com.bannerbound.core.client.ClientStartingItems;
import com.bannerbound.core.client.UnknownItemHelper;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * JEI plugin implementing Bannerbound's knowledge gate: items and recipes the local player hasn't
 * unlocked yet are REMOVED from JEI (no "?" wall, no progression spoiler) rather than masked, while
 * known items (starting items + research unlocks) stay so recipes built from them still resolve.
 * Config.JEI_SHOW_UNKNOWN bypasses the whole gate. Wired to ClientResearchState and
 * ClientStartingItems listeners so visibility tracks research live; applyKnowledgeGate is the single
 * entry point and re-runs on every knowledge change.
 *
 * Two invariants this class exists to defend:
 *  - Recipes are gated by OUTPUT, not input: a recipe that produces something the player can make
 *    must always be visible even if an input is still unknown (the input keeps its own "?" icon).
 *    That output short-circuit keeps starting-item recipes like flint_knife in JEI.
 *  - Vanilla crafting is gated by GRID SIZE, not knowledge: the 2x2 inventory grid is always
 *    available so every 2x2-fitting recipe shows, and 3x3 recipes are always hidden (this mod has no
 *    crafting table). Knowledge-gating crafting left flint_knife stuck hidden, so it is deliberately
 *    excluded; other vanilla types (smelting, stonecutting, ...) and custom station recipes stay
 *    knowledge-gated.
 *
 * Startup races drove the rest. JEI caches each ingredient's search name once at add time, so items
 * indexed before the knowledge set synced are stuck searchable only as "unknown item"; once
 * knowledge lands we re-add the known items a single time (refreshedKnownNames) to refresh those
 * cached names. The item-visibility sync must not run until ClientStartingItems has synced: if it
 * runs first, every starting item looks unknown and gets removed, and re-adding them later does NOT
 * un-hide their crafting recipes in JEI -- leaving e.g. flint_knife permanently absent. Each vanilla
 * type is fully unhidden then re-hidden to only-currently-unknown, making the gate self-correcting
 * and idempotent.
 */
@JeiPlugin
@OnlyIn(Dist.CLIENT)
public final class BannerboundCoreJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "knowledge_gate");
    private static final ResourceLocation ITEM_TAG_RECIPES =
        ResourceLocation.fromNamespaceAndPath("minecraft", "tag_recipes/item");
    private static final ResourceLocation BLOCK_TAG_RECIPES =
        ResourceLocation.fromNamespaceAndPath("minecraft", "tag_recipes/block");

    private final Map<RecipeType<?>, List<?>> hiddenRecipes = new HashMap<>();
    private final List<ItemStack> itemIngredientUniverse = new ArrayList<>();
    private final List<ItemStack> hiddenItemIngredients = new ArrayList<>();
    private boolean refreshedKnownNames = false;
    private final Runnable knowledgeListener = this::applyKnowledgeGate;
    private IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(BannerboundCore.REGISTRATION_TABLET.get(),
            Component.translatable("bannerbound.jei.info.registration_tablet"));
        registration.addIngredientInfo(BannerboundCore.REGISTRATION_PAPER.get(),
            Component.translatable("bannerbound.jei.info.registration_paper"));
        registration.addIngredientInfo(BannerboundCore.FOREMANS_ROD.get(),
            Component.translatable("bannerbound.jei.info.foremans_rod"));
        registration.addIngredientInfo(BannerboundCore.HOUSING_ORDERS.get(),
            Component.translatable("bannerbound.jei.info.housing_orders"));
        registration.addIngredientInfo(BannerboundCore.WORKSHOP_ROD.get(),
            Component.translatable("bannerbound.jei.info.workshop_rod"));
        registration.addIngredientInfo(Items.CAMPFIRE,
            Component.translatable("bannerbound.jei.info.campfire"));
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        refreshedKnownNames = false;
        ClientResearchState.addKnowledgeListener(knowledgeListener);
        ClientStartingItems.addListener(knowledgeListener);
        applyKnowledgeGate();
    }

    @Override
    public void onRuntimeUnavailable() {
        ClientResearchState.removeKnowledgeListener(knowledgeListener);
        ClientStartingItems.removeListener(knowledgeListener);
        if (runtime != null) {
            restoreHiddenItemIngredients(runtime.getIngredientManager());
            unhideAll(runtime.getRecipeManager());
        }
        runtime = null;
        hiddenRecipes.clear();
        itemIngredientUniverse.clear();
        hiddenItemIngredients.clear();
    }

    private void applyKnowledgeGate() {
        if (runtime == null) {
            return;
        }
        IRecipeManager jeiRecipes = runtime.getRecipeManager();
        if (Config.JEI_SHOW_UNKNOWN.get()) {
            restoreHiddenItemIngredients(runtime.getIngredientManager());
            unhideAll(jeiRecipes);
            return;
        }
        // Order-dependent: never remove item ingredients until starting-items has synced, or their crafting recipes stay hidden forever.
        if (ClientStartingItems.isLoaded()) {
            syncItemIngredientVisibility(runtime.getIngredientManager());
        } else {
            restoreHiddenItemIngredients(runtime.getIngredientManager());
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        syncVanillaType(jeiRecipes, level, RecipeTypes.CRAFTING,
            net.minecraft.world.item.crafting.RecipeType.CRAFTING);
        syncVanillaType(jeiRecipes, level, RecipeTypes.STONECUTTING,
            net.minecraft.world.item.crafting.RecipeType.STONECUTTING);
        syncVanillaType(jeiRecipes, level, RecipeTypes.SMELTING,
            net.minecraft.world.item.crafting.RecipeType.SMELTING);
        syncVanillaType(jeiRecipes, level, RecipeTypes.SMOKING,
            net.minecraft.world.item.crafting.RecipeType.SMOKING);
        syncVanillaType(jeiRecipes, level, RecipeTypes.BLASTING,
            net.minecraft.world.item.crafting.RecipeType.BLASTING);
        syncVanillaType(jeiRecipes, level, RecipeTypes.CAMPFIRE_COOKING,
            net.minecraft.world.item.crafting.RecipeType.CAMPFIRE_COOKING);
        syncVanillaType(jeiRecipes, level, RecipeTypes.SMITHING,
            net.minecraft.world.item.crafting.RecipeType.SMITHING);
        syncTagType(jeiRecipes, ITEM_TAG_RECIPES);
        syncTagType(jeiRecipes, BLOCK_TAG_RECIPES);
    }

    private <I extends RecipeInput, R extends Recipe<I>> void syncVanillaType(
        IRecipeManager jeiRecipes,
        ClientLevel level,
        RecipeType<RecipeHolder<R>> jeiType,
        net.minecraft.world.item.crafting.RecipeType<R> vanillaType
    ) {
        List<RecipeHolder<R>> all = level.getRecipeManager().getAllRecipesFor(vanillaType).stream().toList();
        jeiRecipes.unhideRecipes(jeiType, all);
        List<RecipeHolder<R>> unknown = all.stream()
            .filter(holder -> shouldHideRecipe(holder, level))
            .toList();
        replaceHidden(jeiRecipes, jeiType, unknown);
    }

    private void syncTagType(IRecipeManager recipeManager, ResourceLocation recipeTypeId) {
        recipeManager.getRecipeType(recipeTypeId)
            .ifPresent(recipeType -> syncTagType(recipeManager, recipeType));
    }

    private <T> void syncTagType(IRecipeManager recipeManager, RecipeType<T> recipeType) {
        IRecipeCategory<T> category = recipeManager.getRecipeCategory(recipeType);
        List<T> unknown = recipeManager.createRecipeLookup(recipeType)
            .includeHidden()
            .get()
            .filter(recipe -> containsUnknownItem(recipeManager, category, recipe))
            .toList();
        replaceHidden(recipeManager, recipeType, unknown);
    }

    private static <T> boolean containsUnknownItem(IRecipeManager recipeManager,
                                                   IRecipeCategory<T> category,
                                                   T recipe) {
        IIngredientSupplier ingredients = recipeManager.getRecipeIngredients(category, recipe);
        Collection<ITypedIngredient<?>> outputs = ingredients.getIngredients(RecipeIngredientRole.OUTPUT);
        if (hasOnlyKnownOutputs(outputs)) {
            return false;
        }
        return hasUnknownItem(ingredients.getIngredients(RecipeIngredientRole.INPUT))
            || hasUnknownItem(outputs);
    }

    private static boolean hasOnlyKnownOutputs(Collection<ITypedIngredient<?>> outputs) {
        boolean sawOutput = false;
        for (ITypedIngredient<?> ingredient : outputs) {
            ItemStack stack = ingredient.getItemStack().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                continue;
            }
            sawOutput = true;
            if (shouldHideStack(stack)) {
                return false;
            }
        }
        return sawOutput;
    }

    private static boolean hasUnknownItem(Collection<ITypedIngredient<?>> ingredients) {
        for (ITypedIngredient<?> ingredient : ingredients) {
            if (ingredient.getItemStack().map(BannerboundCoreJeiPlugin::shouldHideStack).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldHideStack(ItemStack output) {
        return output != null && !output.isEmpty() && UnknownItemHelper.isUnknownForLocalPlayer(output);
    }

    public static boolean shouldHideIngredient(Ingredient ingredient) {
        if (ingredient == null) {
            return false;
        }
        for (ItemStack stack : ingredient.getItems()) {
            if (shouldHideStack(stack)) {
                return true;
            }
        }
        return false;
    }

    public static boolean allChoicesHidden(Collection<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (!shouldHideStack(stack)) {
                return false;
            }
        }
        return true;
    }

    private <I extends RecipeInput, R extends Recipe<I>> boolean shouldHideRecipe(
        RecipeHolder<R> holder,
        ClientLevel level
    ) {
        R recipe = holder.value();
        // Gate crafting by grid size, NOT knowledge: show all 2x2-fitting recipes, hide 3x3 (no crafting table); knowledge-gating here strands flint_knife.
        if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe) {
            return !craftingRecipe.canCraftInDimensions(2, 2);
        }
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result != null && !result.isEmpty() && !shouldHideStack(result)) {
            return false;
        }
        if (shouldHideStack(result)) {
            return true;
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (shouldHideIngredient(ingredient)) {
                return true;
            }
        }
        return false;
    }

    private void syncItemIngredientVisibility(IIngredientManager ingredientManager) {
        rememberItemIngredients(ingredientManager.getAllItemStacks());
        rememberItemIngredients(hiddenItemIngredients);

        List<ItemStack> nextHidden = itemIngredientUniverse.stream()
            .filter(BannerboundCoreJeiPlugin::shouldHideStack)
            .map(BannerboundCoreJeiPlugin::normalizedCopy)
            .toList();
        List<ItemStack> toRestore = hiddenItemIngredients.stream()
            .filter(stack -> !containsStack(nextHidden, stack))
            .map(BannerboundCoreJeiPlugin::normalizedCopy)
            .toList();
        List<ItemStack> toHide = nextHidden.stream()
            .filter(stack -> !containsStack(hiddenItemIngredients, stack))
            .map(BannerboundCoreJeiPlugin::normalizedCopy)
            .toList();

        if (!toRestore.isEmpty()) {
            ingredientManager.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, toRestore);
        }
        if (!toHide.isEmpty()) {
            ingredientManager.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, toHide);
        }

        hiddenItemIngredients.clear();
        hiddenItemIngredients.addAll(nextHidden);

        if (!refreshedKnownNames) {
            refreshedKnownNames = true;
            List<ItemStack> knownVisible = itemIngredientUniverse.stream()
                .filter(stack -> !shouldHideStack(stack))
                .map(BannerboundCoreJeiPlugin::normalizedCopy)
                .toList();
            if (!knownVisible.isEmpty()) {
                ingredientManager.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, knownVisible);
                ingredientManager.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, knownVisible);
            }
        }
    }

    private void restoreHiddenItemIngredients(IIngredientManager ingredientManager) {
        if (hiddenItemIngredients.isEmpty()) {
            return;
        }
        ingredientManager.addIngredientsAtRuntime(VanillaTypes.ITEM_STACK,
            hiddenItemIngredients.stream()
                .map(BannerboundCoreJeiPlugin::normalizedCopy)
                .toList());
        hiddenItemIngredients.clear();
    }

    private void rememberItemIngredients(Collection<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!containsStack(itemIngredientUniverse, stack)) {
                itemIngredientUniverse.add(normalizedCopy(stack));
            }
        }
    }

    private static boolean containsStack(Collection<ItemStack> stacks, ItemStack wanted) {
        for (ItemStack stack : stacks) {
            if (ItemStack.isSameItemSameComponents(stack, wanted)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack normalizedCopy(ItemStack stack) {
        return stack.copyWithCount(1);
    }

    private void unhideAll(IRecipeManager recipeManager) {
        for (Map.Entry<RecipeType<?>, List<?>> entry : hiddenRecipes.entrySet()) {
            unhideRaw(recipeManager, entry.getKey(), entry.getValue());
        }
        hiddenRecipes.clear();
    }

    private <T> void replaceHidden(IRecipeManager recipeManager, RecipeType<T> recipeType, List<T> nextHidden) {
        @SuppressWarnings("unchecked")
        List<T> previous = (List<T>) hiddenRecipes.getOrDefault(recipeType, List.of());
        if (!previous.isEmpty()) {
            recipeManager.unhideRecipes(recipeType, previous);
        }
        if (!nextHidden.isEmpty()) {
            List<T> copy = List.copyOf(nextHidden);
            recipeManager.hideRecipes(recipeType, copy);
            hiddenRecipes.put(recipeType, copy);
        } else {
            hiddenRecipes.remove(recipeType);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void unhideRaw(IRecipeManager recipeManager, RecipeType<?> recipeType, List<?> recipes) {
        recipeManager.unhideRecipes((RecipeType) recipeType, (List) recipes);
    }
}
