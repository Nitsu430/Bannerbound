package com.bannerbound.core.social;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.DiggerWorkGoal;
import com.bannerbound.core.entity.FarmerWorkGoal;
import com.bannerbound.core.entity.FisherWorkGoal;
import com.bannerbound.core.entity.ForagerWorkGoal;
import com.bannerbound.core.entity.ForesterWorkGoal;
import com.bannerbound.core.entity.HerderWorkGoal;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Single source of truth for "which icon represents a citizen's job". Both the JOB speech bubble
 * (which draws an {@link ItemStack}) and the name-tag suffix glyph (a bitmap-font char) resolve
 * through here so the two can never disagree - the herder-had-no-icon gap came from two parallel
 * lists drifting apart. Every job's icon is data-driven from the tool-age JSON
 * ({@code data/<ns>/tool_ages/*.json} -> {@link Settlement#getToolForRole}): forester=axe,
 * digger=shovel, farmer=hoe, fisher=fishing_rod, herder=rope, forager=forage, hunter=hunt; changing
 * an icon is a datapack edit, no code. Each role falls back to a hardcoded Core baseline
 * ({@link #defaultFor}) when the current age (or a null tool-age) does not define it. Registry-defined
 * expansion jobs supply their own icon role, baseline, and tool-need via {@code CitizenJobRegistry}.
 * <p>
 * {@link #requiresTool} tells whether a job needs a held tool under a GOVERNMENT: every gatherer role
 * does except the forager (bare-handed; its poppy is only an icon). In ANARCHY every gatherer works
 * tool-free (slower), so callers must AND this with {@code !anarchy} or tool-less workers wrongly
 * read as "no tool".
 * <p>
 * Core stays decoupled from the Antiquity expansion: every name-tag glyph references a
 * {@code minecraft:} texture. If a role resolves to an Antiquity-only item (bone tool, fiber rope)
 * the JOB bubble still renders that real item, but the name-tag glyph - which needs a Core font
 * texture - falls back to the role's baseline glyph (wooden tier / lead / etc.).
 */
@ApiStatus.Internal
public final class JobIcons {
    public static final String ROLE_FISHING_ROD = "fishing_rod";
    public static final String ROLE_ROPE = "rope"; // value "rope" gates the herder tool slot in the network handler - do not rename
    public static final String ROLE_FORAGE = "forage";
    public static final String ROLE_HUNT = "hunt";

    private JobIcons() {}

    public static String roleForJob(String typeId) {
        if (ForesterWorkGoal.JOB_TYPE_ID.equals(typeId)) return "axe";
        if (DiggerWorkGoal.JOB_TYPE_ID.equals(typeId)) return "shovel";
        if (FarmerWorkGoal.JOB_TYPE_ID.equals(typeId)) return "hoe";
        if (FisherWorkGoal.JOB_TYPE_ID.equals(typeId)) return ROLE_FISHING_ROD;
        if (HerderWorkGoal.JOB_TYPE_ID.equals(typeId)) return ROLE_ROPE;
        if (ForagerWorkGoal.JOB_TYPE_ID.equals(typeId)) return ROLE_FORAGE;
        return com.bannerbound.core.api.job.CitizenJobRegistry.iconRoleFor(typeId);
    }

    public static boolean requiresTool(String typeId) {
        com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def =
            com.bannerbound.core.api.job.CitizenJobRegistry.byId(typeId);
        if (def != null) return def.toolRequired();
        String role = roleForJob(typeId);
        return role != null && !ROLE_FORAGE.equals(role);
    }

    private static Item defaultFor(String role) {
        if (role == null) return Items.AIR;
        return switch (role) {
            case "axe"            -> Items.WOODEN_AXE;
            case "shovel"         -> Items.WOODEN_SHOVEL;
            case "hoe"            -> Items.WOODEN_HOE;
            case ROLE_FISHING_ROD -> Items.FISHING_ROD;
            case ROLE_ROPE        -> Items.LEAD;
            case ROLE_FORAGE      -> Items.POPPY;
            case ROLE_HUNT        -> Items.WOODEN_SWORD;
            default               -> {
                Item baseline = com.bannerbound.core.api.job.CitizenJobRegistry.baselineForRole(role);
                yield baseline == null ? Items.AIR : baseline;
            }
        };
    }

    public static Item iconItem(Settlement settlement, String typeId) {
        String role = roleForJob(typeId);
        if (role == null) {
            if (com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID.equals(typeId)) {
                Item icon = com.bannerbound.core.api.workshop.WorkBlockRegistry.defaultCrafterIcon();
                if (icon != null) return icon;
            }
            ItemStack s = WorkstationIcons.itemOrdinal(WorkstationIcons.ordinalOf(typeId));
            return s.isEmpty() ? Items.AIR : s.getItem();
        }
        Item t = settlement == null ? Items.AIR : settlement.getToolForRole(role);
        return t == Items.AIR ? defaultFor(role) : t;
    }

    public static int iconItemId(Settlement settlement, String typeId) {
        return BuiltInRegistries.ITEM.getId(iconItem(settlement, typeId));
    }

    public static Item iconItem(Settlement settlement, String typeId, String workshopType) {
        if (com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID.equals(typeId)
                && workshopType != null) {
            Item icon = com.bannerbound.core.api.workshop.WorkBlockRegistry.iconForType(workshopType);
            if (icon != null) return icon;
        }
        return iconItem(settlement, typeId);
    }

    public static int iconItemId(Settlement settlement, String typeId, String workshopType) {
        return BuiltInRegistries.ITEM.getId(iconItem(settlement, typeId, workshopType));
    }

    // item id -> PUA codepoint; MUST stay in sync with assets/bannerbound/font/icons.json (same item, same codepoint)
    private static final Map<ResourceLocation, Character> GLYPHS = Map.ofEntries(
        Map.entry(id("minecraft", "wooden_axe"),    (char) 0xE110),
        Map.entry(id("minecraft", "stone_axe"),     (char) 0xE111),
        Map.entry(id("minecraft", "iron_axe"),      (char) 0xE112),
        Map.entry(id("minecraft", "wooden_shovel"), (char) 0xE113),
        Map.entry(id("minecraft", "stone_shovel"),  (char) 0xE114),
        Map.entry(id("minecraft", "iron_shovel"),   (char) 0xE115),
        Map.entry(id("minecraft", "wooden_hoe"),    (char) 0xE116),
        Map.entry(id("minecraft", "stone_hoe"),     (char) 0xE117),
        Map.entry(id("minecraft", "iron_hoe"),      (char) 0xE118),
        Map.entry(id("minecraft", "fishing_rod"),   (char) 0xE119),
        Map.entry(id("minecraft", "lead"),          (char) 0xE11A),
        Map.entry(id("minecraft", "poppy"),         (char) 0xE11B),
        Map.entry(id("minecraft", "wooden_sword"),  (char) 0xE11C),
        Map.entry(id("minecraft", "stone_sword"),   (char) 0xE11D),
        Map.entry(id("minecraft", "iron_sword"),    (char) 0xE11E),
        Map.entry(id("minecraft", "bow"),           (char) 0xE11F),
        Map.entry(id("minecraft", "cod"),           (char) 0xE120),
        Map.entry(id("bannerboundantiquity", "bone_spear"), (char) 0xE121));

    private static ResourceLocation id(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    public static String glyphForItem(Item item) {
        Character c = GLYPHS.get(BuiltInRegistries.ITEM.getKey(item));
        return c == null ? "" : String.valueOf(c.charValue());
    }

    public static String jobGlyph(Settlement settlement, String typeId) {
        if (typeId == null) return "";
        String g = glyphForItem(iconItem(settlement, typeId));
        if (!g.isEmpty()) return g;
        return glyphForItem(defaultFor(roleForJob(typeId)));
    }
}
