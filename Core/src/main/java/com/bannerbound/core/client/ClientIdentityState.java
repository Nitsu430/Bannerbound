package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.network.IdentitySyncPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the settlement identity color table (see {@code IdentitySyncPayload}):
 * founding-color ordinal -> banner-derived 0xRRGGBB list, most-present dye first, as many
 * colors as the banner has. EVERY client renderer that shows a settlement color resolves
 * through here, falling back to the founding {@code SettlementColor} rgb for ordinals the
 * table doesn't know (pre-design settlements, stale syncs) so nothing ever renders black;
 * malformed (empty) sync entries are skipped for the same reason. rgbs() returns all identity
 * colors (never empty), primaryRgb() the first, secondaryRgb() the accent (second color, or
 * the primary when the banner is single-color).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientIdentityState {

    private static final Map<Integer, List<Integer>> BY_COLOR_ORDINAL = new HashMap<>();

    private ClientIdentityState() {}

    public static void replace(IdentitySyncPayload payload) {
        BY_COLOR_ORDINAL.clear();
        for (int i = 0; i < payload.colorOrdinals().size(); i++) {
            List<Integer> rgbs = payload.rgbLists().get(i);
            if (rgbs.isEmpty()) continue;
            BY_COLOR_ORDINAL.put(payload.colorOrdinals().get(i), List.copyOf(rgbs));
        }
    }

    public static List<Integer> rgbs(int colorOrdinal) {
        List<Integer> rgbs = BY_COLOR_ORDINAL.get(colorOrdinal);
        return rgbs != null ? rgbs : List.of(SettlementColor.byIndex(colorOrdinal).rgb());
    }

    public static int primaryRgb(int colorOrdinal) {
        return rgbs(colorOrdinal).get(0);
    }

    public static int secondaryRgb(int colorOrdinal) {
        List<Integer> rgbs = rgbs(colorOrdinal);
        return rgbs.size() > 1 ? rgbs.get(1) : rgbs.get(0);
    }
}
