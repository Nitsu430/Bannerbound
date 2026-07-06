package com.bannerbound.core.api.settlement;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.data.BlockAppealLoader;
import com.bannerbound.core.api.settlement.data.CultureStyleLoader;
import com.bannerbound.core.api.settlement.data.PaletteLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Stateless appeal math for the house/workshop scoring system. Two jobs: resolve a block's
 * effective appeal for a settlement, and turn per-block-type counts into a chunk score.
 *
 * <p>Multi-block dedup: {@link #isAppealDuplicateHalf} flags the non-anchor half of a two-cell
 * object so the scan counts each object once - the UPPER half of any DOUBLE_BLOCK_HALF block
 * (tall grass, large fern, two-tall flowers, doors) and the FOOT of a bed (beds split
 * side-by-side, not stacked). The anchor that counts is LOWER for double blocks and HEAD for
 * beds (HEAD matches how the resident-cap bed count is tallied); {@link #appealAnchor} maps a
 * non-anchor half back to it, used so the appeal-debug overlay reports the counted half's value
 * instead of 0.
 *
 * <p>Value resolution ({@link #appealOf}): base appeal -> each culture style that lists the block
 * OVERRIDES it outright (last style wins, does not add) -> each active palette that lists the
 * block ADDS its bonus. Final value clamped to [-1, 1]. A null/empty palette list reduces to the
 * style-only overload so palette-unaware callers are unaffected.
 *
 * <p>Per-type total ({@link #typeContribution}): 10% diminishing returns - the Nth block is worth
 * appeal * 0.9^(N-1), summing to the closed form appeal * (1 - 0.9^count) * 10. Because only the
 * count matters, the chunk scan needs only a per-type tally, and destroying a block re-clamps for
 * free (a recount of count-1 yields exactly the clamped total).
 */
@ApiStatus.Internal
public final class AppealResolver {
    private AppealResolver() {
    }

    public static boolean isAppealDuplicateHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        return state.hasProperty(BlockStateProperties.BED_PART)
            && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
    }

    public static BlockPos appealAnchor(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            // BedBlock.FACING points foot -> head, so the head is the foot's neighbour that way.
            return pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return pos;
    }

    public static float appealOf(Block block, List<String> styleIds) {
        return appealOf(block, styleIds, null);
    }

    public static float appealOf(Block block, List<String> styleIds, List<String> paletteIds) {
        float v = BlockAppealLoader.base(block);
        if (styleIds != null) {
            for (String id : styleIds) {
                CultureStyle style = CultureStyleLoader.get(id);
                if (style != null && style.hasOverride(block)) {
                    v = style.override(block);
                }
            }
        }
        if (paletteIds != null) {
            for (String id : paletteIds) {
                Palette palette = PaletteLoader.get(id);
                if (palette != null && palette.has(block)) {
                    v += palette.bonus(block);
                }
            }
        }
        return Math.max(-1f, Math.min(1f, v));
    }

    public static double typeContribution(float appeal, int count) {
        if (count <= 0 || appeal == 0f) return 0.0;
        return appeal * (1.0 - Math.pow(0.9, count)) * 10.0;
    }
}
