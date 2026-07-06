package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.bannerbound.antiquity.event.RopeFenceEvents;
import com.bannerbound.antiquity.rope.RopeTies;

/**
 * Invisible marker placed in the cells a rope spans (by {@code RopeTies}, removed when the rope
 * breaks). Renders nothing (RenderShape.INVISIBLE) and has an empty outline shape, so it can never
 * be targeted or selected. It stores the rope line in blockstate for mob pathing / future tooling:
 * ROTATION reuses the vanilla 16-step property as plain storage for the rope's horizontal direction
 * through the cell (atan2(dz, dx) convention, set on placement), and OFFSET (0..20) is the line's
 * perpendicular position within the cell in px from centre biased by OFFSET_ZERO (px = value -
 * OFFSET_ZERO, so 10 = centred).
 *
 * <p>Collision is deliberately SPLIT per entity kind - this is what finally made the rope non-janky
 * for NPCs. Mobs (non-player living entities) get a REAL solid cell: combined with
 * isPathfindable=false they route around the rope, and pathfinding and physics agree by
 * construction - vanilla move() enforces the wall, so there is no clamp-vs-path fight, no
 * side-tracking, no jump-hop loop, no squeezing through a junction; mobs don't care that a diagonal
 * rope's block wall is staircased. Players (and non-living entities: items, projectiles) get EMPTY
 * collision: their blocking stays the smooth per-tick analytical clamp in {@code RopeFenceEvents}
 * against the rope's true segment, because a block band under the player was tried and REJECTED
 * (janky staircased feel on diagonals, and block collision dominates the smooth clamp when both act
 * on the same entity). The analytical clamp still runs for mobs as a backstop for the cells markers
 * can't occupy (non-replaceable span cells, the gap between directly-adjacent posts).
 */
public class RopeCollisionBlock extends Block {
    public static final MapCodec<RopeCollisionBlock> CODEC = simpleCodec(RopeCollisionBlock::new);
    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    public static final IntegerProperty OFFSET = IntegerProperty.create("offset", 0, 20);
    public static final int OFFSET_ZERO = 10;
    public static final int OFFSET_MAX = 20;

    public RopeCollisionBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ROTATION, 0).setValue(OFFSET, OFFSET_ZERO));
    }

    @Override
    protected MapCodec<RopeCollisionBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROTATION, OFFSET);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        // Mobs: real wall (must match isPathfindable=false). Players: empty - RopeFenceEvents clamps them.
        if (ctx instanceof EntityCollisionContext entityCtx
                && entityCtx.getEntity() instanceof LivingEntity
                && !(entityCtx.getEntity() instanceof Player)) {
            return Shapes.block();
        }
        return Shapes.empty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
