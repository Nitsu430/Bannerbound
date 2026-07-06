package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.network.RecallCitizenPayload;
import com.bannerbound.core.network.SettlementCitizensListPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Roster screen opened from the Town Hall menu's "Citizens" button. Shows each citizen by name with
 * a health/stamina readout plus a per-row "Recall" button that teleports them next to the town hall;
 * the header sums count, stamina, and health so the player can eyeball workforce status at a glance
 * (citizens whose entities aren't loaded report 0 live stats, so the summary leans low as a nudge to
 * look in-world). The roster is windowed to MAX_ROWS: scrollRow is an instance field so the scroll
 * offset survives the rebuildWidgets() relayout each scroll triggers. Closing returns to the parent
 * screen (the Town Hall) when set, else to the world.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SettlementCitizensScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 260;
    private static final int ROW_HEIGHT = 26;
    private static final int MAX_ROWS = 7;
    private static final int SCROLLBAR_WIDTH = 4;

    private int scrollRow = 0;

    private final List<SettlementCitizensListPayload.Entry> entries;
    @org.jetbrains.annotations.Nullable
    private final net.minecraft.client.gui.screens.Screen parent;

    public SettlementCitizensScreen(List<SettlementCitizensListPayload.Entry> entries) {
        this(entries, null);
    }

    public SettlementCitizensScreen(List<SettlementCitizensListPayload.Entry> entries,
            @org.jetbrains.annotations.Nullable net.minecraft.client.gui.screens.Screen parent) {
        super(Component.translatable("bannerbound.townhall.citizens.title"));
        this.entries = entries;
        this.parent = parent;
    }

    @org.jetbrains.annotations.Nullable
    public net.minecraft.client.gui.screens.Screen parentScreen() {
        return parent;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    protected void init() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - PANEL_HEIGHT) / 2;
        final int btnWidth = PANEL_WIDTH - 24;
        final int maxScroll = Math.max(0, entries.size() - MAX_ROWS);
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > maxScroll) scrollRow = maxScroll;
        final int firstRow = scrollRow;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            drawIdentityDivider(graphics, panelX + 8, panelY + 28, PANEL_WIDTH - 16, identityAccents);

            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 12, GuiPalette.TITLE);

            int count = entries.size();
            int totalStamina = 0, totalStaminaMax = 0;
            float totalHealth = 0, totalHealthMax = 0;
            for (SettlementCitizensListPayload.Entry e : entries) {
                totalStamina += e.stamina();
                totalStaminaMax += e.maxStamina();
                totalHealth += e.health();
                totalHealthMax += e.maxHealth();
            }
            MutableComponent summary = Component.translatable(
                "bannerbound.townhall.citizens.summary",
                count,
                String.format("%d/%d", totalStamina, totalStaminaMax),
                String.format("%.0f/%.0f", totalHealth, totalHealthMax))
                .withStyle(ChatFormatting.GRAY);
            graphics.drawString(this.font, summary, panelX + 14, panelY + 36, 0xFFCCCCCC, false);

            int rowsTop = panelY + 52;
            for (int row = 0; row < MAX_ROWS && firstRow + row < entries.size(); row++) {
                int idx = firstRow + row;
                SettlementCitizensListPayload.Entry e = entries.get(idx);
                int y = rowsTop + row * ROW_HEIGHT;
                graphics.fill(panelX + 12, y, panelX + PANEL_WIDTH - 12, y + ROW_HEIGHT - 4, 0xFF181818);
                graphics.renderOutline(panelX + 12, y, PANEL_WIDTH - 24, ROW_HEIGHT - 4, 0xFF303030);

                int textX = panelX + 18;
                if (e.jobIconItemId() != 0) {
                    net.minecraft.world.item.Item icon =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(e.jobIconItemId());
                    // Job icon may be an undiscovered item; bypass the unknown-item "?" swap since it is a UI glyph.
                    UnknownItemHelper.setBypassUnknownSwap(true);
                    graphics.renderItem(new net.minecraft.world.item.ItemStack(icon), panelX + 16, y + 3);
                    UnknownItemHelper.setBypassUnknownSwap(false);
                    textX = panelX + 38;
                }

                MutableComponent line1 = Component.literal(e.name()).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("  "))
                    .append(jobLabel(e.jobTypeId()));
                graphics.drawString(this.font, line1, textX, y + 3, 0xFFFFFFFF, false);
                String stats = String.format("HP %.0f/%.0f   STA %d/%d",
                    e.health(), e.maxHealth(), e.stamina(), e.maxStamina());
                graphics.drawString(this.font, stats, textX, y + 13, 0xFFAAAAAA, false);
            }

            if (maxScroll > 0) {
                int trackX = panelX + PANEL_WIDTH - 10;
                int trackY = rowsTop;
                int trackH = MAX_ROWS * ROW_HEIGHT - 4;
                graphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0xFF0A0A0A);
                int thumbH = Math.max(12, trackH * MAX_ROWS / entries.size());
                int thumbY = trackY + (trackH - thumbH) * firstRow / maxScroll;
                graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF606060);
            }
        });

        int rowsTop = panelY + 52;
        for (int row = 0; row < MAX_ROWS && firstRow + row < entries.size(); row++) {
            SettlementCitizensListPayload.Entry e = entries.get(firstRow + row);
            int y = rowsTop + row * ROW_HEIGHT;
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.townhall.citizens.recall"),
                    btn -> {
                        PacketDistributor.sendToServer(new RecallCitizenPayload(e.id()));
                    })
                .bounds(panelX + PANEL_WIDTH - 74, y + 3, 56, 18)
                .accent(primaryAccent())
                .build());
        }

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"),
                btn -> this.onClose())
            .bounds(panelX + 12, panelY + PANEL_HEIGHT - 28, btnWidth, 20)
            .accent(primaryAccent())
            .build());
    }

    private MutableComponent jobLabel(String jobTypeId) {
        if (jobTypeId == null || jobTypeId.isEmpty()) {
            return Component.translatable("bannerbound.job.unemployed").withStyle(ChatFormatting.DARK_GRAY);
        }
        Component custom = ClientLanguageState.jobName(jobTypeId, false);
        MutableComponent name = custom instanceof MutableComponent m ? m
            : custom != null ? Component.empty().append(custom)
            : Component.translatable("bannerbound.job." + jobTypeId);
        return name.withStyle(ChatFormatting.GOLD);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (entries.size() > MAX_ROWS && scrollY != 0) {
            int before = scrollRow;
            scrollRow -= (int) Math.signum(scrollY);
            int maxScroll = Math.max(0, entries.size() - MAX_ROWS);
            scrollRow = Math.max(0, Math.min(scrollRow, maxScroll));
            if (scrollRow != before) this.rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
