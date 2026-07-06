package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.network.DisbandSettlementPayload;
import com.bannerbound.core.network.GetRegistrationTabletPayload;
import com.bannerbound.core.network.RequestExpandTerritoryPayload;
import com.bannerbound.core.network.RequestSettlementCitizensPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Ancient-era Town Hall -- a "Chiseled Tablet" reskin of {@link TownHallScreen}, built from the
 * {@code bannerbound-antiquity} design bundle (Direction A). The tablet chrome (jagged stone rim,
 * bone face, stat troughs, engraved meander divider) is a baked PNG sprite from
 * {@code tools/antiquity-assetgen}; the title, tabs, gauges and buttons are drawn / blitted on top.
 * The panel-geometry constants and palette (from direction-a.jsx A_PAL) must stay in lockstep with
 * AntiquityAssetGen, or the drawn overlay drifts off the baked troughs. Shares
 * {@link TownHallScreen}'s constructor so it can stand in at the open site; gated to
 * {@link Era#ANCIENT} in {@code ClientPayloadHandler}, which also auto-routes a campfire right-click
 * to ChooseGovernmentScreen while the code-of-laws window is open -- so "Choose Government" is
 * deliberately NOT one of the action slots here. Live data is read from {@link ClientPopulationState}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class AncientTownHallScreen extends Screen {

    private static final ResourceLocation BTN          = spr("btn");
    private static final ResourceLocation BTN_HOVER    = spr("btn_hover");
    private static final ResourceLocation BTN_DISABLED = spr("btn_disabled");
    private static final ResourceLocation TAB          = spr("tab");
    private static final ResourceLocation TAB_ACTIVE   = spr("tab_active");
    private static final ResourceLocation TAB_HOVER    = spr("tab_hover");
    private static final ResourceLocation CAVE_DRAWINGS =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/antiquity/cave_drawings.png");
    private static final ResourceLocation FOOD_ICON =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/food_antiquity.png");
    private static final ResourceLocation CULTURE_ICON =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/culture_antiquity.png");

    private static final int EMBER     = 0xFFA8442E;
    private static final int EMBER_LO  = 0xFF6F2418;
    private static final int OCHRE     = 0xFFC87F3A;
    private static final int UMBER_HI  = 0xFF75451F;
    private static final int BONE      = 0xFFEAD7B1;
    private static final int BONE_SH   = 0xFFA88A5A;
    private static final int PARCH     = 0xFFE2C993;
    private static final int INK_SOFT  = 0xFF4A3520;
    private static final int READOUT   = 0xFF6F5631;
    private static final int DISABLED_TEXT = 0xFF7A6A52;
    private static final int CULTURE   = 0xFF8B4FA0;

    // Geometry must mirror the baked panel.png (AntiquityAssetGen) or the overlay drifts off the troughs.
    private static final int PANEL_W = 240, PANEL_H = 360;
    private static final int TAB_W = 100, TAB_H = 18;
    private static final int BTN_W = 192, BTN_H = 17;
    private static final int BTN_PITCH = 22;
    private static final int TROUGH_X = 27, TROUGH_W = 186;

    private enum TopTab { MAIN, STATUSES }

    private record Hotspot(int x, int y, int w, int h, Runnable action) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record ActionBtn(int x, int y, String label, boolean danger, boolean disabled,
                              Runnable action) { }

    private final String settlementName;
    private final SettlementColor color;
    private final ResourceLocation panelSprite;
    private final Era era;
    private final int tabletsIssued;
    private final int tabletCapacity;
    private final int disbandVoteCount;
    private final int disbandTotalMembers;
    private final boolean playerHasVotedToDisband;
    private final boolean disbandVoteActive;

    private int panelX, panelY;
    private TopTab tab = TopTab.MAIN;
    private final List<Hotspot> hotspots = new ArrayList<>();
    private final List<ActionBtn> actions = new ArrayList<>();

    public AncientTownHallScreen(String settlementName, SettlementColor color, Era era,
                                   int tabletsIssued, int tabletCapacity,
                                   int disbandVoteCount, int disbandTotalMembers,
                                   boolean playerHasVotedToDisband, boolean disbandVoteActive) {
        super(Component.literal(settlementName));
        this.settlementName = settlementName;
        this.color = color;
        this.panelSprite = spr("panel_" + color.name().toLowerCase(Locale.ROOT));
        this.era = era;
        this.tabletsIssued = tabletsIssued;
        this.tabletCapacity = tabletCapacity;
        this.disbandVoteCount = disbandVoteCount;
        this.disbandTotalMembers = disbandTotalMembers;
        this.playerHasVotedToDisband = playerHasVotedToDisband;
        this.disbandVoteActive = disbandVoteActive;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = (this.height - PANEL_H) / 2;
        hotspots.clear();
        actions.clear();

        hotspots.add(new Hotspot(panelX + 19, panelY + 92, TAB_W, TAB_H, () -> tab = TopTab.MAIN));
        hotspots.add(new Hotspot(panelX + 121, panelY + 92, TAB_W, TAB_H, () -> tab = TopTab.STATUSES));

        int bx = panelX + (PANEL_W - BTN_W) / 2, by = panelY + 212;
        addAction(bx, by,                  "Research", false, false,
            () -> this.minecraft.setScreen(new ResearchScreen()));
        addAction(bx, by + BTN_PITCH,      "Citizens", false, false,
            () -> PacketDistributor.sendToServer(new RequestSettlementCitizensPayload()));
        addAction(bx, by + BTN_PITCH * 2,
            "Registration Tablet · " + tabletsIssued + " / " + tabletCapacity,
            false, tabletsIssued >= tabletCapacity, () -> {
                PacketDistributor.sendToServer(new GetRegistrationTabletPayload());
                this.onClose();
            });
        addAction(bx, by + BTN_PITCH * 3,  "Expand Territory", false, false,
            () -> PacketDistributor.sendToServer(new RequestExpandTerritoryPayload()));
        String disband = disbandVoteActive && disbandTotalMembers > 1
            ? "Disband · " + disbandVoteCount + " / " + disbandTotalMembers
            : "Disband Settlement";
        addAction(bx, by + BTN_PITCH * 4,  disband, true, false, () -> {
            PacketDistributor.sendToServer(new DisbandSettlementPayload());
            this.onClose();
        });
        addAction(bx, by + BTN_PITCH * 5,  "Cancel", false, false, this::onClose);
    }

    private void addAction(int x, int y, String label, boolean danger, boolean disabled,
                           Runnable action) {
        actions.add(new ActionBtn(x, y, label, danger, disabled, action));
        if (!disabled) {
            hotspots.add(new Hotspot(x, y, BTN_W, BTN_H, action));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.blitSprite(panelSprite, panelX, panelY, PANEL_W, PANEL_H);

        int cx = panelX + PANEL_W / 2;

        drawScaled(g, settlementName, cx, panelY + 26, 2.0f, scaleRgb(color.rgb(), 0.5f));
        centered(g, eraLabel(), cx, panelY + 58, UMBER_HI);
        centered(g, net.minecraft.network.chat.Component.translatable(
            ClientPopulationState.getTitleKey()).getString(),
            cx, panelY + 66, UMBER_HI);
        g.blit(CAVE_DRAWINGS, cx - 24, panelY + 75, 48, 24, 0f, 0f, 32, 16, 32, 16);

        drawTab(g, panelX + 19,  panelY + 92, "Main",     tab == TopTab.MAIN,     mouseX, mouseY);
        drawTab(g, panelX + 121, panelY + 92, "Statuses", tab == TopTab.STATUSES, mouseX, mouseY);

        g.drawString(this.font,
            "Population · " + ClientPopulationState.getPopulation()
                + " / " + ClientPopulationState.getPopulationMax(),
            panelX + 27, panelY + 124, INK_SOFT, false);
        double foodCap = ClientPopulationState.getFoodCap() > 0.01
            ? ClientPopulationState.getFoodCap() : ClientPopulationState.getNextFoodCost();
        double cultCap = ClientPopulationState.getCultureCap() > 0.01
            ? ClientPopulationState.getCultureCap() : ClientPopulationState.getNextCultureCost();
        drawGauge(g, panelY + 138, panelY + 148, FOOD_ICON, "Food",
            ClientPopulationState.getFoodStored(), foodCap,
            ClientPopulationState.getFoodPerSecond(), OCHRE, 0xFFF0B260, 0xFF9A5A23);
        drawGauge(g, panelY + 164, panelY + 174, CULTURE_ICON, "Culture",
            ClientPopulationState.getCultureStored(), cultCap,
            ClientPopulationState.getCulturePerSecond(), CULTURE, 0xFFC074DB, 0xFF3F2050);

        for (ActionBtn b : actions) {
            boolean hover = !b.disabled() && mouseX >= b.x() && mouseX < b.x() + BTN_W
                         && mouseY >= b.y() && mouseY < b.y() + BTN_H;
            ResourceLocation sprite = b.disabled() ? BTN_DISABLED : hover ? BTN_HOVER : BTN;
            g.blitSprite(sprite, b.x(), b.y(), BTN_W, BTN_H);
            int textColor = b.disabled() ? DISABLED_TEXT : b.danger() ? EMBER : BONE;
            centered(g, b.label(), b.x() + BTN_W / 2, b.y() + (BTN_H - 8) / 2, textColor);
        }
    }

    private void drawTab(GuiGraphics g, int x, int y, String label, boolean active,
                         int mouseX, int mouseY) {
        boolean hover = !active && mouseX >= x && mouseX < x + TAB_W
                     && mouseY >= y && mouseY < y + TAB_H;
        g.blitSprite(active ? TAB_ACTIVE : hover ? TAB_HOVER : TAB, x, y, TAB_W, TAB_H);
        centered(g, label, x + TAB_W / 2, y + (TAB_H - 8) / 2,
            active ? EMBER_LO : hover ? PARCH : BONE_SH);
    }

    private void drawGauge(GuiGraphics g, int labelY, int troughY, ResourceLocation icon,
                           String label, double value, double max, double rate,
                           int labelColor, int fillHi, int fillLo) {
        int x = panelX + TROUGH_X;
        g.drawString(this.font, label, x, labelY, labelColor, false);
        g.blit(icon, x + this.font.width(label) + 4, labelY - 3, 12, 12, 0f, 0f, 32, 32, 32, 32);
        String readout = (rate > 0 ? "+" + trim(rate) + "/s   " : "") + trim(value) + " / " + trim(max);
        g.drawString(this.font, readout, x + TROUGH_W - this.font.width(readout), labelY,
            READOUT, false);
        double pct = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
        int fillW = (int) Math.round((TROUGH_W - 4) * pct);
        if (fillW > 0) {
            g.fillGradient(x + 2, troughY + 2, x + 2 + fillW, troughY + 7, fillHi, fillLo);
        }
    }

    private String eraLabel() {
        String n = era.name();
        return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT) + " Era";
    }

    private void centered(GuiGraphics g, String text, int cx, int y, int textColor) {
        g.drawString(this.font, text, cx - this.font.width(text) / 2, y, textColor, false);
    }

    private void drawScaled(GuiGraphics g, String text, float anchorX, float anchorY,
                            float scale, int textColor) {
        g.pose().pushPose();
        g.pose().translate(anchorX - this.font.width(text) * scale / 2f, anchorY, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(this.font, text, 0, 0, textColor, false);
        g.pose().popPose();
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.round(v)) < 0.05) return String.valueOf(Math.round(v));
        return String.format("%.1f", v);
    }

    private static ResourceLocation spr(String name) {
        return ResourceLocation.fromNamespaceAndPath("bannerbound", "antiquity/" + name);
    }

    private static int scaleRgb(int rgb, float f) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int gn = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (gn << 8) | b;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Hotspot h : hotspots) {
                if (h.hit(mx, my)) {
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.playSound(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f);
                    }
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
