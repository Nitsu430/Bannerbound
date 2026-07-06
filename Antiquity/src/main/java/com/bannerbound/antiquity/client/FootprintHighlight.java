package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.GroundDecalEntity;
import com.bannerbound.core.client.ClientResearchState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side "which tracks belong to the same animal" highlight for the hunting tracker. Examining
 * (right-clicking) a footprint remembers the animal that left it; {@link GroundDecalRenderer} then
 * tints every still-active track from that same animal cyan (lerping the track colour white->cyan
 * by {@link #strength}) so the trail reads as one continuous path. Re-examining any track re-arms
 * the tint and can switch to a different animal.
 *
 * <p>The whole effect is gated behind the {@code hunting_instincts} research: without the flag (or
 * on an ungrouped decal) {@link #examine} is a no-op and tracks render their normal colour. Holds a
 * single "current highlight" (one animal at a time, groupId, -1 = none), fading linearly to zero
 * over FADE_TICKS (~10s, long enough to walk a trail) from the last examine's client game-time.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class FootprintHighlight {
    // Constant whose name contains FLAG so the Research Tree Editor's *FLAG* scan auto-discovers it.
    public static final String FLAG_HUNTING_INSTINCTS = "bannerbound.hunting_instincts";
    private static final float FADE_TICKS = 200.0F;

    private static int groupId = -1;
    private static long startGameTime = Long.MIN_VALUE;

    private FootprintHighlight() {}

    public static void examine(GroundDecalEntity decal) {
        int group = decal.getGroupId();
        if (group < 0 || !ClientResearchState.hasFlag(FLAG_HUNTING_INSTINCTS)) {
            return;
        }
        groupId = group;
        startGameTime = decal.level().getGameTime();
    }

    public static float strength(int group, long gameTime, float partialTick) {
        if (group < 0 || group != groupId) {
            return 0.0F;
        }
        float age = (gameTime - startGameTime) + partialTick;
        if (age < 0.0F || age >= FADE_TICKS) {
            return 0.0F;
        }
        return 1.0F - age / FADE_TICKS;
    }
}
