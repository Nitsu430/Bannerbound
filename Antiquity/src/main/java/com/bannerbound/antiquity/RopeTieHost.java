package com.bannerbound.antiquity;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * A block entity that can host rope ties, implemented by both the rope-fence post (one slot) and the
 * rope fence gate (two slots: left/right upright). RopeTies drives linking, breaking, collision fillers
 * and rendering generically through this interface, so posts and gates share one rope system and can
 * rope to each other. A rope's collision fillers are owned by one endpoint (its lower anchor);
 * getFillers/putFillers record that host's placed cells for the span to a given far anchor.
 */
public interface RopeTieHost {
    int slotCount();

    Set<RopeAnchor> connections(int slot);

    boolean addConnection(int slot, RopeAnchor other);

    boolean removeConnection(int slot, RopeAnchor other);

    List<BlockPos> getFillers(RopeAnchor other);

    void putFillers(RopeAnchor other, List<BlockPos> cells);

    default boolean isConnected(int slot) {
        return !connections(slot).isEmpty();
    }
}
