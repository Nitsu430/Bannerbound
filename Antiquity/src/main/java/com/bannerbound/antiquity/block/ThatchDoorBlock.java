package com.bannerbound.antiquity.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thatch curtain "door" that does NOT swing: right-clicking just toggles it between closed (a
 * solid straw panel you can't walk through) and open (passable - no collision). It reuses vanilla
 * {@link DoorBlock} for the two-tall placement, breaking, redstone and the OPEN toggle; only the
 * shape is overridden so "open" removes collision instead of sliding a leaf aside, and the
 * blockstate swaps the closed/open texture rather than rotating a hinge. The collision panel is a
 * thin 2px slab on the facing-side edge, matching the visible model (a 16x32x2 panel modelled on
 * the north edge and rotated by facing in the blockstate). Outline/selection is always the panel
 * even when open, so an open door can still be targeted to close it.
 */
public class ThatchDoorBlock extends DoorBlock {

    private static final VoxelShape NORTH_EDGE = Block.box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SOUTH_EDGE = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape WEST_EDGE  = Block.box(0, 0, 0, 2, 16, 16);
    private static final VoxelShape EAST_EDGE  = Block.box(14, 0, 0, 16, 16, 16);

    public ThatchDoorBlock(BlockSetType type, Properties properties) {
        super(type, properties);
    }

    private static VoxelShape panel(BlockState state) {
        // Inverted on purpose: the blockstate's y-rotation of the north-edge model puts a SOUTH-facing panel on the north edge.
        return switch (state.getValue(FACING)) {
            case SOUTH -> NORTH_EDGE;
            case NORTH -> SOUTH_EDGE;
            case EAST  -> WEST_EDGE;
            case WEST  -> EAST_EDGE;
            default    -> NORTH_EDGE;
        };
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return panel(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? Shapes.empty() : panel(state);
    }
}
