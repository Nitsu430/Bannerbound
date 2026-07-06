package com.bannerbound.core.building;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workstation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Centralised building-rules check for workstation right-click flow. Wraps {@link BuildingValidator}
 * with the additional "one workstation per enclosed building" rule, picks the right validator tier
 * per workstation type, and emits the matching translatable error directly to the player, so each
 * block class shrinks to a single boolean check. Each workstation block still owns its own
 * chunk-owner / not-your-settlement check (those have block-specific error text); this helper
 * handles only the building geometry.
 *
 * <p>{@link #tierFor} maps type ids to a tier: every current workstation is ancient-era ROOF_ONLY;
 * add future late-era types here returning ENCLOSED. Unknown ids default to ENCLOSED so a
 * misregistered type fails loud, not silent. The one-per-building rule applies only to ENCLOSED tier
 * (ROOF_ONLY stations may sit close together - no walls means no well-defined building boundary).
 */
@ApiStatus.Internal
public final class WorkstationPlacement {
    private WorkstationPlacement() {
    }

    public static BuildingValidator.BuildingTier tierFor(String typeId) {
        if (typeId == null) return BuildingValidator.BuildingTier.ENCLOSED;
        return switch (typeId) {
            case "foresters_log", "diggers_slab", "farmers_granary", "fishers_creel",
                 "foragers_basket"
                -> BuildingValidator.BuildingTier.ROOF_ONLY;
            default -> BuildingValidator.BuildingTier.ENCLOSED;
        };
    }

    public static boolean checkBuilding(ServerLevel level, ServerPlayer player, BlockPos pos,
                                         BuildingValidator.BuildingTier tier, Settlement settlement) {
        BuildingValidator.Result result = BuildingValidator.validate(level, pos, tier);
        if (!result.valid()) {
            BlockPos fail = result.failPos();
            String key = switch (result.reason()) {
                case NO_INTERIOR -> "bannerbound.building.error.no_interior";
                case NO_ROOF -> "bannerbound.building.error.no_roof";
                case TOO_LARGE -> "bannerbound.building.error.too_large";
            };
            player.sendSystemMessage(Component.translatable(key, fail.getX(), fail.getY(), fail.getZ())
                .withStyle(ChatFormatting.RED));
            return false;
        }
        if (tier == BuildingValidator.BuildingTier.ENCLOSED) {
            for (Workstation existing : settlement.workstations().values()) {
                if (existing.pos().equals(pos)) continue;
                if (result.interior().contains(existing.pos())) {
                    player.sendSystemMessage(Component.translatable(
                            "bannerbound.building.error.another_workstation")
                        .withStyle(ChatFormatting.RED));
                    return false;
                }
            }
        }
        return true;
    }
}
