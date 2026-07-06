package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Guard WorkGoal -- a settlement's standing watch (GUARD_PLAN.md). By default the guard patrols the
 * claim perimeter (the ring of claimed chunks that borders unclaimed land, the edge an enemy crosses),
 * weapon in hand. If the player has marked guard posts with the Foreman's Rod ("guard" point
 * selections in the BlockSelectionRegistry), the guard instead mans a post: it walks there and holds
 * within POST_HOLD_RADIUS_SQ, pacing a little so the watch doesn't read as statues. Open posts
 * distribute one-guard-per-post through GuardPostClaims; a rod-bound post is private to its citizen.
 * findGuardPost prefers a post bound to this citizen, then the open one it already mans, then the
 * nearest unmanned open post; guards beyond the post count fall back to the perimeter beat (kept local
 * to the guard via BEAT_CHUNK_RADIUS rather than marching the whole border).
 *
 * <p>It does NOT fight here: combat lives in the priority-0 GuardCombatGoal, which preempts this goal
 * whenever a hostile is in range and returns the guard to its beat after.
 *
 * <p>Not a gatherer: guards produce no haul, so this extends WorkGoal directly (not GathererWorkGoal)
 * and is registered gatherer(false) -- a government-assigned institution, never anarchy self-employed.
 * canStartWork runs its OWN readiness (job + a settlement with claims) rather than isGatherJobReady,
 * which gates on a drop-off depot a guard (depositing nothing) never has.
 *
 * <p>Weapon is a real logistics cost: drawn from the settlement storage pool through normal JobTools
 * provisioning (the "guard" tool-age role, with slings/bows appended -- see JobTools.allowedToolsFor).
 * No conjured fallback -- an empty armory means a bare-handed watch that patrols the same but fights at
 * fist damage (GuardCombatGoal). weaponAttackDamage/weaponAttackSpeed read the held ItemStack's own
 * mainhand ADD_VALUE attribute modifiers (fist 1.0 + damage mods; player-baseline 4.0 + speed mods),
 * so a stocked bronze sword is genuinely better than a bone club. Shared currentWeapon keeps patrol
 * and combat holding the same blade.
 */
@ApiStatus.Internal
public class GuardWorkGoal extends WorkGoal {
    public static final String JOB_TYPE_ID = "guards_post";
    public static final String SELECTION_TYPE = "guard";

    public static final TagKey<Item> GUARD_SLINGS_TAG = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath("bannerbound", "guard_slings"));

    private static final double ARRIVE_SQ = 2.2 * 2.2;
    private static final int ROAM_TIMEOUT_TICKS = 300;
    private static final int BEAT_CHUNK_RADIUS = 8;
    private static final double POST_HOLD_RADIUS_SQ = 8.0 * 8.0;
    private static final int POST_PACE_RANGE = 3;
    private static final int POST_TICK_INTERVAL = 60;

    private BlockPos beatPos;
    private int roamAge;
    @Nullable private BlockPos postPos;
    private int postCooldown;

    public GuardWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    @Override
    protected boolean canStartWork() {
        // Guard readiness is its OWN check, NOT isGatherJobReady (which gates on a drop-off depot a guard never has).
        if (!JOB_TYPE_ID.equals(citizen.getJobType())) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement s = citizen.getSettlement();
        if (s == null || s.claimedChunks().isEmpty()) return false;
        JobTools.tryEquipToolFromStorage(citizen, s);
        postPos = findGuardPost(sl, s);
        if (postPos != null) return true;
        if (beatPos == null) {
            beatPos = pickBeatPos(sl, s);
            roamAge = 0;
        }
        return beatPos != null;
    }

    @Override
    protected boolean canKeepWorking() {
        return JOB_TYPE_ID.equals(citizen.getJobType());
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        equipWeapon();
        postCooldown = 0;
        if (postPos != null) {
            moveTo(postPos);
        } else if (beatPos != null) {
            moveTo(beatPos);
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        GuardPostClaims.releaseAll(citizen.getId());
        beatPos = null;
        postPos = null;
        roamAge = 0;
    }

    @Override
    public void tick() {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        if (postPos != null) {
            tickPost(sl, s);
            return;
        }
        tickPatrol(sl, s);
    }

    private void tickPost(ServerLevel sl, Settlement s) {
        if (--postCooldown > 0) {
            if (citizen.getNavigation().isDone() && distSqTo(postPos) > POST_HOLD_RADIUS_SQ) {
                moveTo(postPos);
            }
            return;
        }
        postCooldown = POST_TICK_INTERVAL;
        BlockPos current = findGuardPost(sl, s);
        if (current == null) {
            postPos = null;
            return;
        }
        postPos = current;
        if (distSqTo(postPos) > POST_HOLD_RADIUS_SQ) {
            moveTo(postPos);
            return;
        }
        int x = postPos.getX() + citizen.getRandom().nextInt(POST_PACE_RANGE * 2 + 1) - POST_PACE_RANGE;
        int z = postPos.getZ() + citizen.getRandom().nextInt(POST_PACE_RANGE * 2 + 1) - POST_PACE_RANGE;
        int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos p = new BlockPos(x, y, z);
        if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
            moveTo(p);
        }
    }

    private double distSqTo(BlockPos p) {
        return citizen.position().distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }

    @Nullable
    private BlockPos findGuardPost(ServerLevel sl, Settlement s) {
        ServerLevel overworld = sl.getServer().overworld();
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        double bestSq = Double.MAX_VALUE;
        boolean bestOpen = false;
        for (BlockSelection sel : BlockSelectionRegistry.get(overworld).getForSettlement(s.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            boolean open = sel.targetsAllWorkers();
            int score;
            if (!open) {
                score = 0;
            } else if (GuardPostClaims.ownedBy(anchor, citizen.getId())) {
                score = 1;
            } else if (!GuardPostClaims.isClaimedByOther(sl, anchor, citizen.getId())) {
                score = 2;
            } else {
                continue;
            }
            double dSq = citizen.distanceToSqr(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            if (score < bestScore || (score == bestScore && dSq < bestSq)) {
                best = anchor;
                bestScore = score;
                bestSq = dSq;
                bestOpen = open;
            }
        }
        if (best != null && bestOpen) {
            GuardPostClaims.claim(best, citizen.getId());
        }
        return best;
    }

    private void tickPatrol(ServerLevel sl, Settlement s) {
        if (beatPos == null) {
            beatPos = pickBeatPos(sl, s);
            roamAge = 0;
            if (beatPos != null) moveTo(beatPos);
            return;
        }
        double d = distSqTo(beatPos);
        if (d <= ARRIVE_SQ || ++roamAge > ROAM_TIMEOUT_TICKS) {
            beatPos = pickBeatPos(sl, s);
            roamAge = 0;
            if (beatPos != null) moveTo(beatPos);
            return;
        }
        if (citizen.getNavigation().isDone()) moveTo(beatPos);
    }

    private void equipWeapon() {
        ItemStack tool = citizen.getJobTool();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, tool.isEmpty() ? ItemStack.EMPTY : tool.copy());
    }

    static ItemStack currentWeapon(CitizenEntity citizen) {
        return citizen.getJobTool();
    }

    static boolean isBowWeapon(ItemStack stack) {
        return stack.is(HunterWorkGoal.HUNTER_BOWS_TAG) || stack.getItem() instanceof BowItem;
    }

    static boolean isSlingWeapon(ItemStack stack) {
        return stack.is(GUARD_SLINGS_TAG);
    }

    static boolean isRangedWeapon(ItemStack stack) {
        return !stack.isEmpty() && (isBowWeapon(stack) || isSlingWeapon(stack));
    }

    static double weaponAttackDamage(ItemStack stack) {
        return 1.0 + sumAttribute(stack, true);
    }

    static double weaponAttackSpeed(ItemStack stack) {
        return 4.0 + sumAttribute(stack, false);
    }

    private static double sumAttribute(ItemStack stack, boolean damage) {
        if (stack.isEmpty()) return 0.0;
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 0.0;
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (!e.slot().test(EquipmentSlot.MAINHAND)) continue;
            var attr = damage ? Attributes.ATTACK_DAMAGE : Attributes.ATTACK_SPEED;
            if (e.attribute().value() != attr.value()) continue;
            if (e.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum += e.modifier().amount();
            }
        }
        return sum;
    }

    private BlockPos pickBeatPos(ServerLevel sl, Settlement s) {
        List<Long> perimeter = perimeterChunks(s);
        List<Long> pool = perimeter.isEmpty() ? new ArrayList<>(s.claimedChunks()) : perimeter;
        if (pool.isEmpty()) return null;
        ChunkPos here = new ChunkPos(citizen.blockPosition());
        List<Long> nearby = new ArrayList<>();
        for (long packed : pool) {
            ChunkPos cp = new ChunkPos(packed);
            if (Math.abs(cp.x - here.x) <= BEAT_CHUNK_RADIUS && Math.abs(cp.z - here.z) <= BEAT_CHUNK_RADIUS) {
                nearby.add(packed);
            }
        }
        List<Long> chosen = nearby.isEmpty() ? pool : nearby;
        for (int attempt = 0; attempt < 12; attempt++) {
            ChunkPos cp = new ChunkPos(chosen.get(citizen.getRandom().nextInt(chosen.size())));
            int x = (cp.x << 4) + citizen.getRandom().nextInt(16);
            int z = (cp.z << 4) + citizen.getRandom().nextInt(16);
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
                return p;
            }
        }
        return null;
    }

    private static List<Long> perimeterChunks(Settlement s) {
        Set<Long> claimed = s.claimedChunks();
        List<Long> out = new ArrayList<>();
        for (long packed : claimed) {
            ChunkPos cp = new ChunkPos(packed);
            if (!claimed.contains(ChunkPos.asLong(cp.x + 1, cp.z))
                    || !claimed.contains(ChunkPos.asLong(cp.x - 1, cp.z))
                    || !claimed.contains(ChunkPos.asLong(cp.x, cp.z + 1))
                    || !claimed.contains(ChunkPos.asLong(cp.x, cp.z - 1))) {
                out.add(packed);
            }
        }
        return out;
    }

    private void moveTo(BlockPos p) {
        if (p == null) return;
        citizen.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, skilledSpeed());
    }
}
