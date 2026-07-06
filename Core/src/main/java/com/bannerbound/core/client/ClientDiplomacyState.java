package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.DiplomacyObjectivePayload;
import com.bannerbound.core.network.DiplomacyStatePayload;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the settlement's diplomacy state: rally status, victory-cooldown countdown, and
 * the per-settlement / per-barbarian relation rows, replaced wholesale by
 * {@link DiplomacyStatePayload}. Also holds the current {@link DiplomacyObjectivePayload} (the
 * tracked objective's label plus its world-marker position and color). Read by the Diplomacy tab
 * and its HUD.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientDiplomacyState {
    private static boolean rallying;
    private static int winnerCooldownSeconds;
    private static List<DiplomacyStatePayload.Row> rows = List.of();
    private static List<DiplomacyStatePayload.BarbarianRow> barbarianRows = List.of();
    private static DiplomacyObjectivePayload objective =
        new DiplomacyObjectivePayload(false, "", "", BlockPos.ZERO, 0xFFFFFF);

    private ClientDiplomacyState() {}

    public static void replace(DiplomacyStatePayload payload) {
        rallying = payload.rallying();
        winnerCooldownSeconds = payload.winnerCooldownSeconds();
        rows = List.copyOf(payload.rows());
        barbarianRows = List.copyOf(payload.barbarianRows());
    }

    public static boolean rallying() { return rallying; }
    public static int winnerCooldownSeconds() { return winnerCooldownSeconds; }
    public static List<DiplomacyStatePayload.Row> rows() { return rows; }
    public static List<DiplomacyStatePayload.BarbarianRow> barbarianRows() { return barbarianRows; }

    public static void objective(DiplomacyObjectivePayload payload) {
        objective = payload;
    }

    public static DiplomacyObjectivePayload objective() {
        return objective;
    }
}
