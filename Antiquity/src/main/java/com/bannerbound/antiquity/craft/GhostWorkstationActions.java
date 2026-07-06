package com.bannerbound.antiquity.craft;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.bannerbound.antiquity.block.entity.GhostRecipeWorkstation;
import com.bannerbound.antiquity.network.GhostActionPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import com.bannerbound.antiquity.BannerboundAntiquity;

/**
 * Server side of the ghost-preview clicks ({@link GhostActionPayload}): browse arrows cycle the
 * candidate recipe, clicking the ghost result pulls the missing ingredients straight from the
 * player's inventory. Only trust the block-entity state here, not the click - the pile may have
 * changed since the client ray-tested, so we act on either a live ghost preview or a solid
 * exact-match result floating above the crafting stone. A citizen mid-craft owns the station
 * (WorkBlockLocks), same rule as the block's own interactions.
 *
 * FILL is overloaded per station: the woodworking table and mason's bench queue one unit into their
 * build list, the pottery slab locks its ghost, and other stations pull the missing ingredients. On
 * the crafting stone a completed pull also crafts immediately (mirroring CraftingStoneBlock.tryCraft),
 * but ONLY when the finished result still matches the recipe the player clicked - a partial pull can
 * accidentally complete a different, smaller recipe (2 sticks = fire sticks while building a bone
 * axe), which must never be auto-crafted. The fletching station only fills; its stretch minigame
 * stays mandatory, so the player still shift-clicks to start it.
 */
@ApiStatus.Internal
public final class GhostWorkstationActions {
    private GhostWorkstationActions() {}

    public static void serverHandle(ServerPlayer player, BlockPos pos, int action) {
        Level level = player.level();
        if (!level.isLoaded(pos)) return;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return;
        if (!(level.getBlockEntity(pos) instanceof GhostRecipeWorkstation ws)) return;
        if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            player.displayClientMessage(Component.translatable("bannerbound.workshop.station_busy")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        boolean hasGhost = !ws.getGhostResult().isEmpty();
        boolean hasResult = !ws.getResult().isEmpty();
        if (!hasGhost && !hasResult) return;
        switch (action) {
            case GhostActionPayload.CYCLE_LEFT, GhostActionPayload.CYCLE_RIGHT -> {
                if (!hasGhost) break;
                ws.cycleGhost(action == GhostActionPayload.CYCLE_LEFT ? -1 : 1);
                level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.7F, 1.0F);
            }
            case GhostActionPayload.FILL -> {
                if (ws instanceof com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity table) {
                    if (table.addSelected()) {
                        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                            SoundSource.BLOCKS, 0.5F, 1.2F);
                    } else {
                        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(),
                            SoundSource.BLOCKS, 0.4F, 0.7F);
                    }
                } else if (ws instanceof com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity bench) {
                    if (bench.addSelected()) {
                        level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                            SoundSource.BLOCKS, 0.5F, 1.2F);
                    } else {
                        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(),
                            SoundSource.BLOCKS, 0.4F, 0.7F);
                    }
                } else if (ws instanceof com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity pottery
                        && !pottery.getResult().isEmpty()) {
                    pottery.lockGhost();
                    level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                        SoundSource.BLOCKS, 0.5F, 1.2F);
                } else if (hasGhost) {
                    fill(player, level, pos, ws);
                } else if (ws instanceof CraftingStoneBlockEntity be && !be.getResult().isEmpty()) {
                    ItemStack out = be.craft();
                    Block.popResource(level, pos.above(), out);
                    level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(),
                        SoundSource.BLOCKS, 0.8F, 1.2F);
                }
            }
            default -> { }
        }
    }

    private static void fill(ServerPlayer player, Level level, BlockPos pos, GhostRecipeWorkstation ws) {
        ws.lockGhost();
        ItemStack target = ws.getGhostResult().copy();
        Direction from = player.getDirection().getOpposite();
        boolean any = false;
        // Snapshot: each insertOne() recomputes the ghost and shrinks this list under us.
        for (ItemStack miss : List.copyOf(ws.getGhostIngredients())) {
            int want = miss.getCount();
            for (int slot = 0; slot < player.getInventory().getContainerSize() && want > 0; slot++) {
                ItemStack s = player.getInventory().getItem(slot);
                while (want > 0 && !s.isEmpty() && s.is(miss.getItem())) {
                    if (!ws.insertOne(s, from)) {
                        want = 0;
                        break;
                    }
                    if (!player.hasInfiniteMaterials()) s.shrink(1);
                    want--;
                    any = true;
                }
            }
        }
        if (!any) {
            player.displayClientMessage(Component.translatable("bannerboundantiquity.ghost_fill.missing")
                .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.1F);
        // Auto-craft only if the completed pile still matches the clicked recipe (else a partial pull crafts a smaller one, e.g. fire sticks).
        if (ws instanceof CraftingStoneBlockEntity be && !be.getResult().isEmpty()
                && be.getResult().is(target.getItem())) {
            ItemStack out = be.craft();
            Block.popResource(level, pos.above(), out);
            level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(),
                SoundSource.BLOCKS, 0.8F, 1.2F);
        }
    }
}
