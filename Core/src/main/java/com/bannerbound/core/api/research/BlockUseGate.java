package com.bannerbound.core.api.research;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Gates a player <i>using</i> a placed block (opening its terminal/menu, operating it) behind the
 * owning civ's research - the run-time counterpart that keeps a block built or left over from before
 * its tech was learned inert until the settlement that owns its chunk has researched it. "Researched"
 * means the same thing as everywhere else: the owning settlement recognizes the block's item
 * ({@link ItemKnowledge#isKnown}), so adding the block to a research node's {@code unlocks.items}
 * gates both its production AND its use. The knowledge lookup is shared with {@link CraftGating} (via
 * {@code CraftGating.canProduceAt}), so the inventory question-mark, the craft gate, and the use gate
 * never disagree. Always permissive on the client and when there's no server context - the server is
 * authoritative. {@link #checkUse} is the convenience form: it returns true when allowed, else
 * flashes {@code lockedMsgKey} (red) on the action bar and returns false, so callers can write
 * {@code if (!checkUse(...)) return;}.
 */
public final class BlockUseGate {
    private BlockUseGate() {
    }

    public static boolean isUnlocked(Level level, BlockPos pos, Item blockItem) {
        return CraftGating.canProduceAt(level, pos, blockItem);
    }

    public static boolean checkUse(ServerPlayer player, Level level, BlockPos pos, Item blockItem,
                                   String lockedMsgKey) {
        if (isUnlocked(level, pos, blockItem)) {
            return true;
        }
        player.displayClientMessage(Component.translatable(lockedMsgKey).withStyle(ChatFormatting.RED), true);
        return false;
    }
}
