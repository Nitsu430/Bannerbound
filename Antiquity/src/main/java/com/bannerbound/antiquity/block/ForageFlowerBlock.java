package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A plain cross-model ground plant (no collision, breaks if its support is removed) - the base for
 * the biome poison/remedy herbs (wolfsbane, yarrow, ...). Rendered as a flat "X" via a
 * {@code minecraft:block/cross} model with a cutout render type; survival rules come from
 * {@link BushBlock} (places on dirt/grass/farmland). SHAPE is purely the hover outline and
 * interaction box - a slim box hugging the art rather than a full bush cube; tune its px bounds to
 * the texture. The harvested block item is what the Mortar and Pestle grinds into poison paste /
 * antidote.
 */
public class ForageFlowerBlock extends BushBlock {
    public static final MapCodec<ForageFlowerBlock> CODEC = simpleCodec(ForageFlowerBlock::new);

    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 13.0, 13.0);

    public ForageFlowerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected MapCodec<ForageFlowerBlock> codec() {
        return CODEC;
    }
}
