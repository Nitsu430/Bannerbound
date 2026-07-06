package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;

/**
 * Client game-bus hooks for the fletching minigame. While {@link FletchingScreen} is open and the
 * player is holding (stretching), widen the FOV in proportion to the cursor's rise - the "leaning
 * back to draw the string taut" feel - up to MAX_WIDEN (+15% FOV) at full stretch. Gated by
 * {@link Config#FLETCHING_FOV_EFFECT}.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class FletchingClientEvents {
    private static final float MAX_WIDEN = 0.15F;

    private FletchingClientEvents() {
    }

    @SubscribeEvent
    static void onComputeFov(ComputeFovModifierEvent event) {
        if (!FletchingScreen.MINIGAME_ACTIVE || !Config.FLETCHING_FOV_EFFECT.get()) {
            return;
        }
        float frac = Math.max(0.0F, Math.min(1.0F, FletchingScreen.STRETCH_FRACTION));
        event.setNewFovModifier(event.getNewFovModifier() * (1.0F + MAX_WIDEN * frac));
    }
}
