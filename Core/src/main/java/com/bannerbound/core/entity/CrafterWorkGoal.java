package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.ChunkBeauty;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkBlockLocks;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * The Crafter's work loop (CRAFTER_PLAN.md Phase 2): resolve the citizen's bound workshop, claim a
 * free work block whose executor has something to craft from workshop storage, walk there, withdraw
 * the inputs, play the craft (arm swings per beat; the executor adds the station's sounds/particles/
 * visible pile), then deposit the finished item back into storage. Subclass of WorkGoal; the generic
 * "crafter" plus registry jobs (Carpenter, etc.) that bind to a Workshop all run through it.
 *
 * Crafting is demand-driven: queued orders, chain-derived auto-orders, or positive min-stock deficits
 * pick the next craft. Stored inputs alone do not make a crafter keep producing. When nothing can be
 * made we split NO_ORDERS (nothing wanted) from NEED_MATERIALS (a craft is wanted but its inputs are
 * not stocked yet, per an executor's non-empty missingInputs) so the Job-tab headline stays honest.
 *
 * In a mixed workshop each worker locks to ONE station family (its "position") so experience pools in
 * a single profession instead of smearing across whatever block is free; an unpositioned worker
 * self-assigns to the family it has the most XP in, and a stale position (family gone) clears for a
 * re-pick. workTypeId is the CLAIMED block's family (not the workshop's derived type, which can be
 * MIXED) and is the XP bucket each completed craft pays into.
 *
 * The claimed work block is locked for the craft's duration (WorkBlockLocks) so players can't disturb
 * the pile mid-craft. Inputs are withdrawn all-or-nothing and tracked in withdrawn so an abort
 * (station broken/swapped, interruption) always returns them; reusable (non-consumed) inputs are
 * returned on finish. Products are never voided: storage overflow pops the item to the world.
 *
 * Two knobs scale the base craft duration: crafter skill (XP saturation, novice ~1.0x down to ~0.6x
 * master) and mood (happier is faster). One completed craft grants one XP into the claimed family's
 * bucket, scaled by workshop appeal (0.8x..1.25x, never zero) which also echoes a LOVELY_WORKPLACE /
 * DREARY_WORKPLACE mood thought, refreshed at most once per active thought so a spree can't stack it.
 */
@ApiStatus.Internal
public class CrafterWorkGoal extends WorkGoal {
    public static final String JOB_TYPE_ID = "crafter";

    private static final long VALIDATE_MAX_AGE_TICKS = 100L;
    private static final double USE_DIST_SQ = 2.6 * 2.6;

    private Workshop workshop;
    private BlockPos workBlock;
    private BlockPos targetBlock;
    private WorkExecutor executor;
    private WorkExecutor.Craft craft;
    private String workTypeId;
    private final List<ItemStack> withdrawn = new ArrayList<>();
    private boolean started;
    private int ticksLeft;
    private int effectiveTicks;
    private int beatsDone;
    private int repathCooldown;
    private final String jobTypeId;
    @Nullable
    private final String fixedWorkshopTypeId;

    public CrafterWorkGoal(CitizenEntity citizen, double speedModifier) {
        this(citizen, speedModifier, JOB_TYPE_ID, null);
    }

    public CrafterWorkGoal(CitizenEntity citizen, double speedModifier,
                           String jobTypeId, @Nullable String fixedWorkshopTypeId) {
        super(citizen, speedModifier);
        this.jobTypeId = jobTypeId == null || jobTypeId.isBlank() ? JOB_TYPE_ID : jobTypeId;
        this.fixedWorkshopTypeId = fixedWorkshopTypeId;
    }

    public static boolean isWorkshopJob(@Nullable String typeId) {
        return JOB_TYPE_ID.equals(typeId)
            || com.bannerbound.core.api.job.CitizenJobRegistry.isWorkshopBound(typeId);
    }

    @Nullable
    public static String workshopTypeForJob(@Nullable String typeId) {
        if (typeId == null) return null;
        return com.bannerbound.core.api.job.CitizenJobRegistry.workshopTypeFor(typeId);
    }

    @Override
    protected String workstationTypeId() {
        return jobTypeId;
    }

    @Override
    protected boolean canStartWork() {
        if (!jobTypeId.equals(citizen.getJobType())) return false;
        if (toolRequired(jobTypeId) && !citizen.hasJobTool()) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement s = citizen.getSettlement();
        if (s == null) return false;
        Workshop w = s.getWorkshop(citizen.getAssignedWorkshopId());
        if (w == null) return false;
        if (Workshops.validateCached(sl, w, VALIDATE_MAX_AGE_TICKS) != Workshop.Status.VALID) {
            return false;
        }
        String position = fixedWorkshopTypeId != null ? fixedWorkshopTypeId : w.positionOf(citizen.getUUID());
        if (position != null && !hasStationOfType(sl, w, position)) {
            if (fixedWorkshopTypeId != null) {
                citizen.setCurrentWorkStatus(CitizenWorkStatus.NO_ORDERS);
                return false;
            } else {
                w.clearPosition(citizen.getUUID());
                position = null;
            }
        }

        BlockPos bestPos = null;
        WorkBlockRegistry.WorkBlockDef bestDef = null;
        WorkExecutor.Craft bestCraft = null;
        float bestXp = -1.0F;
        for (BlockPos p : w.workBlocks()) {
            if (WorkBlockLocks.isLockedByOther(p, citizen.getUUID())) continue;
            WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
            if (def == null || def.executor() == null) continue;
            if (position != null && !position.equals(def.workshopTypeId())) continue;
            WorkExecutor.Craft c = def.executor().chooseCraft(sl, s, w, p);
            if (c == null) continue;
            if (position != null) {
                bestPos = p;
                bestDef = def;
                bestCraft = c;
                break;
            }
            float xp = citizen.getJobXp(def.workshopTypeId());
            if (xp > bestXp) {
                bestXp = xp;
                bestPos = p;
                bestDef = def;
                bestCraft = c;
            }
        }
        if (bestPos == null) {
            boolean wantsSupply = false;
            for (BlockPos p : w.workBlocks()) {
                WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
                if (def == null || def.executor() == null) continue;
                if (position != null && !position.equals(def.workshopTypeId())) continue;
                if (!def.executor().missingInputs(sl, s, w, p).isEmpty()) {
                    wantsSupply = true;
                    break;
                }
            }
            citizen.setCurrentWorkStatus(
                wantsSupply ? CitizenWorkStatus.NEED_MATERIALS : CitizenWorkStatus.NO_ORDERS);
            return false;
        }
        if (position == null) {
            w.setPosition(citizen.getUUID(), bestDef.workshopTypeId());
            com.bannerbound.core.api.settlement.SettlementData
                .get(sl.getServer().overworld()).setDirty();
        } else if (fixedWorkshopTypeId != null && !position.equals(w.positionOf(citizen.getUUID()))) {
            w.setPosition(citizen.getUUID(), position);
            com.bannerbound.core.api.settlement.SettlementData
                .get(sl.getServer().overworld()).setDirty();
        }
        this.workshop = w;
        this.workBlock = bestPos.immutable();
        this.executor = bestDef.executor();
        this.craft = bestCraft;
        BlockPos target = bestDef.executor().workTarget(sl, s, w, bestPos, bestCraft);
        this.targetBlock = (target == null ? bestPos : target).immutable();
        this.workTypeId = bestDef.workshopTypeId();
        citizen.setCurrentWorkStatus(CitizenWorkStatus.WORKING);
        return true;
    }

    private static boolean toolRequired(String jobTypeId) {
        com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def =
            com.bannerbound.core.api.job.CitizenJobRegistry.byId(jobTypeId);
        return def != null && def.toolRequired();
    }

    private static boolean hasStationOfType(ServerLevel sl, Workshop w, String typeId) {
        for (BlockPos p : w.workBlocks()) {
            WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
            if (def != null && typeId.equals(def.workshopTypeId())) return true;
        }
        return false;
    }

    @Override
    protected boolean canKeepWorking() {
        return craft != null && workBlock != null && targetBlock != null;
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        started = false;
        beatsDone = 0;
        repathCooldown = 0;
        withdrawn.clear();
        WorkBlockLocks.lock(workBlock, citizen.getUUID());
        citizen.getNavigation().moveTo(
            targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5, speedModifier);
    }

    @Override
    public void tick() {
        if (!(citizen.level() instanceof ServerLevel sl) || craft == null) return;
        citizen.getLookControl().setLookAt(
            targetBlock.getX() + 0.5, targetBlock.getY() + 0.6, targetBlock.getZ() + 0.5);

        if (started && WorkBlockRegistry.of(sl.getBlockState(workBlock)) == null) {
            abort(sl);
            return;
        }

        double distSq = citizen.distanceToSqr(
            targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5);
        if (distSq > USE_DIST_SQ) {
            if (--repathCooldown <= 0) {
                repathCooldown = 20;
                citizen.getNavigation().moveTo(
                    targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5,
                    speedModifier);
            }
            return;
        }
        citizen.getNavigation().stop();

        if (!started) {
            for (ItemStack input : craft.inputs()) {
                ItemStack got = WorkshopStorage.extract(sl, workshop, input.getItem(), input.getCount());
                if (got.isEmpty()) {
                    returnWithdrawn(sl);
                    craft = null;
                    return;
                }
                withdrawn.add(got);
            }
            started = true;
            effectiveTicks = skillScaledTicks(craft.workTicks());
            ticksLeft = Math.max(1, effectiveTicks);
            executor.onStart(sl, citizen, workBlock, craft);
            return;
        }

        ticksLeft--;
        int beats = Math.max(1, craft.beats());
        int interval = Math.max(1, effectiveTicks / beats);
        int elapsed = effectiveTicks - ticksLeft;
        executor.onWorkTick(sl, citizen, workBlock, craft, ticksLeft);
        if (executor.externallyComplete(sl, citizen, workBlock, craft, ticksLeft)) {
            finishCraft(sl);
            return;
        }
        if (beatsDone < beats && elapsed >= (beatsDone + 1) * interval - interval / 2
                && elapsed % interval == 0) {
            citizen.swing(InteractionHand.MAIN_HAND);
            executor.onBeat(sl, citizen, workBlock, craft, beatsDone);
            beatsDone++;
        }
        if (ticksLeft <= 0) {
            ItemStack out = executor.finish(sl, citizen, workBlock, craft);
            if (!out.isEmpty()) {
                workshop.recordCraftOutput(out.getCount());
            }
            returnReusableInputs(sl, craft);
            ItemStack leftover = WorkshopStorage.insert(sl, workshop, out);
            if (!leftover.isEmpty()) {
                Block.popResource(sl, dropPos(), leftover);
            }
            if (workshop.fulfillOrder(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(out.getItem()).toString(),
                    executor.fulfilledOrderUnits(sl, workBlock, craft, out))) {
                com.bannerbound.core.api.settlement.SettlementData
                    .get(sl.getServer().overworld()).setDirty();
            }
            grantCraftReward(sl);
            citizen.consumeStamina(2);
            craft = null;
        }
    }

    private void finishCraft(ServerLevel sl) {
        ItemStack out = executor.finish(sl, citizen, workBlock, craft);
        if (!out.isEmpty()) {
            workshop.recordCraftOutput(out.getCount());
        }
        returnReusableInputs(sl, craft);
        ItemStack leftover = WorkshopStorage.insert(sl, workshop, out);
        if (!leftover.isEmpty()) {
            Block.popResource(sl, dropPos(), leftover);
        }
        if (workshop.fulfillOrder(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(out.getItem()).toString(),
                executor.fulfilledOrderUnits(sl, workBlock, craft, out))) {
            com.bannerbound.core.api.settlement.SettlementData
                .get(sl.getServer().overworld()).setDirty();
        }
        grantCraftReward(sl);
        citizen.consumeStamina(2);
        craft = null;
    }

    private void grantCraftReward(ServerLevel sl) {
        ChunkBeauty beauty = workshop.cachedAppealBeauty();
        int idx = beauty == null ? 0 : beauty.tierIndex();
        float mult = Math.max(0.8F, 1.0F + idx * 0.0625F);
        citizen.grantJobXp(workTypeId, mult, workTypeId);
        if (beauty == null || citizen.getThoughts() == null) return;
        ThoughtKind mood = idx >= ChunkBeauty.ATTRACTIVE.tierIndex() ? ThoughtKind.LOVELY_WORKPLACE
            : idx <= ChunkBeauty.UNAPPEALING.tierIndex() ? ThoughtKind.DREARY_WORKPLACE
            : null;
        if (mood != null && !citizen.getThoughts().has(mood, null)) {
            citizen.getThoughts().add(mood, null, sl.getGameTime(), citizen.getRandom());
            citizen.recomputeHappiness();
        }
    }

    private int skillScaledTicks(int baseTicks) {
        float xp = citizen.getJobXp(workTypeId);
        float skill = xp / (xp + com.bannerbound.core.api.quality.QualityMath.NPC_XP_HALF);
        float mult = 1.0F - 0.45F * skill;
        // Divide, not multiply: happinessPerformanceMultiplier is a speed, so it shortens duration.
        mult /= citizen.happinessPerformanceMultiplier();
        return Math.max(1, Math.round(baseTicks * mult));
    }

    private void abort(ServerLevel sl) {
        if (executor != null && craft != null) {
            executor.onAbort(sl, citizen, workBlock, craft);
        }
        returnWithdrawn(sl);
        craft = null;
    }

    private void returnWithdrawn(ServerLevel sl) {
        for (ItemStack s : withdrawn) {
            ItemStack leftover = WorkshopStorage.insert(sl, workshop, s);
            if (!leftover.isEmpty()) {
                Block.popResource(sl, dropPos(), leftover);
            }
        }
        withdrawn.clear();
    }

    private void returnReusableInputs(ServerLevel sl, WorkExecutor.Craft finishedCraft) {
        for (ItemStack s : withdrawn) {
            if (executor == null || executor.consumesInput(finishedCraft, s)) continue;
            ItemStack leftover = WorkshopStorage.insert(sl, workshop, s);
            if (!leftover.isEmpty()) {
                Block.popResource(sl, dropPos(), leftover);
            }
        }
        withdrawn.clear();
    }

    private BlockPos dropPos() {
        return (targetBlock != null ? targetBlock : workBlock).above();
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        if (citizen.level() instanceof ServerLevel sl && started && craft != null) {
            abort(sl);
        }
        if (workBlock != null) {
            WorkBlockLocks.unlock(workBlock, citizen.getUUID());
        }
        citizen.getNavigation().stop();
        workshop = null;
        workBlock = null;
        targetBlock = null;
        executor = null;
        craft = null;
        started = false;
    }
}
