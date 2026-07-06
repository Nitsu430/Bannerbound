package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.UUID;

import com.bannerbound.core.network.PickSeedPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Auto-popup shown to the player who created a farmer selection once tilling completes. Lists the
 * candidate seeds (icon + display name), hiding any the player has not learned yet; a seed whose
 * crop chunk this field sits on harvests at 2x and is flagged green with a star. Picking a seed
 * ships {@link PickSeedPayload} and the selection's farmer goal advances to PLANTING. Skipping (the
 * "Skip" button, Esc, or window-close) sends an empty PickSeedPayload, which the server translates
 * into "erase this selection" so the popup is truly one-shot. The decisionSent latch is the guard
 * that makes this work: it must be set the instant any button fires so onClose never sends a second
 * (erasing) skip after a real pick has already been confirmed.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SeedPickerScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 220;
    private static final int ROW_HEIGHT = 22;

    private final UUID rodId;
    private final List<String> candidates;
    private final List<String> bonusSeeds;
    private boolean decisionSent;

    public SeedPickerScreen(UUID rodId, List<String> candidates, List<String> bonusSeeds) {
        super(Component.translatable("bannerbound.seed_picker.title"));
        this.rodId = rodId;
        this.candidates = candidates;
        this.bonusSeeds = bonusSeeds;
    }

    @Override
    protected void init() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - PANEL_HEIGHT) / 2;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);
        });

        int listTop = panelY + 30;
        int row = 0;
        for (String seedId : candidates) {
            Item item = resolveItem(seedId);
            if (item == Items.AIR || !UnknownItemHelper.isKnown(item)) {
                continue;
            }
            final ItemStack icon = new ItemStack(item);
            final int rowY = listTop + row * ROW_HEIGHT;
            row++;
            Component label = bonusSeeds.contains(seedId)
                ? Component.literal("★ ").append(item.getDescription()).withStyle(ChatFormatting.GREEN)
                : item.getDescription();
            this.addRenderableWidget(PolishButton.polished(label,
                    btn -> {
                        decisionSent = true;
                        PacketDistributor.sendToServer(new PickSeedPayload(rodId, seedId));
                        this.onClose();
                    })
                .bounds(panelX + 30, rowY, PANEL_WIDTH - 42, 20)
                .accent(primaryAccent())
                .build());
            this.addRenderableOnly((graphics, mx, my, pt) ->
                graphics.renderItem(icon, panelX + 10, rowY + 2));
        }

        int skipY = panelY + PANEL_HEIGHT - 28;
        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.seed_picker.skip")
                    .withStyle(ChatFormatting.YELLOW),
                btn -> {
                    decisionSent = true;
                    PacketDistributor.sendToServer(new PickSeedPayload(rodId, ""));
                    this.onClose();
                })
            .bounds(panelX + 12, skipY, PANEL_WIDTH - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    @Override
    public void onClose() {
        if (!decisionSent) {
            decisionSent = true;
            PacketDistributor.sendToServer(new PickSeedPayload(rodId, ""));
        }
        super.onClose();
    }

    private static Item resolveItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(rl);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
