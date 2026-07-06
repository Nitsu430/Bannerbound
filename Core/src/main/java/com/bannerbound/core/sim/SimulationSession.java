package com.bannerbound.core.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Server-side state for one running {@code /bannerbound simulate} crowd-LOD stress test. Throwaway
 * by design: holds only what the duration timer and the broadcast snapshot need. The decorative
 * crowd itself lives entirely on the client (generated from {@link #seed}); the server tracks just
 * the handful of real {@link com.bannerbound.core.entity.CitizenEntity} it spawned for the near band
 * (their UUIDs in {@link #spawned}) so it can discard them on cleanup. {@link #eraOrdinal} is the
 * settlement era at start and drives era-correct mover skins on the client.
 */
public final class SimulationSession {
    public final UUID settlementId;
    public final BlockPos townHall;
    public final int radius;
    public final int believedPopulation;
    public final long seed;
    public final long endGameTick;
    public final int eraOrdinal;
    public final List<UUID> spawned = new ArrayList<>();

    public SimulationSession(UUID settlementId, BlockPos townHall, int radius,
                             int believedPopulation, long seed, long endGameTick, int eraOrdinal) {
        this.settlementId = settlementId;
        this.townHall = townHall;
        this.radius = radius;
        this.believedPopulation = believedPopulation;
        this.seed = seed;
        this.endGameTick = endGameTick;
        this.eraOrdinal = eraOrdinal;
    }
}
