package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Kiln - the 2x2x2 multiblock that forms automatically from eight
 * Clayed Cobblestone blocks. Two scenes (construction, operation) registered under
 * {@code bannerboundantiquity:kiln}. The cube is placed cell-by-cell from its min-corner
 * (OX, OY, OZ), centred on the plate; forEachCell derives each cell's PART index as
 * dx*4 + dy*2 + dz, which must match KilnBlock's PART encoding. PART 0 at the min-corner is the
 * controller carrying the mouth and the BlockEntity, so all NBT pokes (HeldItem, LitTicks,
 * SmeltingActive) target that cell, while litKiln toggles the LIT blockstate on every cell so
 * the whole kiln glows during firing.
 */
@OnlyIn(Dist.CLIENT)
public final class KilnScenes {
    private static final int OX = 1, OY = PonderUtil.CY, OZ = 1;
    private static final Direction FACING = Direction.NORTH;

    private KilnScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("kiln_construction", "Building the Kiln");
        PonderUtil.basePlate(scene, util, 0.85f);

        BlockPos seed = util.grid().at(OX, OY, OZ);

        scene.world().setBlock(seed, Blocks.COBBLESTONE.defaultBlockState(), false);
        scene.idle(8);
        scene.overlay().showControls(util.vector().centerOf(seed), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("minecraft:clay_ball"));
        scene.idle(8);
        scene.world().setBlock(seed, BannerboundAntiquity.CLAYED_COBBLESTONE.get().defaultBlockState(), true);
        scene.overlay().showText(80)
            .text("Right-click Cobblestone with a Clay Ball to make Clayed Cobblestone.")
            .pointAt(util.vector().centerOf(seed))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        forEachCell((dx, dy, dz, part) -> {
            if (dx == 0 && dy == 0 && dz == 0) return;
            scene.world().setBlock(util.grid().at(OX + dx, OY + dy, OZ + dz),
                BannerboundAntiquity.CLAYED_COBBLESTONE.get().defaultBlockState(), false);
        });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Stack eight of them into a 2×2×2 cube.")
            .pointAt(util.vector().topOf(util.grid().at(OX + 1, OY + 1, OZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        placeKiln(scene, util, false, true);
        scene.idle(15);
        scene.overlay().showText(80)
            .text("The cube collapses into a Kiln — its mouth faces you.")
            .pointAt(util.vector().topOf(util.grid().at(OX, OY + 1, OZ)))
            .placeNearTarget();
        scene.idle(70);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("kiln_operation", "Firing the Kiln");
        PonderUtil.basePlate(scene, util, 0.8f);

        BlockPos controller = util.grid().at(OX, OY, OZ);
        BlockPos mouth = util.grid().at(OX, OY, OZ);
        placeKiln(scene, util, false, false);
        scene.idle(10);
        scene.overlay().showText(70)
            .text("The Kiln bakes clay, ceramics and lime — no fuel-hungry furnace required.")
            .pointAt(util.vector().topOf(util.grid().at(OX, OY + 1, OZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        scene.overlay().showControls(util.vector().centerOf(mouth), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("minecraft:clay_ball", 4));
        scene.idle(5);
        scene.world().modifyBlockEntityNBT(util.select().position(OX, OY, OZ), KilnBlockEntity.class, tag -> {
            tag.put("HeldItem", PonderUtil.itemNbt("minecraft:clay_ball", 4));
            tag.putInt("InsertAnimTicks", KilnBlockEntity.SLIDE_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Right-click the mouth to load raw material inside.")
            .pointAt(util.vector().centerOf(mouth))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        scene.overlay().showControls(util.vector().centerOf(mouth), Pointing.DOWN, 50)
            .rightClick().withItem(Items.FLINT_AND_STEEL.getDefaultInstance());
        scene.idle(5);
        litKiln(scene, util, true);
        scene.world().modifyBlockEntityNBT(util.select().position(OX, OY, OZ), KilnBlockEntity.class, tag -> {
            tag.putInt("LitTicks", KilnBlockEntity.MAX_LIT_TICKS);
            tag.putBoolean("SmeltingActive", true);
        });
        scene.idle(15);
        scene.overlay().showText(80)
            .text("Light it with Flint & Steel. Feed Coal or Charcoal to stoke the fire.")
            .pointAt(util.vector().topOf(util.grid().at(OX, OY + 1, OZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(110);

        scene.world().modifyBlockEntityNBT(util.select().position(OX, OY, OZ), KilnBlockEntity.class, tag -> {
            tag.put("HeldItem", PonderUtil.itemNbt("minecraft:brick", 4));
            tag.putInt("InsertAnimTicks", KilnBlockEntity.SLIDE_TICKS);
            tag.putInt("LitTicks", 0);
            tag.putInt("SmeltProgress", 0);
            tag.putBoolean("SmeltingActive", false);
        });
        litKiln(scene, util, false);
        scene.idle(15);
        scene.overlay().showText(90)
            .text("When the fire dies, right-click empty-handed to collect the fired goods.")
            .pointAt(util.vector().centerOf(mouth))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
    }

    private interface CellOp { void apply(int dx, int dy, int dz, int part); }

    private static void forEachCell(CellOp op) {
        for (int dx = 0; dx <= 1; dx++)
            for (int dy = 0; dy <= 1; dy++)
                for (int dz = 0; dz <= 1; dz++)
                    op.apply(dx, dy, dz, dx * 4 + dy * 2 + dz);
    }

    private static void placeKiln(SceneBuilder scene, SceneBuildingUtil util, boolean lit, boolean particles) {
        forEachCell((dx, dy, dz, part) -> {
            BlockState s = BannerboundAntiquity.KILN.get().defaultBlockState()
                .setValue(KilnBlock.PART, part)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING)
                .setValue(KilnBlock.LIT, lit);
            scene.world().setBlock(util.grid().at(OX + dx, OY + dy, OZ + dz), s, particles && part == 0);
        });
    }

    private static void litKiln(SceneBuilder scene, SceneBuildingUtil util, boolean lit) {
        forEachCell((dx, dy, dz, part) ->
            scene.world().modifyBlock(util.grid().at(OX + dx, OY + dy, OZ + dz),
                s -> s.setValue(KilnBlock.LIT, lit), false));
    }
}
