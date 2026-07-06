package com.bannerbound.core.client;

import com.bannerbound.core.api.settlement.Workstation;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.network.PickForemansRodWorkstationPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picker opened by shift-right-clicking a Foreman's Rod. Lists every supported workstation type the
 * settlement has researched (WorkstationUnlocks.flagForUnit -> bannerbound.unlock.<type>), each
 * labelled from bannerbound.workstation_type.<id>, plus a red "Clear selection" row above Cancel.
 * Clicking a row ships {@link PickForemansRodWorkstationPayload} with the chosen type id (empty
 * string for Clear); the server-side handler writes or wipes the rod's data components.
 *
 * <p>To add a workstation: add its type id to {@link #WORKSTATION_TYPES} AND to the server-side
 * allowlist in ServerPayloadHandler.handlePickForemansRodWorkstation.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ForemansRodPickerScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT = 200;
    private static final int ROW_HEIGHT = 22;

    public static final List<String> WORKSTATION_TYPES = List.of("digger", "farmer", "herder", "miner", "guard");

    public ForemansRodPickerScreen() {
        super(Component.translatable("bannerbound.foremans_rod.picker.title"));
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
        for (String typeId : WORKSTATION_TYPES) {
            if (!ClientResearchState.hasFlag(
                    com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForUnit(typeId))) {
                continue;
            }
            int y = listTop + row * ROW_HEIGHT;
            row++;
            this.addRenderableWidget(PolishButton.polished(
                    workstationLabel(typeId),
                    btn -> {
                        PacketDistributor.sendToServer(new PickForemansRodWorkstationPayload(typeId));
                        this.onClose();
                    })
                .bounds(panelX + 12, y, PANEL_WIDTH - 24, 20)
                .accent(primaryAccent())
                .build());
        }

        int clearY = panelY + PANEL_HEIGHT - 52;
        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.foremans_rod.picker.clear")
                    .withStyle(ChatFormatting.RED),
                btn -> {
                    PacketDistributor.sendToServer(new PickForemansRodWorkstationPayload(""));
                    this.onClose();
                })
            .bounds(panelX + 12, clearY, PANEL_WIDTH - 24, 20)
            .accent(primaryAccent())
            .build());

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"),
                btn -> this.onClose())
            .bounds(panelX + 12, panelY + PANEL_HEIGHT - 28, PANEL_WIDTH - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    private Component workstationLabel(String typeId) {
        String jobType = switch (typeId) {
            case "digger" -> "diggers_slab";
            case "farmer" -> "farmers_granary";
            case "herder" -> "herders_pen";
            case "miner" -> "miners_claim";
            case "guard" -> "guards_post";
            default -> typeId;
        };
        Component custom = ClientLanguageState.jobName(jobType, false);
        return custom != null ? custom : Component.translatable("bannerbound.workstation_type." + typeId);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
