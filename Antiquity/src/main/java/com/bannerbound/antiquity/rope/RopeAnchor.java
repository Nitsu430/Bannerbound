package com.bannerbound.antiquity.rope;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.RopeFenceGateBlock;
import com.bannerbound.antiquity.block.RopeFencePostBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One end of a rope: a block position plus a <b>slot</b> identifying which tie point on that block.
 * A rope-fence post has a single tie point (slot 0, its centre); a rope fence gate has two (slot 0 =
 * the left upright, slot 1 = the right upright). Modelling a tie point this way lets any tie point
 * rope to any other uniformly -- post to post, post to gate, gate to gate. {@link #compareTo} gives
 * a total order over anchors so each rope is owned/drawn by exactly one of its two ends, and
 * {@link #worldTie} resolves an anchor to its world-space tie point (null when the block is no
 * longer a tie host), rotating the gate's model-space upright offsets to match the blockstate
 * y-rotation.
 */
@ApiStatus.Internal
public record RopeAnchor(BlockPos pos, int slot) implements Comparable<RopeAnchor> {
    public static final double TIE_Y = 13.0 / 16.0; // tie height = top of the rope coil in the post model

    public RopeAnchor immutable() {
        return new RopeAnchor(pos.immutable(), slot);
    }

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putLong("P", pos.asLong());
        t.putInt("S", slot);
        return t;
    }

    public static RopeAnchor fromTag(CompoundTag t) {
        return new RopeAnchor(BlockPos.of(t.getLong("P")), t.getInt("S"));
    }

    @Override
    public int compareTo(RopeAnchor o) {
        int c = Long.compareUnsigned(pos.asLong(), o.pos.asLong());
        return c != 0 ? c : Integer.compare(slot, o.slot);
    }

    public static Vec3 worldTie(BlockGetter level, RopeAnchor a) {
        BlockState st = level.getBlockState(a.pos());
        double y = a.pos().getY() + TIE_Y;
        if (st.getBlock() instanceof RopeFencePostBlock) {
            return new Vec3(a.pos().getX() + 0.5, y, a.pos().getZ() + 0.5);
        }
        if (st.getBlock() instanceof RopeFenceGateBlock) {
            double modelX = a.slot() == 0 ? RopeFenceGateBlock.LEFT_X : RopeFenceGateBlock.RIGHT_X;
            double[] off = rotate(modelX - 0.5, 0.0, st.getValue(RopeFenceGateBlock.FACING));
            return new Vec3(a.pos().getX() + 0.5 + off[0], y, a.pos().getZ() + 0.5 + off[1]);
        }
        return null;
    }

    private static double[] rotate(double ox, double oz, Direction facing) {
        return switch (facing) {
            case EAST -> new double[] {-oz, ox};
            case SOUTH -> new double[] {-ox, -oz};
            case WEST -> new double[] {oz, -ox};
            default -> new double[] {ox, oz};
        };
    }
}
