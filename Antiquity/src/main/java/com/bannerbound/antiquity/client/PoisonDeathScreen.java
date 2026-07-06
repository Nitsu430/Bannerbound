package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A poison-flavoured death screen shown instead of the vanilla one when the player dies poisoned
 * (see {@link StatusClientEffects#onDeathScreenOpening}). Subclasses {@link DeathScreen} so the
 * inherited {@code init()} still builds the Respawn / Title-menu buttons and the post-death delay
 * (the super's death-cause argument is passed as null and never rendered). render() does not call
 * super: it swaps vanilla's red wash for a dark gradient tinted toward the poison's
 * {@code tintColor()}, draws a 2x-scaled title plus a flavour line at the vanilla
 * cause-of-death spot -- both pulled from lang by poison id ({@code death.bannerbound.<id>.title}
 * / {@code .flavor}) -- then renders the inherited buttons itself.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PoisonDeathScreen extends DeathScreen {
    private final PoisonType poison;
    private final Component poisonTitle;
    private final Component flavor;

    public PoisonDeathScreen(PoisonType poison, boolean hardcore) {
        super(null, hardcore);
        this.poison = poison;
        this.poisonTitle = Component.translatable("death.bannerbound." + poison.id() + ".title");
        this.flavor = Component.translatable("death.bannerbound." + poison.id() + ".flavor");
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int top = 0x96000000 | darken(poison.tintColor(), 0.30F);
        g.fillGradient(0, 0, this.width, this.height, top, 0xC0080808);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.pose().pushPose();
        g.pose().scale(2.0F, 2.0F, 2.0F);
        g.drawCenteredString(this.font, this.poisonTitle, this.width / 2 / 2, 30, 0xFFFFFFFF);
        g.pose().popPose();
        g.drawCenteredString(this.font, this.flavor, this.width / 2, 85, 0xFFE8DCDC);
        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }
    }

    private static int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int gg = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (gg << 8) | b;
    }
}
