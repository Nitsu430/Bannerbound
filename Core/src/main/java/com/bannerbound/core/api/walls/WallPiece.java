package com.bannerbound.core.api.walls;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One placed instance of a WallDesign in the world: the unit WallLayoutEngine emits and the
 * blueprint / builder task board expands. Holds everything needed to expand into world block
 * states WITHOUT touching the level again -- terrain was sampled at layout time and frozen here
 * (per-column ground Y), per WALLS_PLAN.md ("plans are frozen; later claim/terrain changes do
 * nothing until the player explicitly adapts").
 *
 * <p>Geometry: (startX, startZ) is the world column of design-local (l=0, d=0); +l steps along
 * along() and +d along inward(). Block states are rotated by rotation() (designs authored
 * outward = north). length may be shorter than the design's length -- a run remainder that does
 * not fill a full instance becomes a truncated piece rather than a hole (plan sec B run fill).
 *
 * <p>STEPPED pieces fill a foundation from each column's ground up to baseY (DRAPE follows the
 * ground). Foundation is the column's own BOTTOM design block continued downward (no separate
 * material), only under columns that have a bottom block whose bottom is NOT openable (doors /
 * fence gates / trapdoors) -- continuing an openable duplicated gates on slopes. noFoundation is
 * a per-piece refinement that suppresses continuation entirely; waterGap pieces emit nothing;
 * groundY is indexed l * depth + d. forEachBlock visits foundation-then-design per column, bottom
 * up: the builder's task sort relies on lower blocks enumerating first. Courses at or below the
 * terrain are buried (omitted from the blueprint) so the level wall top stays walkable.
 */
public final class WallPiece {

    public enum Mode { DRAPE, STEPPED }

    private final String designId;
    private final WallDesign.Kind kind;
    private final Direction outward;
    private final int startX;
    private final int startZ;
    private final int length;
    private final int depth;
    private final Mode mode;
    private final int baseY;
    private final int[] groundY;
    private final boolean waterGap;
    private final boolean noFoundation;

    public WallPiece(String designId, WallDesign.Kind kind, Direction outward,
                     int startX, int startZ, int length, int depth,
                     Mode mode, int baseY, int[] groundY, boolean waterGap) {
        this(designId, kind, outward, startX, startZ, length, depth, mode, baseY, groundY,
            waterGap, false);
    }

    public WallPiece(String designId, WallDesign.Kind kind, Direction outward,
                     int startX, int startZ, int length, int depth,
                     Mode mode, int baseY, int[] groundY, boolean waterGap,
                     boolean noFoundation) {
        this.designId = designId;
        this.kind = kind;
        this.outward = outward;
        this.startX = startX;
        this.startZ = startZ;
        this.length = length;
        this.depth = depth;
        this.mode = mode;
        this.baseY = baseY;
        this.groundY = groundY;
        this.waterGap = waterGap;
        this.noFoundation = noFoundation;
    }

    public String designId() { return designId; }
    public WallDesign.Kind kind() { return kind; }
    public Direction outward() { return outward; }
    public int startX() { return startX; }
    public int startZ() { return startZ; }
    public int length() { return length; }
    public int depth() { return depth; }
    public Mode mode() { return mode; }
    public int baseY() { return baseY; }
    public boolean waterGap() { return waterGap; }
    public boolean noFoundation() { return noFoundation; }

    public int maxGround() {
        int max = Integer.MIN_VALUE;
        for (int g : groundY) max = Math.max(max, g);
        return max;
    }

    public int minGround() {
        int min = Integer.MAX_VALUE;
        for (int g : groundY) min = Math.min(min, g);
        return min;
    }

    public WallPiece withBaseY(int newBaseY) {
        return new WallPiece(designId, kind, outward, startX, startZ, length, depth,
            Mode.STEPPED, newBaseY, groundY, waterGap, noFoundation);
    }

    public WallPiece withDesignId(String newDesignId) {
        return new WallPiece(newDesignId, kind, outward, startX, startZ, length, depth,
            mode, baseY, groundY, waterGap, noFoundation);
    }

    public WallPiece withNoFoundation() {
        return new WallPiece(designId, kind, outward, startX, startZ, length, depth,
            mode, baseY, groundY, waterGap, true);
    }

    public Direction along() { return outward.getClockWise(); }
    public Direction inward() { return outward.getOpposite(); }

    public Rotation rotation() {
        return switch (outward) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    @FunctionalInterface
    public interface BlockConsumer {
        void accept(BlockPos pos, BlockState state, boolean foundation);
    }

    public void forEachBlock(WallDesign design, BlockConsumer consumer) {
        if (waterGap) return;
        Direction along = along();
        Direction inward = inward();
        Rotation rotation = rotation();
        for (int l = 0; l < length; l++) {
            for (int d = 0; d < depth; d++) {
                int x = startX + along.getStepX() * l + inward.getStepX() * d;
                int z = startZ + along.getStepZ() * l + inward.getStepZ() * d;
                int ground = groundY[l * depth + d];
                int columnBase = mode == Mode.DRAPE ? ground + 1 : baseY;
                BlockState bottom = design.stateAt(l, d, 0);
                // Never continue an openable bottom (door / fence gate / trapdoor): stacking it down a slope duplicates the gate.
                if (!noFoundation && bottom != null && !isOpenable(bottom)) {
                    BlockState continued = bottom.rotate(rotation);
                    for (int y = ground + 1; y < columnBase; y++) {
                        consumer.accept(new BlockPos(x, y, z), continued, true);
                    }
                }
                for (int h = 0; h < design.height(); h++) {
                    if (columnBase + h <= ground) continue;
                    BlockState state = design.stateAt(l, d, h);
                    if (state != null) {
                        consumer.accept(new BlockPos(x, columnBase + h, z), state.rotate(rotation), false);
                    }
                }
            }
        }
    }

    private static boolean isOpenable(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.DOORS)
            || state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
            || state.is(net.minecraft.tags.BlockTags.TRAPDOORS);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Design", designId);
        tag.putBoolean("NoFnd", noFoundation);
        tag.putInt("Kind", kind.ordinal());
        tag.putInt("Outward", outward.get2DDataValue());
        tag.putInt("X", startX);
        tag.putInt("Z", startZ);
        tag.putInt("Length", length);
        tag.putInt("Depth", depth);
        tag.putInt("Mode", mode.ordinal());
        tag.putInt("BaseY", baseY);
        tag.putIntArray("GroundY", groundY.clone());
        tag.putBoolean("WaterGap", waterGap);
        return tag;
    }

    public static WallPiece load(CompoundTag tag) {
        return new WallPiece(
            tag.getString("Design"),
            WallDesign.Kind.values()[tag.getInt("Kind")],
            Direction.from2DDataValue(tag.getInt("Outward")),
            tag.getInt("X"),
            tag.getInt("Z"),
            tag.getInt("Length"),
            tag.getInt("Depth"),
            Mode.values()[tag.getInt("Mode")],
            tag.getInt("BaseY"),
            tag.getIntArray("GroundY").clone(),
            tag.getBoolean("WaterGap"),
            tag.getBoolean("NoFnd"));
    }
}
