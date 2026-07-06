package com.bannerbound.core.api.settlement;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Extension point for appeal that does NOT come from a block type - e.g. the Antiquity expansion's
 * per-face plaster/trim coatings, which are decoration data rather than blocks and so are invisible
 * to the block-type tally. Contributors are summed per scanned position in
 * {@link HouseAppealData#scoreUnion} (homes and workshops). Core ships none; expansions register at
 * setup. Backed by a CopyOnWriteArrayList so registration at setup and the reader threads don't race.
 */
public final class AppealContributors {
    @FunctionalInterface
    public interface ExtraAppeal {
        double appeal(ServerLevel level, BlockPos pos);
    }

    private static final List<ExtraAppeal> LIST = new CopyOnWriteArrayList<>();

    private AppealContributors() {}

    public static void register(ExtraAppeal contributor) {
        LIST.add(contributor);
    }

    public static boolean hasAny() {
        return !LIST.isEmpty();
    }

    public static double extra(ServerLevel level, BlockPos pos) {
        double sum = 0.0;
        for (ExtraAppeal c : LIST) {
            sum += c.appeal(level, pos);
        }
        return sum;
    }
}
