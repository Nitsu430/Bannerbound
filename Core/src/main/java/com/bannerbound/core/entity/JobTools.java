package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.social.JobIcons;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shared job-tool resolution and remote provisioning. The accepted-tool list built by
 * allowedToolsFor is the single source of truth for both the Job-tab tool slot
 * (ServerPayloadHandler.allowedToolItemIds delegates here) and the settlement's auto-provisioning
 * of tools from its supply pool.
 *
 * <p>allowedToolsFor returns, for a job icon role, every tool age's tool for that role whose order
 * is &lt;= the settlement's current tool age (no leaping ahead of tech). Fishing rod and herder
 * rope aren't tiered (any rod / any #bannerbound:herder_rope item); the forager is tool-free (empty
 * list). List ORDER is load-bearing: equipFrom / tryEquipToolFromStorage equip the FIRST stocked
 * match, so entries are ordered to make the intended weapon the default -- hunter bows lead the
 * "hunt" entries once Archery is researched (prefer a bow over the tiered blade/spear), while guard
 * ranged options append after the tiered melee blades (a watch defaults to melee, going ranged only
 * when the armory holds slings/bows but no blades). Anarchy has no pool, so provisioning no-ops there.
 */
@ApiStatus.Internal
public final class JobTools {
    private JobTools() {
    }

    public static int currentToolAgeOrder(Settlement settlement) {
        ToolAge age = ToolAgeLoader.get(settlement.getCurrentToolAge());
        return age == null ? Integer.MIN_VALUE : age.order();
    }

    public static List<Item> allowedToolsFor(Settlement settlement, String role) {
        List<Item> out = new ArrayList<>();
        if (role == null) return out;
        if (JobIcons.ROLE_FISHING_ROD.equals(role)) {
            out.add(Items.FISHING_ROD);
            return out;
        }
        if (JobIcons.ROLE_ROPE.equals(role)) {
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getTag(HerderWorkGoal.HERDER_ROPE_TAG)
                .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            if (out.isEmpty()) out.add(Items.LEAD);
            return out;
        }
        if (JobIcons.ROLE_FORAGE.equals(role)) {
            return out;
        }
        if (JobIcons.ROLE_HUNT.equals(role)
                && com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, com.bannerbound.core.api.hunter.HunterHooks.FLAG_ARCHERY)) {
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getTag(HunterWorkGoal.HUNTER_BOWS_TAG)
                .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            if (out.isEmpty()) out.add(Items.BOW);
        }
        // spear fisher's icon role is "spearfish" but its tool slot uses the tiered "spear" items (lookup alias only)
        String toolRole = "spearfish".equals(role) ? "spear" : role;
        int currentOrder = currentToolAgeOrder(settlement);
        for (ToolAge a : ToolAgeLoader.getAll().values()) {
            if (a.order() > currentOrder) continue;
            Item tool = a.tools().get(toolRole);
            if (tool != null && tool != Items.AIR) out.add(tool);
        }
        // ORDER: ranged options must APPEND after the tiered melee entries so equipFrom defaults a guard to melee
        if ("guard".equals(role)) {
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getTag(GuardWorkGoal.GUARD_SLINGS_TAG)
                .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            if (com.bannerbound.core.api.research.ResearchManager.hasFlag(
                    settlement, com.bannerbound.core.api.hunter.HunterHooks.FLAG_ARCHERY)) {
                net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getTag(HunterWorkGoal.HUNTER_BOWS_TAG)
                    .ifPresent(set -> set.forEach(h -> out.add(h.value())));
            }
        }
        return out;
    }

    public static boolean tryEquipToolFromStorage(CitizenEntity citizen, Settlement settlement) {
        if (citizen.hasJobTool()) return false;
        String role = JobIcons.roleForJob(citizen.getJobType());
        List<Item> tools = allowedToolsFor(settlement, role);
        if (tools.isEmpty()) return false;
        return equipFrom(citizen, DropOffContainers.supplyPool(citizen), tools);
    }

    private static boolean equipFrom(CitizenEntity citizen, Container depot, List<Item> tools) {
        if (depot == null) return false;
        for (Item tool : tools) {
            if (!DropOffContainers.contains(depot, tool)) continue;
            ItemStack one = DropOffContainers.extractOne(depot, tool);
            if (!one.isEmpty()) {
                citizen.setJobTool(one);
                return true;
            }
        }
        return false;
    }
}
