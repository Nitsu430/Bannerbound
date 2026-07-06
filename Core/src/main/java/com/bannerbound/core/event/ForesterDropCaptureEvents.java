package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DropOffContainers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Routes drops spawned during a forester's capture window straight into their assigned Forester's
 * Log block entity -- no ground items, no pickup walking. The window is opened by
 * ForesterWorkGoal.chopLog when it hands the tree to Pandas Falling Trees; PFT then spawns
 * ItemEntities along the falling-tree path over a couple of seconds, and this listener intercepts
 * each one (EntityJoinLevelEvent) before it enters the world.
 *
 * Anything the forester can't store (unknown item, workstation full, BE chunk unloaded,
 * workstation reassigned) is left to spawn as a normal ground ItemEntity. The window can't prove
 * an item came from the fell (PFT spawns drops with no originating block), so this must NEVER
 * delete outright: doing so also deleted player Q-drops and mob loot that happened to land in the
 * window. Captured stacks are shrunk to what was banked; the rest hits the ground.
 *
 * Constants: LOOKUP_INFLATE (24) is the broad-phase box half-extent, larger than the capture
 * radius so a citizen whose center sits at the capture-circle edge is still found.
 * CAPTURE_RADIUS_SQ (10^2) is tighter than the old 16-block sphere (which siphoned unrelated
 * player/mob drops) but still spans a normal tree's fall path; a giant's far-end drops just land
 * on the ground.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ForesterDropCaptureEvents {
    private static final double LOOKUP_INFLATE = 24.0;
    private static final double CAPTURE_RADIUS_SQ = 10.0 * 10.0;

    private ForesterDropCaptureEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return;

        BlockPos itemPos = itemEntity.blockPosition();
        AABB box = new AABB(itemPos).inflate(LOOKUP_INFLATE);
        List<CitizenEntity> candidates = level.getEntitiesOfClass(CitizenEntity.class, box,
            CitizenEntity::isCaptureWindowActive);
        if (candidates.isEmpty()) return;

        ItemStack remaining = stack;
        for (CitizenEntity citizen : candidates) {
            BlockPos center = citizen.getCaptureCenter();
            if (center == null) continue;
            if (center.distSqr(itemPos) > CAPTURE_RADIUS_SQ) continue;

            // NEVER cancel here: the window can't tell a fell drop from a player's dropped stack.
            if (!com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(
                    citizen.getSettlement(), null, remaining)) {
                continue;
            }

            Container depot = DropOffContainers.resolveDropOff(level, citizen.getDropOff());
            if (depot == null) continue;

            remaining = DropOffContainers.insert(depot, remaining);
            if (remaining.isEmpty()) {
                event.setCanceled(true);
                return;
            }
        }

        itemEntity.setItem(remaining);
    }
}
