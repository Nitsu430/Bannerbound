package com.bannerbound.antiquity.block.entity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * A workstation with the ghost-recipe preview (crafting stone / fletching station), letting the
 * click payload handler and client-side targeting treat both stations uniformly. Semantics of the
 * shared pile idiom: getResult() is the exactly-matched recipe's output (EMPTY = partial pile =
 * ghost territory); getGhostResult()/getGhostIngredients() describe the selected candidate and its
 * still-missing ingredients (counts = how many more), shown as silhouettes with the floating
 * result + browse arrows at local height ghostPreviewY(); arrows appear when
 * getGhostCandidateCount() >= 2 and clicking the ghost result pulls the missing items from the
 * player's inventory. cycleGhost(-1/+1) steps between candidates and, like lockGhost(), marks the
 * selection player-chosen: a locked selection never auto-switches and the preview hides (rather
 * than jumping recipes) if the pile turns incompatible; the lock clears when the pile empties.
 * insertOne/removeOne add or take back a single item (removeOne pops from the most-recently-
 * touched cell). All mutators are server-side only.
 */
@ApiStatus.Internal
public interface GhostRecipeWorkstation {
    ItemStack getResult();

    ItemStack getGhostResult();

    List<ItemStack> getGhostIngredients();

    int getGhostCandidateCount();

    double ghostPreviewY();

    void cycleGhost(int dir);

    void lockGhost();

    boolean insertOne(ItemStack held, Direction from);

    ItemStack removeOne();
}
