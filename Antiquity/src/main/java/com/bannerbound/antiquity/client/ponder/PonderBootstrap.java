package com.bannerbound.antiquity.client.ponder;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.research.ResearchPonderBridge;

import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.bannerbound.antiquity.BannerboundAntiquityClient;

/**
 * One-shot client bootstrap (run once at client setup, only when Create is installed) that wires
 * Bannerbound: Antiquity into Create's Ponder library: it registers
 * {@link BannerboundAntiquityPonderPlugin} with {@link PonderIndex} and installs an opener on
 * Core's {@link ResearchPonderBridge} so the research screen can launch a ponder for the id stored
 * in a node's "ponder" field. IMPORTANT: this class imports {@code net.createmod.ponder.*} and
 * must only be class-loaded after a positive {@code ModList.isLoaded("create")} check (see the
 * call site in {@code BannerboundAntiquityClient}), or systems without Create crash on the missing
 * classes. The opener resolves the id as a registered item first, opening its scenes via
 * {@link PonderUI#of(ItemStack)} (the same UI as inventory hover-W); failing that it treats the id
 * as a Ponder tag id and opens the first member item that actually has registered scenes. The
 * intermediate PonderTagScreen is intentionally skipped either way, so W on a research node drops
 * straight into the lesson with no extra click.
 */
@OnlyIn(Dist.CLIENT)
public final class PonderBootstrap {
    private PonderBootstrap() {}

    public static void init() {
        PonderIndex.addPlugin(new BannerboundAntiquityPonderPlugin());

        ResearchPonderBridge.setOpener(PonderBootstrap::openForItemId);

        BannerboundAntiquity.LOGGER.info("Bannerbound: Antiquity → Ponder bridge installed.");
    }

    private static void openForItemId(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return;
        }
        Screen ui = null;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item != Items.AIR) {
            ui = PonderUI.of(new ItemStack(item));
        } else {
            for (ResourceLocation memberItemId : PonderIndex.getTagAccess().getItems(rl)) {
                if (!PonderIndex.getSceneAccess().doScenesExistForId(memberItemId)) {
                    continue;
                }
                Item member = BuiltInRegistries.ITEM.get(memberItemId);
                if (member != Items.AIR) {
                    ui = PonderUI.of(new ItemStack(member));
                    break;
                }
            }
        }
        if (ui != null) {
            Minecraft.getInstance().setScreen(ui);
        }
    }
}
