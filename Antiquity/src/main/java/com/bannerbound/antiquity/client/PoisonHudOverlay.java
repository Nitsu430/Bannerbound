package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;
import com.bannerbound.antiquity.poison.PoisonState;
import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The HUD-stage poison overlay. The wolfsbane "world goes cold and closes in" look (desaturate /
 * blur / vignette) is now a real framebuffer shader ({@link PoisonPostProcessor}); what remains
 * here is drawn over the crisp HUD: the warm-yellow antidote relief-flash (a full-screen fade
 * driven by {@link StatusClientEffects#healFlash}) and the curare eyelids -- heavy lids drooping
 * and fluttering as the player fights the stun (phase computed from the synced faint/wake
 * deadline attachments plus the configured stun length), then snapping to full black while
 * unconscious. Local player only; suppressed while the GUI is hidden.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PoisonHudOverlay {
    private PoisonHudOverlay() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        float time = (float) mc.level.getGameTime() + delta.getGameTimeDeltaPartialTick(false);
        float flash = StatusClientEffects.healFlash(time);
        if (flash > 0.0F) {
            int a = (int) (flash * 105);
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | 0xFFE85C);
        }
        renderCurareEyelids(mc, g, time);
    }

    private static void renderCurareEyelids(Minecraft mc, GuiGraphics g, float time) {
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (s.type() != PoisonType.CURARE) {
            return;
        }
        long now = mc.level.getGameTime();
        long faintAt = mc.player.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        long wakeAt = mc.player.getData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get());
        if (faintAt <= 0L) {
            return;
        }
        float cover;
        if (now < faintAt) {
            int stunTicks = Math.max(1, Config.POISON_CURARE_STUN_TICKS.get());
            float stunFrac = Mth.clamp((now - (faintAt - stunTicks)) / (float) stunTicks, 0.0F, 1.0F);
            cover = Math.min(0.92F, 0.20F + 0.65F * stunFrac + 0.05F * Mth.sin(time * 0.5F) * stunFrac);
        } else if (now < wakeAt) {
            cover = 1.0F;
        } else {
            return;
        }
        int lid = (int) (cover * g.guiHeight() * 0.5F);
        if (lid > 0) {
            g.fill(0, 0, g.guiWidth(), lid, 0xFF000000);
            g.fill(0, g.guiHeight() - lid, g.guiWidth(), g.guiHeight(), 0xFF000000);
        }
    }
}
