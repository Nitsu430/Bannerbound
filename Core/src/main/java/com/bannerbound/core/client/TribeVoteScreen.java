package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Cosmetic citizen-vote reveal screen, opened to every settlement member when a chief election
 * ties and the citizens break it. The winner is already picked server-side; this screen only makes
 * the tiebreak feel like a dramatic moment instead of a silent random pick. Reveal pacing is read
 * from the shared TribeVoteTiming (first vote after FIRST_DELAY_MS, each next one multiplied by
 * DECAY_FACTOR, floored at MIN_DELAY_MS -> a slow opening beat accelerating to a flourish); the
 * constants are shared with the server so its chief enactment lands exactly as the last row reveals.
 *
 * <p>Reveal-at timestamps are pre-computed in init() so the math never runs per frame. Each newly
 * revealed vote fires one NOTE_BLOCK_BASEDRUM kick (pitch 1.0, vol 0.3); soundedCount tracks how
 * many have already played so a frame that reveals several fires one kick each.
 *
 * <p>The screen does NOT auto-close and does NOT dim the background: the reveal is a cinematic
 * overlay drawn over the live world (citizens visibly gathered), and the player closes it with Esc
 * whenever they like. The server enactment is on its own timer (SettlementManager
 * .TRIBE_VOTE_REVEAL_MS) so the outcome happens regardless of when the screen is dismissed.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class TribeVoteScreen extends PolishedScreen {
    private static final long FIRST_DELAY_MS = TribeVoteTiming.FIRST_DELAY_MS;
    private static final double DECAY_FACTOR = TribeVoteTiming.DECAY_FACTOR;
    private static final long MIN_DELAY_MS = TribeVoteTiming.MIN_DELAY_MS;

    private static final int ROW_W = 260;
    private static final int ROW_H = 22;
    private static final int ROW_PITCH = 26;

    private static final ResourceLocation CROWN_TEX =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/crown.png");

    private final List<String> voterNames;
    private final List<String> candidateNames;
    private final long openedAtMs;
    private long[] revealAtMs = new long[0];
    private int soundedCount = 0;

    public TribeVoteScreen(List<String> voterNames, List<String> candidateNames) {
        super(Component.translatable("bannerbound.tribe_vote.title"));
        this.voterNames = voterNames;
        this.candidateNames = candidateNames;
        this.openedAtMs = System.currentTimeMillis();
    }

    @Override
    protected void init() {
        int n = voterNames.size();
        revealAtMs = new long[n];
        double delay = FIRST_DELAY_MS;
        long accum = openedAtMs;
        for (int i = 0; i < n; i++) {
            accum += (long) delay;
            revealAtMs[i] = accum;
            delay = Math.max(MIN_DELAY_MS, delay * DECAY_FACTOR);
        }
    }

    @Override
    protected boolean drawsDimmedBackground() {
        return false;
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        int revealed = 0;
        for (int i = 0; i < revealAtMs.length; i++) {
            if (now >= revealAtMs[i]) revealed++;
            else break;
        }

        if (revealed > soundedCount) {
            Minecraft mc = this.minecraft;
            if (mc != null && mc.getSoundManager() != null) {
                int newlyRevealed = revealed - soundedCount;
                for (int i = 0; i < newlyRevealed; i++) {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 1.0f, 0.3f));
                }
            }
            soundedCount = revealed;
        }

        int cx = this.width / 2;

        Component title = Component.translatable("bannerbound.tribe_vote.title")
            .withStyle(ChatFormatting.WHITE);
        g.pose().pushPose();
        g.pose().scale(2.0f, 2.0f, 1.0f);
        g.drawCenteredString(this.font, title, cx / 2, 40 / 2, 0xFFFFFFFF);
        g.pose().popPose();

        int crownSize = 24;
        int titleWidth = this.font.width(title.getString()) * 2;
        int crownY = 36 - crownSize / 2;
        g.blit(CROWN_TEX, cx - titleWidth / 2 - crownSize - 8, crownY,
            0f, 0f, crownSize, crownSize, crownSize, crownSize);
        g.blit(CROWN_TEX, cx + titleWidth / 2 + 8, crownY,
            0f, 0f, crownSize, crownSize, crownSize, crownSize);

        if (revealed == 0) {
            g.drawCenteredString(this.font,
                Component.translatable("bannerbound.tribe_vote.waiting")
                    .withStyle(ChatFormatting.GRAY),
                cx, this.height / 2 - 4, 0xFF888888);
            return;
        }

        int stackHeight = revealed * ROW_PITCH;
        int topY = this.height / 2 - stackHeight / 2;
        int rowX = cx - ROW_W / 2;
        for (int i = 0; i < revealed; i++) {
            int y = topY + i * ROW_PITCH;
            drawVoteRow(g, rowX, y, voterNames.get(i), candidateNames.get(i));
        }

        if (revealed >= revealAtMs.length) {
            g.drawCenteredString(this.font,
                Component.translatable("bannerbound.tribe_vote.press_esc")
                    .withStyle(ChatFormatting.GRAY),
                cx, this.height - 24, 0xFF888888);
        }
    }

    private void drawVoteRow(GuiGraphics g, int x, int y, String voter, String candidate) {
        g.fill(x, y, x + ROW_W, y + ROW_H, 0xC0808080);
        g.renderOutline(x, y, ROW_W, ROW_H, 0xFFAAAAAA);
        g.drawString(this.font, voter, x + 10, y + (ROW_H - 8) / 2, 0xFFFFFFFF, false);
        int candidateW = this.font.width(candidate);
        g.drawString(this.font, Component.literal(candidate).withStyle(ChatFormatting.GOLD),
            x + ROW_W - candidateW - 10, y + (ROW_H - 8) / 2, 0xFFFFAA00, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
