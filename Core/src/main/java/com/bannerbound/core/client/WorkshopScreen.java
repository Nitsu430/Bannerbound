package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.network.AssignWorkshopWorkerPayload;
import com.bannerbound.core.network.OpenWorkshopMenuPayload;
import com.bannerbound.core.network.RenameWorkshopPayload;
import com.bannerbound.core.network.WorkshopMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only Workshop menu, opened by shift-right-clicking inside a workshop with the Workshop
 * Orders rod. Two bookmark tabs (TownHall style, above the panel's top edge): Workers (the roster)
 * and Stock (min-stock plus order-queue rows). Switching tabs resets the scroll window.
 *
 * <p>Names and icons are painted in the backdrop; only the buttons are real widgets. The candidate
 * list is unemployed-first (stable within each group) and the mouse wheel scrolls it. The Workers
 * tab shows a per-worker station-chooser button only when the workshop holds more than one station
 * family (nothing to choose otherwise). The Stock tab's min-stock and order-queue steps go by 1, or
 * by 8 with shift held; player orders render gold while the chain's derived auto orders ride along
 * as a display-only gray "+n" (the +/- buttons edit player orders, never the auto ones). The
 * capacity row shows workplace appeal right-aligned: the decorating carrot, since a prettier
 * workshop means happier workers who learn their craft faster.
 *
 * <p>typeKnown mirrors the server-side assign gate: an unresearched craft reads as "Unknown
 * Workshop" everywhere (type line and rename placeholder) and its Assign buttons are disabled with
 * an explanatory tooltip, so a station pre-placed on old ruins can't be operated (silently) before
 * the research is earned. The server re-sends the whole menu after every edit (rename / assign /
 * min-stock +/-); carryUiStateFrom copies tab, scroll and the typed-but-unsaved name from the prior
 * instance so, e.g., a Stock-tab click doesn't bounce the player back to Workers.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WorkshopScreen extends PolishedScreen {
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 290;
    private static final int VISIBLE_ROWS = 5;
    private static final int ROW_H = 18;

    private final OpenWorkshopMenuPayload data;
    private final boolean typeKnown;
    private final List<Integer> sortedCandidates = new ArrayList<>();
    private EditBox nameField;
    private int scroll;
    private int tab;
    private int rosterTop;
    private String pendingName;

    public boolean showsWorkshop(String workshopId) {
        return data.workshopId().equals(workshopId);
    }

    public void carryUiStateFrom(WorkshopScreen previous) {
        this.tab = previous.tab;
        this.scroll = previous.scroll;
        this.pendingName = previous.pendingName;
    }

    public WorkshopScreen(OpenWorkshopMenuPayload data) {
        super(Component.translatable("bannerbound.workshop.title"));
        this.data = data;
        this.typeKnown = ClientResearchState.isWorkshopTypeKnown(data.derivedTypeId());
        this.pendingName = data.customName();
        for (int i = 0; i < data.candidateIds().size(); i++) {
            if (!data.candidateEmployed().get(i)) sortedCandidates.add(i);
        }
        for (int i = 0; i < data.candidateIds().size(); i++) {
            if (data.candidateEmployed().get(i)) sortedCandidates.add(i);
        }
    }

    private int panelX() {
        return (this.width - PANEL_W) / 2;
    }

    private int panelY() {
        // Panel + bookmark-tab strip center as one block so tabs protruding above the top stay on-screen.
        return (this.height - PANEL_H + BookmarkTab.HEIGHT) / 2;
    }

    @Override
    protected void init() {
        int x = panelX();
        int y = panelY();
        this.nameField = new EditBox(this.font, x + 12, y + 36, PANEL_W - 80, 20,
            Component.translatable("bannerbound.workshop.name_label"));
        this.nameField.setMaxLength(WorkshopMenu.MAX_NAME_LENGTH);
        this.nameField.setValue(pendingName);
        this.nameField.setResponder(s -> pendingName = s);
        this.nameField.setHint(typeName().copy().withStyle(ChatFormatting.DARK_GRAY));
        this.addRenderableWidget(this.nameField);

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.workshop.save_name"),
                btn -> PacketDistributor.sendToServer(
                    new RenameWorkshopPayload(data.workshopId(), nameField.getValue().strip())))
            .bounds(x + PANEL_W - 60, y + 36, 48, 20)
            .accent(primaryAccent())
            .build());

        BookmarkTab.addRow(this::addRenderableWidget, x, PANEL_W, y,
            java.util.List.of(
                Component.translatable("bannerbound.workshop.tab_workers"),
                Component.translatable("bannerbound.workshop.tab_stock")),
            tab, primaryAccent(), secondaryAccent(), i -> {
                if (tab != i) { tab = i; scroll = 0; this.rebuildWidgets(); }
            });

        rosterTop = y + 134;
        if (tab == 0) {
            initWorkersTab(x);
        } else {
            initStockTab(x);
        }

        this.addRenderableWidget(PolishButton.polished(Component.translatable("gui.done"),
                btn -> this.onClose())
            .bounds(x + 12, y + PANEL_H - 28, PANEL_W - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    private void initWorkersTab(int x) {
        int rowY = rosterTop;
        boolean multiStation = data.stationTypeIds().size() > 1;
        for (int i = 0; i < data.workerIds().size(); i++) {
            final String id = data.workerIds().get(i);
            if (multiStation) {
                final String current = i < data.workerPositions().size()
                    ? data.workerPositions().get(i) : "";
                this.addRenderableWidget(PolishButton.polished(
                        stationLabel(current),
                        btn -> PacketDistributor.sendToServer(
                            new com.bannerbound.core.network.SetWorkshopWorkerStationPayload(
                                data.workshopId(), id, nextStation(current))))
                    .bounds(x + PANEL_W - 162, rowY - 2, 86, 16)
                    .accent(primaryAccent())
                    .build());
            }
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.workshop.unassign"),
                    btn -> PacketDistributor.sendToServer(
                        new AssignWorkshopWorkerPayload(data.workshopId(), id, false)))
                .bounds(x + PANEL_W - 72, rowY - 2, 60, 16)
                .accent(primaryAccent())
                .build());
            rowY += ROW_H;
        }
        rowY += 12;
        boolean hasRoom = data.workerIds().size() < data.capacity();
        int end = Math.min(sortedCandidates.size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            final String id = data.candidateIds().get(sortedCandidates.get(row));
            PolishButton.Builder assignBtn = PolishButton.polished(
                    Component.translatable("bannerbound.workshop.assign"),
                    b -> PacketDistributor.sendToServer(
                        new AssignWorkshopWorkerPayload(data.workshopId(), id, true)))
                .bounds(x + PANEL_W - 72, rowY - 2, 60, 16)
                .accent(primaryAccent());
            if (!typeKnown) {
                assignBtn.tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("bannerbound.workshop.assign_locked_tip")));
            }
            PolishButton btn = assignBtn.build();
            btn.active = hasRoom && typeKnown;
            this.addRenderableWidget(btn);
            rowY += ROW_H;
        }
    }

    private void initStockTab(int x) {
        int rowY = rosterTop + 12;
        int end = Math.min(data.minStockItemIds().size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            final int itemId = data.minStockItemIds().get(row);
            final int value = data.minStockValues().get(row);
            final int queued = row < data.orderCounts().size() ? data.orderCounts().get(row) : 0;
            this.addRenderableWidget(PolishButton.polished(Component.literal("-"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopMinStockPayload(data.workshopId(), itemId,
                            value - (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 148, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            this.addRenderableWidget(PolishButton.polished(Component.literal("+"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopMinStockPayload(data.workshopId(), itemId,
                            value + (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 100, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            this.addRenderableWidget(PolishButton.polished(Component.literal("-"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopOrderPayload(data.workshopId(), itemId,
                            queued - (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 76, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            this.addRenderableWidget(PolishButton.polished(Component.literal("+"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopOrderPayload(data.workshopId(), itemId,
                            queued + (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 28, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            rowY += ROW_H;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        int listSize = tab == 0 ? sortedCandidates.size() : data.minStockItemIds().size();
        int maxScroll = Math.max(0, listSize - VISIBLE_ROWS);
        int newScroll = net.minecraft.util.Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll);
        if (newScroll != scroll) {
            scroll = newScroll;
            this.rebuildWidgets();
        }
        return true;
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = panelX();
        int y = panelY();
        drawIdentityPanel(g, x, y, PANEL_W, PANEL_H, identityAccents);

        g.drawCenteredString(this.font, this.title, this.width / 2, y + 8, GuiPalette.TITLE);
        drawIdentityDivider(g, x + 8, y + 20, PANEL_W - 16, identityAccents);

        Workshop.Status status = Workshop.Status.fromOrdinalOrDefault(data.statusOrdinal());
        boolean valid = status == Workshop.Status.VALID;
        Component statusLine = Component.translatable("bannerbound.workshop.status_label")
            .append(" ")
            .append(Component.translatable("bannerbound.workshop.status." + status.name().toLowerCase())
                .withStyle(valid ? ChatFormatting.GREEN : ChatFormatting.RED));
        int lineY = y + 64;
        for (net.minecraft.util.FormattedCharSequence seq : this.font.split(statusLine, PANEL_W - 24)) {
            g.drawString(this.font, seq, x + 12, lineY, 0xFFFFFFFF, false);
            lineY += this.font.lineHeight + 2;
        }
        Component typeLine = Component.translatable("bannerbound.workshop.type_label")
            .append(" ").append(typeName().copy()
                .withStyle(typeKnown ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        g.drawString(this.font, typeLine, x + 12, lineY, 0xFFFFFFFF);
        lineY += this.font.lineHeight + 4;
        Component capacityLine = Component.translatable("bannerbound.workshop.capacity_label",
            data.workerIds().size(), data.capacity());
        g.drawString(this.font, capacityLine, x + 12, lineY, 0xFFFFFFFF);
        if (data.appealOrdinal() >= 0) {
            com.bannerbound.core.api.settlement.ChunkBeauty beauty =
                com.bannerbound.core.api.settlement.ChunkBeauty.byNetworkId(data.appealOrdinal());
            Component appealLine = Component.translatable("bannerbound.workshop.appeal_label")
                .append(" ")
                .append(Component.translatable(beauty.langKey())
                    .withStyle(beauty.tierIndex() > 0 ? ChatFormatting.AQUA
                        : beauty.tierIndex() < 0 ? ChatFormatting.RED
                        : ChatFormatting.GRAY));
            g.drawString(this.font, appealLine,
                x + PANEL_W - 12 - this.font.width(appealLine), lineY, 0xFFFFFFFF);
        }

        if (tab == 0) {
            drawWorkersTab(g, x);
        } else {
            drawStockTab(g, x);
        }
    }

    private void drawWorkersTab(GuiGraphics g, int x) {
        int rowY = rosterTop;
        int nameClip = data.stationTypeIds().size() > 1 ? PANEL_W - 206 : 0;
        for (int i = 0; i < data.workerNames().size(); i++) {
            drawCitizenRow(g, x, rowY, data.workerNames().get(i), data.workerJobIcons().get(i),
                null, nameClip);
            rowY += ROW_H;
        }
        Component header = Component.translatable("bannerbound.workshop.candidates_header");
        if (sortedCandidates.size() > VISIBLE_ROWS) {
            header = header.copy().append(Component.literal(
                    "  (" + (scroll + 1) + "–" + Math.min(sortedCandidates.size(), scroll + VISIBLE_ROWS)
                        + " / " + sortedCandidates.size() + ")")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        g.drawString(this.font, header.copy().withStyle(ChatFormatting.GRAY),
            x + 12, rowY, 0xFFAAAAAA, false);
        rowY += 12;
        int end = Math.min(sortedCandidates.size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            int i = sortedCandidates.get(row);
            boolean employed = data.candidateEmployed().get(i);
            Component tag = Component.translatable(employed
                    ? "bannerbound.workshop.candidate_employed"
                    : "bannerbound.workshop.candidate_unemployed")
                .withStyle(employed ? ChatFormatting.GOLD : ChatFormatting.GREEN);
            drawCitizenRow(g, x, rowY, data.candidateNames().get(i), data.candidateJobIcons().get(i),
                tag, 0);
            rowY += ROW_H;
        }
    }

    private void drawStockTab(GuiGraphics g, int x) {
        int rowY = rosterTop;
        Component header = Component.translatable("bannerbound.workshop.stock_header");
        g.drawString(this.font, header.copy().withStyle(ChatFormatting.GRAY),
            x + 12, rowY, 0xFFAAAAAA, false);
        int minCenter = x + PANEL_W - 116;
        int orderCenter = x + PANEL_W - 44;
        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.workshop.col_min").getString(),
            minCenter, rowY, 0xFFAAAAAA);
        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.workshop.col_orders").getString(),
            orderCenter, rowY, 0xFFAAAAAA);
        rowY += 12;
        int end = Math.min(data.minStockItemIds().size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            Item item = BuiltInRegistries.ITEM.byId(data.minStockItemIds().get(row));
            int min = data.minStockValues().get(row);
            int have = data.minStockCounts().get(row);
            int queued = row < data.orderCounts().size() ? data.orderCounts().get(row) : 0;
            if (item != Items.AIR) {
                g.renderItem(new ItemStack(item), x + 12, rowY - 4);
            }
            Component nameLine = Component.empty()
                .append(item.getDescription())
                .append(Component.literal(" (" + have + ")")
                    .withStyle(min > 0 && have < min ? ChatFormatting.RED : ChatFormatting.GREEN));
            var clipped = this.font.split(nameLine, PANEL_W - 188).get(0);
            g.drawString(this.font, clipped, x + 32, rowY, 0xFFFFFFFF, false);
            String minText = min <= 0 ? "—" : Integer.toString(min);
            g.drawCenteredString(this.font, minText, minCenter, rowY, 0xFFFFFFFF);
            int auto = row < data.autoOrderCounts().size() ? data.autoOrderCounts().get(row) : 0;
            String orderText = (queued <= 0 && auto <= 0) ? "—"
                : queued > 0 && auto > 0 ? queued + "+" + auto
                : queued > 0 ? Integer.toString(queued)
                : "+" + auto;
            g.drawCenteredString(this.font, orderText, orderCenter, rowY,
                queued > 0 ? 0xFFFFC84A : auto > 0 ? 0xFFB0B0B0 : 0xFFFFFFFF);
            rowY += ROW_H;
        }
        if (data.minStockItemIds().size() > VISIBLE_ROWS) {
            g.drawString(this.font, Component.literal(
                    "(" + (scroll + 1) + "–" + end + " / " + data.minStockItemIds().size() + ")")
                .withStyle(ChatFormatting.DARK_GRAY), x + 12, rowY, 0xFF777777, false);
            drawScrollbar(g, x, rosterTop + 12, VISIBLE_ROWS * ROW_H,
                data.minStockItemIds().size());
        }
    }

    private void drawScrollbar(GuiGraphics g, int x, int top, int height, int total) {
        if (total <= VISIBLE_ROWS) return;
        int trackX = x + PANEL_W - 8;
        g.fill(trackX, top - 2, trackX + 3, top - 2 + height, 0xFF2B2B2B);
        int thumbH = Math.max(10, height * VISIBLE_ROWS / total);
        int maxScroll = total - VISIBLE_ROWS;
        int thumbY = top - 2 + (height - thumbH) * scroll / maxScroll;
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF8B8B8B);
    }

    private void drawCitizenRow(GuiGraphics g, int x, int rowY, String name, int iconItemId,
                                Component tag, int maxTextWidth) {
        int textX = x + 12;
        if (iconItemId != 0) {
            Item item = BuiltInRegistries.ITEM.byId(iconItemId);
            if (item != Items.AIR) {
                g.renderItem(new ItemStack(item), x + 12, rowY - 4);
            }
        }
        textX += 20;
        Component row = tag == null
            ? Component.literal(name)
            : Component.literal(name).append(" ").append(tag);
        if (maxTextWidth > 0) {
            g.drawString(this.font, this.font.split(row, maxTextWidth).get(0), textX, rowY,
                0xFFFFFFFF, false);
        } else {
            g.drawString(this.font, row, textX, rowY, 0xFFFFFFFF, false);
        }
    }

    private Component typeName() {
        if (!typeKnown) {
            return Component.translatable("bannerbound.workshop.type_unknown");
        }
        return Component.translatable(WorkBlockRegistry.displayKey(data.derivedTypeId()));
    }

    private Component stationLabel(String typeId) {
        if (typeId == null || typeId.isEmpty()) {
            return Component.translatable("bannerbound.workshop.station_any");
        }
        return Component.translatable(WorkBlockRegistry.displayKey(typeId));
    }

    private String nextStation(String current) {
        List<String> opts = new ArrayList<>(data.stationTypeIds());
        opts.add("");
        int idx = opts.indexOf(current == null ? "" : current);
        if (idx < 0) idx = opts.size() - 1;
        return opts.get((idx + 1) % opts.size());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
