package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Walk evaluator with citizen-specific tweaks over vanilla so citizens path through their own
 * settlement infrastructure:
 * <ul>
 *   <li>Fence gates: vanilla treats a closed fence gate as FENCE/impassable (and the modded rope
 *       gate as BLOCKED). getPathType matches the fence_gates tag (covering vanilla and rope gates)
 *       and reclassifies a closed gate to {@link PathType#DOOR_WOOD_CLOSED} so A* routes through it
 *       with canOpenDoors set; {@link OpenFenceGateGoal} opens it on arrival. An OPEN gate's cell is
 *       force-accepted as a walkable node in findAcceptedNode, because vanilla otherwise rejects it
 *       (its non-empty render shape fails the floor/occupancy check) and A* could not route through.</li>
 *   <li>Dirt-path preference: gated on the Roads policy, or on a road-building stocker doing an
 *       outpost haul leg (which prefers paths regardless, so later trips follow the road its first
 *       trip trampled). A walkable tile not standing on dirt_path gets +OFF_PATH_MALUS (1.0, tuned
 *       for a detour up to ~2x straight-line without starving the node budget), so routes hug path
 *       infrastructure when it is close. The malus stays positive because a negative costMalus is
 *       the pathfinder's "blocked" sentinel.</li>
 *   <li>Fisher avoid-water: a fisher that must not swim hard-blocks actual WATER (a high malus was
 *       not enough - a short diagonal swim could out-cost the long way round). WATER_BORDER (the
 *       land the pier sits on) stays walkable so the pier itself remains routable.</li>
 * </ul>
 * Antiquity's partial-collision workstation blocks (mortar, basket, crafting stone) declare
 * isPathfindable=false themselves, so vanilla already classifies them BLOCKED here - no id
 * special-case needed. roadsActive/avoidWater are cached once per path build in {@link #prepare}
 * to avoid a per-node settlement lookup.
 */
@ApiStatus.Internal
public class CitizenNodeEvaluator extends WalkNodeEvaluator {
    private static final float OFF_PATH_MALUS = 1.0F;

    private boolean roadsActive = false;
    private boolean avoidWater = false;

    @Override
    public void prepare(net.minecraft.world.level.PathNavigationRegion region,
                        net.minecraft.world.entity.Mob mob) {
        super.prepare(region, mob);
        roadsActive = false;
        avoidWater = false;
        if (mob instanceof CitizenEntity c) {
            com.bannerbound.core.api.settlement.Settlement s = c.getSettlement();
            roadsActive = (s != null && s.hasPolicy(
                com.bannerbound.core.api.settlement.PolicyRegistry.ROADS))
                || c.isRoadBuilding();
            avoidWater = c.isAvoidWaterPathing();
        }
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        PathType base = super.getPathType(context, x, y, z);
        BlockState state = context.getBlockState(new BlockPos(x, y, z));
        if (avoidWater && base == PathType.WATER) {
            return PathType.BLOCKED;
        }
        if (state.is(BlockTags.FENCE_GATES) && state.hasProperty(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.OPEN)) {
            return PathType.DOOR_WOOD_CLOSED;
        }
        return base;
    }

    @Override
    protected Node findAcceptedNode(int x, int y, int z, int verticalDeltaLimit, double nodeFloorLevel,
                                    Direction direction, PathType pathType) {
        Node node = super.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathType);
        if (node == null) {
            // Vanilla rejects an OPEN gate's cell (non-empty render shape) though it is passable;
            // force-accept it as a walkable node so A* can route through the gate.
            BlockState g = this.currentContext.getBlockState(new BlockPos(x, y, z));
            if (g.is(BlockTags.FENCE_GATES) && g.hasProperty(BlockStateProperties.OPEN)
                    && g.getValue(BlockStateProperties.OPEN)) {
                Node n = this.getNode(x, y, z);
                n.type = PathType.OPEN;
                n.costMalus = Math.max(n.costMalus, 0.0F);
                return n;
            }
            return null;
        }
        if (roadsActive && node.type == PathType.WALKABLE && node.costMalus >= 0.0F) {
            BlockState ground = this.currentContext.getBlockState(
                new BlockPos(node.x, node.y - 1, node.z));
            if (!ground.is(Blocks.DIRT_PATH)) {
                node.costMalus += OFF_PATH_MALUS;
            }
        }
        return node;
    }
}
