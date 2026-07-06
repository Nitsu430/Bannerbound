package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.api.settlement.SiteWarning;
import com.bannerbound.core.network.SettleRequestPayload;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Two-page settlement founding screen (client-only). Page 1 (IDENTITY) picks a name + banner color;
 * page 2 (STYLE) picks the settlement's culture style, which governs which blocks the settlement
 * finds appealing (see the chunk-beauty / culture system). Confirm sends a SettleRequestPayload to
 * the server.
 *
 * Widgets are rebuilt on every init() (page switch), so the name / color / style picks are held in
 * fields to survive the rebuild. Banner colors are unique per server: colors already flown by an
 * existing settlement are derived from the client's mirror of the claim map (each claim carries its
 * settlement color), so no extra packet is needed - taken colors are greyed out and unselectable,
 * and if the player's pick is taken (or on first build) the picker snaps to the first still-available
 * color. The identity page also shows a server-assessed site report: a green all-clear, or one
 * warning line per poor-terrain finding.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SettleScreen extends PolishedScreen {
    private enum Page { IDENTITY, STYLE }

    private static final int PANEL_WIDTH = 320;
    private static final int IDENTITY_PANEL_HEIGHT = 160;
    private static final int BANNER_AREA_WIDTH = 100;
    private static final int SWATCH_SIZE = 20;
    private static final int SWATCH_GAP = 4;
    private static final int STYLE_ROW_H = 24;

    private Page page = Page.IDENTITY;

    private String nameText = "";
    private int selectedColor = 0;
    private String selectedStyleId = "";

    private EditBox nameField;
    private Button confirmButton;

    private final List<SiteWarning> siteWarnings;

    public SettleScreen(int siteWarningMask) {
        super(Component.translatable("bannerbound.settle.title"));
        this.siteWarnings = SiteWarning.fromMask(siteWarningMask);
    }

    @Override
    protected void init() {
        if (page == Page.IDENTITY) {
            initIdentityPage();
        } else {
            initStylePage();
        }
    }

    private void initIdentityPage() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - IDENTITY_PANEL_HEIGHT) / 2;
        final int controlsX = panelX + BANNER_AREA_WIDTH;
        final int controlsWidth = PANEL_WIDTH - BANNER_AREA_WIDTH;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + IDENTITY_PANEL_HEIGHT, 0xFF101010);
            graphics.renderOutline(panelX, panelY, PANEL_WIDTH, IDENTITY_PANEL_HEIGHT, 0xFF606060);
            graphics.fill(panelX + BANNER_AREA_WIDTH, panelY + 8,
                panelX + BANNER_AREA_WIDTH + 1, panelY + IDENTITY_PANEL_HEIGHT - 8, 0xFF2A2A2A);
        });

        int nameY = panelY + 36;
        this.nameField = new EditBox(this.font, controlsX + 12, nameY, controlsWidth - 24, 20,
            Component.translatable("bannerbound.settle.name_label"));
        this.nameField.setMaxLength(SettlementManager.MAX_NAME_LENGTH);
        this.nameField.setValue(nameText);
        this.nameField.setResponder(s -> {
            nameText = s;
            updateConfirmButton();
        });
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        java.util.Set<Integer> takenColors = takenColorIndices();
        if (takenColors.contains(selectedColor)) {
            for (int i = 0; i < SettlementColor.count(); i++) {
                if (!takenColors.contains(i)) {
                    selectedColor = i;
                    break;
                }
            }
        }

        int swatchRowWidth = SettlementColor.count() * SWATCH_SIZE + (SettlementColor.count() - 1) * SWATCH_GAP;
        int swatchStartX = controlsX + (controlsWidth - swatchRowWidth) / 2;
        int swatchY = panelY + 84;
        for (int i = 0; i < SettlementColor.count(); i++) {
            final int colorIndex = i;
            final boolean taken = takenColors.contains(i);
            int x = swatchStartX + i * (SWATCH_SIZE + SWATCH_GAP);
            this.addRenderableWidget(new ColorSwatchButton(x, swatchY, SettlementColor.byIndex(i),
                () -> this.selectedColor = colorIndex,
                () -> this.selectedColor == colorIndex,
                taken));
        }

        int buttonY = panelY + IDENTITY_PANEL_HEIGHT - 28;
        int buttonWidth = (controlsWidth - 36) / 2;
        this.confirmButton = PolishButton.polished(
            Component.translatable("bannerbound.settle.next"),
            btn -> {
                page = Page.STYLE;
                this.rebuildWidgets();
            })
            .bounds(controlsX + 12, buttonY, buttonWidth, 20)
            .build();
        this.addRenderableWidget(this.confirmButton);

        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            btn -> this.onClose())
            .bounds(controlsX + controlsWidth - 12 - buttonWidth, buttonY, buttonWidth, 20)
            .build());

        final int bannerCenterX = panelX + BANNER_AREA_WIDTH / 2;
        final int bannerCenterY = panelY + IDENTITY_PANEL_HEIGHT / 2;
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            ItemStack stack = new ItemStack(bannerItemFor(SettlementColor.byIndex(selectedColor)));
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(bannerCenterX, bannerCenterY, 0.0f);
            pose.scale(4.0f, 4.0f, 1.0f);
            graphics.renderItem(stack, -8, -8);
            pose.popPose();
        });

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            graphics.drawCenteredString(this.font, this.title,
                controlsX + controlsWidth / 2, panelY + 10, 0xFFFFFFFF);
            graphics.drawString(this.font, Component.translatable("bannerbound.settle.name_label"),
                controlsX + 12, panelY + 24, 0xFFCCCCCC);
            graphics.drawString(this.font, Component.translatable("bannerbound.settle.color_label"),
                controlsX + 12, panelY + 72, 0xFFCCCCCC);
        });

        final int reportCenterX = panelX + PANEL_WIDTH / 2;
        final int reportTop = panelY + IDENTITY_PANEL_HEIGHT + 8;
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            if (siteWarnings.isEmpty()) {
                graphics.drawCenteredString(this.font,
                    Component.translatable("bannerbound.settle.site.good"),
                    reportCenterX, reportTop, 0xFF66CC66);
                return;
            }
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.settle.site.header"),
                reportCenterX, reportTop, 0xFFFFCC44);
            int y = reportTop + 12;
            for (SiteWarning warning : siteWarnings) {
                graphics.drawCenteredString(this.font,
                    Component.translatable(warning.translationKey()),
                    reportCenterX, y, 0xFFE0A030);
                y += 11;
            }
        });

        updateConfirmButton();
    }

    private void initStylePage() {
        List<ClientCultureStyleState.Entry> styles = ClientCultureStyleState.styles();
        if (selectedStyleId.isEmpty() && !styles.isEmpty()) {
            selectedStyleId = styles.get(0).id();
        }

        int rows = Math.max(1, (styles.size() + 1) / 2);
        int gridTop = 32;
        int panelHeight = gridTop + rows * STYLE_ROW_H + 40;
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = Math.max(10, (this.height - panelHeight) / 2);
        final int ph = panelHeight;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + ph, 0xFF101010);
            graphics.renderOutline(panelX, panelY, PANEL_WIDTH, ph, 0xFF606060);
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.settle.style_title"),
                panelX + PANEL_WIDTH / 2, panelY + 10, 0xFFFFFFFF);
            if (styles.isEmpty()) {
                graphics.drawCenteredString(this.font,
                    Component.translatable("bannerbound.settle.style_none"),
                    panelX + PANEL_WIDTH / 2, panelY + gridTop + 8, 0xFFCC6666);
            }
        });

        int colWidth = (PANEL_WIDTH - 12 - 12 - 8) / 2;
        for (int i = 0; i < styles.size(); i++) {
            ClientCultureStyleState.Entry entry = styles.get(i);
            int col = i % 2;
            int row = i / 2;
            int x = panelX + 12 + col * (colWidth + 8);
            int y = panelY + gridTop + row * STYLE_ROW_H;
            Button styleButton = PolishButton.polished(
                // Display names come from the style JSON, not lang -> render literally, not as a key.
                Component.literal(entry.nameKey()),
                btn -> {
                    selectedStyleId = entry.id();
                    this.rebuildWidgets();
                })
                .bounds(x, y, colWidth, 20)
                .build();
            styleButton.active = !entry.id().equals(selectedStyleId);
            this.addRenderableWidget(styleButton);
        }

        int buttonY = panelY + ph - 28;
        int buttonWidth = (PANEL_WIDTH - 36) / 2;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.settle.back"),
            btn -> {
                page = Page.IDENTITY;
                this.rebuildWidgets();
            })
            .bounds(panelX + 12, buttonY, buttonWidth, 20)
            .build());
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.settle.confirm"),
            btn -> submit())
            .bounds(panelX + PANEL_WIDTH - 12 - buttonWidth, buttonY, buttonWidth, 20)
            .build());
    }

    private static java.util.Set<Integer> takenColorIndices() {
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (com.bannerbound.core.network.ClaimEntry e : ClientClaimState.all().values()) {
            taken.add(e.colorIndex());
        }
        return taken;
    }

    private void updateConfirmButton() {
        if (this.confirmButton != null) {
            this.confirmButton.active = SettlementManager.isNameValid(nameText.trim());
        }
    }

    private void submit() {
        String name = nameText.trim();
        if (!SettlementManager.isNameValid(name)) {
            page = Page.IDENTITY;
            this.rebuildWidgets();
            return;
        }
        PacketDistributor.sendToServer(new SettleRequestPayload(name, this.selectedColor, this.selectedStyleId));
        this.onClose();
    }

    private static Item bannerItemFor(SettlementColor color) {
        return switch (color) {
            case WHITE -> Items.WHITE_BANNER;
            case RED -> Items.RED_BANNER;
            case GOLD -> Items.ORANGE_BANNER;
            case YELLOW -> Items.YELLOW_BANNER;
            case GREEN -> Items.LIME_BANNER;
            case AQUA -> Items.LIGHT_BLUE_BANNER;
            case BLUE -> Items.BLUE_BANNER;
            case LIGHT_PURPLE -> Items.MAGENTA_BANNER;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class ColorSwatchButton extends Button {
        private final SettlementColor color;
        private final java.util.function.BooleanSupplier isSelected;
        private final boolean taken;

        ColorSwatchButton(int x, int y, SettlementColor color, Runnable onClick,
                          java.util.function.BooleanSupplier isSelected, boolean taken) {
            super(x, y, SWATCH_SIZE, SWATCH_SIZE, color.displayName(),
                btn -> onClick.run(), DEFAULT_NARRATION);
            this.color = color;
            this.isSelected = isSelected;
            this.taken = taken;
            this.active = !taken;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();

            if (taken) {
                int dim = 0xFF000000 | (((color.rgb() & 0xFEFEFE) >> 1));
                graphics.fill(x, y, x + w, y + h, dim);
                graphics.renderOutline(x, y, w, h, 0xFF222222);
                graphics.fill(x + 2, y + h / 2, x + w - 2, y + h / 2 + 1, 0xFF000000);
                return;
            }

            int fill = 0xFF000000 | (color.rgb() & 0x00FFFFFF);
            graphics.fill(x, y, x + w, y + h, fill);

            int borderColor = isSelected.getAsBoolean() ? 0xFFFFFFFF
                : (this.isHovered() ? 0xFFCCCCCC : 0xFF333333);
            graphics.renderOutline(x, y, w, h, borderColor);
            if (isSelected.getAsBoolean()) {
                graphics.renderOutline(x - 1, y - 1, w + 2, h + 2, 0xFFFFFFFF);
            }
        }
    }
}
