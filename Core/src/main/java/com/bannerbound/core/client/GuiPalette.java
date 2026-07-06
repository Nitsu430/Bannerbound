package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.ClaimEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * THE shared GUI color vocabulary. Every panel screen draws its chrome and text from these roles
 * instead of ad-hoc hex constants, so the whole mod shifts together when a value is tuned. Screens
 * with a deliberate theme of their own (codex gold, barbarian slate, minigame warm-tones) keep
 * their local palettes -- this class is the default, not a straitjacket.
 *
 * <p>Identity accents: settlement-scoped screens wear the settlement's banner colors (ARGB, most-
 * present dye first) in their chrome. {@link #localIdentityAccents()} resolves them from the claim
 * under the player's feet (same lookup as the "Currently in ..." HUD, so no per-screen network
 * plumbing) and returns empty on unclaimed ground so callers fall back to the neutral border;
 * {@link #identityAccents(int)} resolves from a known founding-color ordinal a payload already
 * carries, and is never empty. Resolve accents ONCE at screen construction, not per frame.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class GuiPalette {

    private GuiPalette() {}

    public static final int PANEL_BG = 0xFF101010;
    public static final int PANEL_BORDER = 0xFF606060;
    public static final int WELL_BG = 0xFF1A1A1A;

    public static final int TITLE = 0xFFFFFFFF;
    public static final int SUBTITLE = 0xFFCCCCCC;
    public static final int LABEL = 0xFFAAAAAA;
    public static final int MUTED = 0xFF999999;

    public static final int GOOD = 0xFF2EB872;
    public static final int WARN = 0xFFE9D24A;
    public static final int BAD = 0xFFE57761;

    public static List<Integer> localIdentityAccents() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return List.of();
        ClaimEntry entry = ClientClaimState.getEntry(new ChunkPos(mc.player.blockPosition()).toLong());
        if (entry == null) return List.of();
        return identityAccents(entry.colorIndex());
    }

    public static List<Integer> identityAccents(int colorOrdinal) {
        List<Integer> rgbs = ClientIdentityState.rgbs(colorOrdinal);
        List<Integer> accents = new ArrayList<>(rgbs.size());
        for (int rgb : rgbs) accents.add(0xFF000000 | rgb);
        return List.copyOf(accents);
    }

    public static int primary(List<Integer> accents) {
        return accents.isEmpty() ? PANEL_BORDER : accents.get(0);
    }
}
