package com.bannerbound.antiquity.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thatch (straw-mat) bed. Extends vanilla {@link BedBlock} so it keeps all the bed behaviour for
 * free - two-part placement, sleeping, respawn-point setting, and the nether/end explosion - but it
 * renders as an ordinary static block model instead of the vanilla BedRenderer: getRenderShape
 * returns MODEL so the blockstate's foot/head models draw it, and newBlockEntity returns null so no
 * BedBlockEntity is created (it would be of the vanilla BlockEntityType.BED - invalid for this
 * block) and the vanilla bed block-entity renderer never runs over the top of our model. Shape and
 * collision are a flat 4px straw-mat box hugging the low mat that is actually drawn, replacing
 * vanilla's taller bed shape. The whole look comes from the per-part models + the thatch_bed_lower/
 * _upper textures. The block must be in the minecraft:beds tag or sleeping fails.
 */
public class ThatchBedBlock extends BedBlock {
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 4.0, 16.0);

    public ThatchBedBlock(DyeColor color, Properties properties) {
        super(color, properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return null;
    }
}
