package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.CastGovernmentVotePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sub-screen opened from the town hall's "Choose Government" button after the Code of Laws
 * prompt fires. Two stages: the player toggles {@code selected} between COUNCIL (1) and
 * CHIEFDOM (2) as often as they like (0 = none; local-only, initialised from the server
 * snapshot so an existing vote shows highlighted, nothing leaves the client); then Cast Vote
 * (greyed until a selection exists) fires {@link CastGovernmentVotePayload} and flips
 * {@code hasCast}, locking every button -- no take-backsies.
 * <p>
 * On cast, the caster's own tally is bumped locally so their +1 shows instantly; the other
 * member's vote still needs a server-pushed snapshot to appear. The screen never auto-refreshes
 * tallies -- values are the snapshot from when the town hall opened, so after voting the
 * subtitle switches to a "locked" hint; re-opening the town hall is the intended refresh.
 * <p>
 * Solo settlements (onlineMembers &lt;= 1) must pick Chiefdom: a council of one has no meaning
 * and its vote thresholds assume multiple members. The client greys the council button and the
 * server enforces the same rule.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ChooseGovernmentScreen extends PolishedScreen {
    private static final int PANEL_W = 220;
    private static final int PANEL_H = 200;
    private static final int BTN_W = 180;
    private static final int BTN_H = 28;
    private static final int BTN_PITCH = 36;

    @Nullable private final Screen parent;
    private int councilVotes;
    private int chiefdomVotes;
    private final int onlineMembers;

    private int selected;
    private boolean hasCast;

    private Button councilBtn;
    private Button chiefdomBtn;
    private Button castBtn;
    private final TransientClickFeedback feedback = new TransientClickFeedback();

    public ChooseGovernmentScreen(@Nullable Screen parent, int councilVotes, int chiefdomVotes,
                                   int onlineMembers, int playerVote) {
        super(Component.translatable("bannerbound.government.choose.title"));
        this.parent = parent;
        this.councilVotes = councilVotes;
        this.chiefdomVotes = chiefdomVotes;
        this.onlineMembers = onlineMembers;
        this.selected = playerVote;
        this.hasCast = playerVote > 0;
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int btnX = panelX + (PANEL_W - BTN_W) / 2;
        int firstY = panelY + 50;

        councilBtn = PolishButton.polished(
            buildOptionLabel("bannerbound.government.council", councilVotes, 1),
            b -> {
                if (hasCast) return;
                selected = 1;
                refreshButtons();
            }
        ).bounds(btnX, firstY, BTN_W, BTN_H).accent(primaryAccent()).build();
        if (onlineMembers <= 1) {
            councilBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.government.council.solo_tooltip")));
        }
        addRenderableWidget(councilBtn);

        chiefdomBtn = PolishButton.polished(
            buildOptionLabel("bannerbound.government.chiefdom", chiefdomVotes, 2),
            b -> {
                if (hasCast) return;
                selected = 2;
                refreshButtons();
            }
        ).bounds(btnX, firstY + BTN_PITCH, BTN_W, BTN_H).accent(primaryAccent()).build();
        addRenderableWidget(chiefdomBtn);

        castBtn = PolishButton.polished(
            Component.translatable("bannerbound.vote.cast"),
            b -> {
                if (hasCast || selected <= 0) return;
                PacketDistributor.sendToServer(new CastGovernmentVotePayload(selected));
                if (selected == 1) councilVotes++;
                else if (selected == 2) chiefdomVotes++;
                hasCast = true;
                refreshButtons();
                feedback.spawnAtCursor();
            }
        ).bounds(btnX, firstY + BTN_PITCH * 2 + 8, BTN_W, BTN_H).accent(primaryAccent()).build();
        addRenderableWidget(castBtn);

        addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            b -> this.onClose()
        ).bounds(btnX, panelY + PANEL_H - 28, BTN_W, 20).accent(primaryAccent()).build());

        refreshButtons();
    }

    private void refreshButtons() {
        if (councilBtn != null) {
            councilBtn.setMessage(buildOptionLabel("bannerbound.government.council", councilVotes, 1));
            councilBtn.active = !hasCast && onlineMembers > 1;
        }
        if (chiefdomBtn != null) {
            chiefdomBtn.setMessage(buildOptionLabel("bannerbound.government.chiefdom", chiefdomVotes, 2));
            chiefdomBtn.active = !hasCast;
        }
        if (castBtn != null) {
            castBtn.active = !hasCast && selected > 0;
            castBtn.setMessage(hasCast
                ? Component.translatable("bannerbound.vote.cast.locked")
                    .withStyle(ChatFormatting.GRAY)
                : Component.translatable("bannerbound.vote.cast"));
        }
    }

    private Component buildOptionLabel(String labelKey, int votes, int optionId) {
        Component label = Component.translatable(labelKey);
        Component tally = Component.literal(" — " + votes + " / " + onlineMembers);
        Component prefix = (selected == optionId)
            ? Component.literal("✓ ").withStyle(ChatFormatting.GREEN)
            : Component.literal("");
        return Component.empty().append(prefix).append(label).append(tally);
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Draw panel chrome in the backdrop, not renderPolishedExtras: its fill is opaque and would hide the buttons.
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        drawIdentityPanel(g, panelX, panelY, PANEL_W, PANEL_H, identityAccents);
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.government.choose.title")
                .withStyle(ChatFormatting.GOLD),
            panelX + PANEL_W / 2, panelY + 14, GuiPalette.TITLE);

        Component subtitle = hasCast
            ? Component.translatable("bannerbound.vote.locked.subtitle").withStyle(ChatFormatting.GRAY)
            : Component.translatable("bannerbound.government.choose.subtitle").withStyle(ChatFormatting.GRAY);
        g.drawCenteredString(this.font, subtitle, panelX + PANEL_W / 2, panelY + 28, 0xFFCCCCCC);

        feedback.render(g);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
