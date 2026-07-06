package com.bannerbound.antiquity.craft;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared server-side validation for the crafting-minigame COMPLETE/COMMIT paths: the station must
 * still be loaded and in reach (chunk loaded and within 8 blocks, i.e. distanceSqr <= 64), the
 * client-reported step count must match what the server observed, and a conservative minimum
 * game-time must have elapsed for the number of steps -- so a modified client can neither complete
 * instantly nor forge scores outside their legal 0-100 range.
 */
@ApiStatus.Internal
final class MinigameGuard {
    private MinigameGuard() {}

    static boolean stationInReach(ServerPlayer player, BlockPos pos) {
        return player.level().isLoaded(pos)
            && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    static boolean elapsedOk(ServerPlayer player, long startGameTime, int steps, int minTicksPerStep) {
        return player.serverLevel().getGameTime() - startGameTime >= (long) steps * minTicksPerStep;
    }

    static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
