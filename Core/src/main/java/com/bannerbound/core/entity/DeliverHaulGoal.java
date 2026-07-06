package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * Anarchy carry-home delivery. When a self-organizing gatherer has no real storage it deposits its
 * harvest into its carry pack (CitizenEntity#getAnarchyHaul) instead of a chest; once that pack is
 * full (and the citizen has yielded its work goal), this goal walks it to the town hall and dumps the
 * load on the ground there, the worker physically hauling it home rather than the loot teleporting.
 *
 * Sits at the same priority as the work goals but is registered BEFORE them, so when a delivery is
 * due it wins the MOVE flag (the gatherer has already yielded, so they are never both runnable); this
 * ordering is load-bearing. Dormant whenever the carry pack is empty, which is always for a citizen
 * with a real drop-off. If the town hall vanishes mid-trip or the walk times out (walled off / fell
 * in a hole), the load is dumped where the citizen stands so it is never carried forever.
 */
@ApiStatus.Internal
public class DeliverHaulGoal extends Goal {
    private static final double REACH_SQ = 6.25;
    private static final int DELIVER_TIMEOUT_TICKS = 300;
    private static final int REPATH_INTERVAL = 20;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private BlockPos townHall;
    private int tripTicks;
    private boolean done;

    public DeliverHaulGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(citizen.level() instanceof ServerLevel)) return false;
        if (!citizen.hasHaul()) return false;
        if (citizen.isWorking()) return false;
        if (!citizen.isAiActive()) return false;
        if (citizen.isChild()) return false;
        Settlement s = citizen.getSettlement();
        if (s == null || s.townHallPos() == null) return false;
        this.townHall = s.townHallPos();
        return true;
    }

    @Override
    public void start() {
        done = false;
        tripTicks = DELIVER_TIMEOUT_TICKS;
        citizen.setItemSlot(EquipmentSlot.MAINHAND, firstHaulItem());
        if (townHall != null) {
            citizen.getNavigation().moveTo(
                townHall.getX() + 0.5, townHall.getY(), townHall.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !done && citizen.hasHaul() && townHall != null;
    }

    @Override
    public void tick() {
        if (townHall == null) {
            dump(citizen.blockPosition());
            done = true;
            return;
        }
        tripTicks--;
        citizen.getLookControl().setLookAt(
            townHall.getX() + 0.5, townHall.getY() + 0.5, townHall.getZ() + 0.5);
        double distSq = citizen.distanceToSqr(
            townHall.getX() + 0.5, townHall.getY(), townHall.getZ() + 0.5);
        if (distSq <= REACH_SQ) {
            dump(townHall);
            done = true;
            return;
        }
        if (tripTicks <= 0) {
            dump(citizen.blockPosition());
            done = true;
            return;
        }
        if (citizen.getNavigation().isDone() || tripTicks % REPATH_INTERVAL == 0) {
            citizen.getNavigation().moveTo(
                townHall.getX() + 0.5, townHall.getY(), townHall.getZ() + 0.5, speedModifier);
        }
    }

    private void dump(BlockPos pos) {
        if (citizen.level() instanceof ServerLevel sl) {
            citizen.dumpHaulAt(sl, pos);
        }
    }

    @Override
    public void stop() {
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.getNavigation().stop();
        done = false;
        townHall = null;
    }

    private ItemStack firstHaulItem() {
        net.minecraft.world.SimpleContainer pack = citizen.getAnarchyHaul();
        for (int i = 0; i < pack.getContainerSize(); i++) {
            ItemStack s = pack.getItem(i);
            if (!s.isEmpty()) return s.copy();
        }
        return ItemStack.EMPTY;
    }
}
