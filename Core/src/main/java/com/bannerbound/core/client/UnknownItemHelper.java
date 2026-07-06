package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Central client-side authority for the "unknown item" knowledge gate: the single funnel every
 * client surface (name swap, question-mark model, tooltips, screen/JEI filters) consults to decide
 * whether the local player recognizes an item. Item gating is a survival-only constraint, so
 * isKnown() short-circuits true for creative players; otherwise an item is known iff it is in
 * ClientStartingItems or ClientResearchState reports it unlocked.
 *
 * <p>isKnownToSettlement() is the same test WITHOUT the creative bypass and returns false until the
 * starting-items set has synced -- used by the lexicon, which must document only what the settlement
 * actually knows and must not flood with the whole registry during the pre-sync window or when a
 * creative player flies around. isUnknownForLocalPlayer() layers ClientStackGate on top: a
 * component-aware test (mirror of server ItemKnowledge.StackGate) an expansion registers so the
 * swap can reflect an item's DATA, e.g. a modular arrow whose material the civ hasn't researched.
 *
 * <p>The question-mark BakedModel is cached; invalidateCache() must be called on resource reload.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class UnknownItemHelper {
    public static final ModelResourceLocation QUESTION_MARK_MODEL =
        ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("bannerbound", "item/question_mark"));

    public static final ResourceLocation SCIENCE_ICON =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/science_icon.png");

    private static BakedModel cachedQuestionMark;

    // Render thread is single-threaded, so a plain boolean (not volatile/atomic) is enough here.
    private static boolean bypassUnknownSwap = false;

    public static void setBypassUnknownSwap(boolean bypass) {
        bypassUnknownSwap = bypass;
    }

    public static boolean isBypassActive() {
        return bypassUnknownSwap;
    }

    private UnknownItemHelper() {
    }

    public static MutableComponent unknownName() {
        return Component.translatable("bannerbound.unknown_item.name").withStyle(ChatFormatting.RED);
    }

    public static MutableComponent unknownAction() {
        return Component.translatable("bannerbound.unknown_item.action").withStyle(ChatFormatting.RED);
    }

    public static boolean isKnown(Item item) {
        if (localPlayerIsCreative()) {
            return true;
        }
        return ClientStartingItems.contains(item) || ClientResearchState.isItemUnlocked(item);
    }

    public static boolean isKnownToSettlement(Item item) {
        if (!ClientStartingItems.isLoaded()) {
            return false;
        }
        return ClientStartingItems.contains(item) || ClientResearchState.isItemUnlocked(item);
    }

    private static boolean localPlayerIsCreative() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.player.isCreative();
    }

    @FunctionalInterface
    public interface ClientStackGate {
        boolean isKnown(ItemStack stack);
    }

    private static final java.util.List<ClientStackGate> STACK_GATES =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerStackGate(ClientStackGate gate) {
        STACK_GATES.add(gate);
    }

    public static boolean isUnknownForLocalPlayer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (localPlayerIsCreative()) {
            return false;
        }
        if (!isKnown(stack.getItem())) {
            return true;
        }
        for (ClientStackGate gate : STACK_GATES) {
            if (!gate.isKnown(stack)) {
                return true;
            }
        }
        return false;
    }

    public static BakedModel getQuestionMarkModel() {
        if (cachedQuestionMark == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getModelManager() == null) {
                return null;
            }
            cachedQuestionMark = mc.getModelManager().getModel(QUESTION_MARK_MODEL);
        }
        return cachedQuestionMark;
    }

    public static void invalidateCache() {
        cachedQuestionMark = null;
    }
}
