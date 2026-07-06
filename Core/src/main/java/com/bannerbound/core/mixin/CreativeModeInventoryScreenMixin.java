package com.bannerbound.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.core.client.creative.CreativeSectionRenderer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Draws the creative-grid section banners (see CreativeSectionRenderer) at the tail of the screen
 * render so they sit on top of the empty cells of their row. The panel origin comes from NeoForge's
 * public getGuiLeft()/getGuiTop() accessors rather than @Shadow of leftPos/topPos: those fields are
 * declared on the superclass AbstractContainerScreen, and shadowing an inherited field fails to
 * resolve here.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin {

    @Shadow
    private static CreativeModeTab selectedTab;

    @Inject(method = "render", at = @At("TAIL"))
    private void bannerbound$renderSections(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                            CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        CreativeSectionRenderer.render(selectedTab, graphics, self.getGuiLeft(), self.getGuiTop(), mouseX, mouseY);
    }
}
