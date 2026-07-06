package com.bannerbound.antiquity.metalworking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.HammerItem;
import com.bannerbound.antiquity.item.KnifeItem;
import com.bannerbound.antiquity.item.TongsItem;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Registers every net-new metalworking item in templated loops so the casting flow stays
 * data-shaped (METALWORKING_PLAN.md). One naming scheme runs end to end:
 * mold (tool-named) -cast-> casting (part-named) -+stick-> tool (tool-named), e.g.
 * clay_mold_axe -> fired_clay_mold_axe -> copper_axe_head -> copper_axe; sword casts a
 * "blade"; chisel and ingot molds cast the finished item directly (no head, no hafting).
 * Spear/arrow molds cast heads finished off-anvil (spear hafted at the Crafting Stone, arrow
 * fletched at the Fletching Station), which is why they are not Haft entries and
 * castingInfo() deliberately skips them. Items live in id-keyed LinkedHashMaps
 * (MOLDS/CASTINGS/TOOLS/HAMMERS/TONGS, insertion order = data-file order) so stations,
 * recipes, and JEI look them up by logical id instead of one static field each; register()
 * is called from the mod constructor before the DeferredRegisters bind to the bus.
 * Metals are copper/tin/bronze in tech order: copper reuses the vanilla ingot, tin/bronze add
 * their own; per-metal numbers (colour, rank, melt point, mB-per-unit, alloy ratios) are
 * data-driven in {@link MetalworkingData}. Alloying is ratio-gated: a crucible charge whose
 * per-component mB fractions hit an AlloyDef band resolves to bronze, otherwise the melt falls
 * back to the metal with the most mB (there is no bronze ore). Only wood/stone template tools
 * imprint a base clay mold (bone/metal tiers deliberately cannot; only the rank-0 stone hammer
 * imprints the hammer mold). Tongs are crafted, not cast, and carried off-hand to lift a molten
 * crucible without burning (see {@link TongsItem}); tongsDurability() is roughly seconds of
 * carry, the green-wood pair being the disposable pre-metal stopgap. The stone hammer is the
 * smithing entry point: works copper/tin, caps bronze at Standard quality.
 * Open: move the casting helpers (requiredMb/castingFor/templateShape) to mold_recipes data.
 */
public final class MetalworkingItems {
    private MetalworkingItems() {}

    public static final List<String> METALS = List.of("copper", "tin", "bronze");

    private enum Haft {
        AXE("axe", "axe_head"),
        PICKAXE("pickaxe", "pickaxe_head"),
        HOE("hoe", "hoe_head"),
        SHOVEL("shovel", "shovel_head"),
        SWORD("sword", "blade"),
        KNIFE("knife", "knife_head"),
        HAMMER("hammer", "hammer_head");
        final String tool;
        final String castingSuffix;
        Haft(String tool, String castingSuffix) { this.tool = tool; this.castingSuffix = castingSuffix; }
    }

    public static final List<String> MOLD_SHAPES = List.of(
        "axe", "pickaxe", "hoe", "shovel", "sword", "knife", "hammer", "chisel", "ingot",
        "spear", "arrow");

    public static final Tier COPPER = metalTier(180, 5.0F, 1.0F, BlockTags.INCORRECT_FOR_IRON_TOOL, 12, "copper");
    public static final Tier TIN    = metalTier(120, 4.0F, 0.5F, BlockTags.INCORRECT_FOR_STONE_TOOL, 8, "tin");
    public static final Tier BRONZE = metalTier(375, 7.0F, 2.0F, BlockTags.INCORRECT_FOR_IRON_TOOL, 18, "bronze");

    private static Tier tierFor(String metal) {
        return switch (metal) {
            case "copper" -> COPPER;
            case "tin" -> TIN;
            case "bronze" -> BRONZE;
            default -> Tiers.STONE;
        };
    }

    public static final Map<String, DeferredItem<Item>> MOLDS = new LinkedHashMap<>();
    public static final Map<String, DeferredItem<Item>> CASTINGS = new LinkedHashMap<>();
    public static final Map<String, DeferredItem<? extends Item>> TOOLS = new LinkedHashMap<>();
    public static final Map<String, DeferredItem<? extends Item>> HAMMERS = new LinkedHashMap<>();
    public static final Map<String, DeferredItem<? extends Item>> TONGS = new LinkedHashMap<>();

    public static void register() {
        var items = BannerboundAntiquity.ITEMS;

        MOLDS.put("clay_mold_base", items.registerSimpleItem("clay_mold_base", new Item.Properties()));
        for (String shape : MOLD_SHAPES) {
            MOLDS.put("clay_mold_" + shape,
                items.registerSimpleItem("clay_mold_" + shape, new Item.Properties()));
            MOLDS.put("fired_clay_mold_" + shape,
                items.registerSimpleItem("fired_clay_mold_" + shape, new Item.Properties()));
        }

        CASTINGS.put("stone_hammer_head",
            items.registerSimpleItem("stone_hammer_head", new Item.Properties()));
        HAMMERS.put("stone_hammer",
            items.registerItem("stone_hammer", p -> new HammerItem(p, Tiers.STONE, "stone"), new Item.Properties()));

        TONGS.put("wooden_tongs",
            items.registerItem("wooden_tongs", p -> new TongsItem(p, tongsDurability("wooden")),
                new Item.Properties()));

        for (String metal : METALS) {
            Tier tier = tierFor(metal);

            for (Haft h : Haft.values()) {
                CASTINGS.put(metal + "_" + h.castingSuffix,
                    items.registerSimpleItem(metal + "_" + h.castingSuffix, new Item.Properties()));
                TOOLS.put(metal + "_" + h.tool, registerTool(metal, h, tier));
                if (h == Haft.HAMMER) {
                    HAMMERS.put(metal + "_hammer", TOOLS.get(metal + "_hammer"));
                }
            }

            CASTINGS.put(metal + "_spear_point",
                items.registerSimpleItem(metal + "_spear_point", new Item.Properties()));
            CASTINGS.put(metal + "_arrow_head",
                items.registerSimpleItem(metal + "_arrow_head", new Item.Properties()));

            TONGS.put(metal + "_tongs",
                items.registerItem(metal + "_tongs", p -> new TongsItem(p, tongsDurability(metal)),
                    new Item.Properties()));

            TOOLS.put(metal + "_chisel",
                items.registerSimpleItem(metal + "_chisel", new Item.Properties()));
            if (!metal.equals("copper")) {
                TOOLS.put(metal + "_ingot",
                    items.registerSimpleItem(metal + "_ingot", new Item.Properties()));
            }
        }
    }

    private static DeferredItem<? extends Item> registerTool(String metal, Haft h, Tier tier) {
        var items = BannerboundAntiquity.ITEMS;
        String id = metal + "_" + h.tool;
        return switch (h) {
            case AXE -> items.registerItem(id,
                p -> new AxeItem(tier, p.attributes(AxeItem.createAttributes(tier, 5.0F, -3.1F))),
                new Item.Properties());
            case PICKAXE -> items.registerItem(id,
                p -> new PickaxeItem(tier, p.attributes(PickaxeItem.createAttributes(tier, 1.0F, -2.8F))),
                new Item.Properties());
            case HOE -> items.registerItem(id,
                p -> new HoeItem(tier, p.attributes(HoeItem.createAttributes(tier, 0.0F, -1.0F))),
                new Item.Properties());
            case SHOVEL -> items.registerItem(id,
                p -> new ShovelItem(tier, p.attributes(ShovelItem.createAttributes(tier, 1.5F, -3.0F))),
                new Item.Properties());
            case SWORD -> items.registerItem(id,
                p -> new SwordItem(tier, p.attributes(SwordItem.createAttributes(tier, 3, -2.4F))),
                new Item.Properties());
            case KNIFE -> items.registerItem(id,
                p -> new KnifeItem(p, tier.getUses(), 3.0, 2.0),
                new Item.Properties());
            case HAMMER -> items.registerItem(id,
                p -> new HammerItem(p, tier, metal),
                new Item.Properties());
        };
    }

    private static int tongsDurability(String metal) {
        return switch (metal) {
            case "tin" -> 128;
            case "copper" -> 192;
            case "bronze" -> 384;
            default -> 48;
        };
    }

    private static Tier metalTier(int uses, float speed, float dmg, TagKey<Block> incorrect,
                                  int enchant, String metalId) {
        return new Tier() {
            @Override public int getUses() { return uses; }
            @Override public float getSpeed() { return speed; }
            @Override public float getAttackDamageBonus() { return dmg; }
            @Override public TagKey<Block> getIncorrectBlocksForDrops() { return incorrect; }
            @Override public int getEnchantmentValue() { return enchant; }
            @Override public Ingredient getRepairIngredient() { return repairIngredient(metalId); }
        };
    }

    private static Ingredient repairIngredient(String metalId) {
        if (metalId.equals("copper")) return Ingredient.of(Items.COPPER_INGOT);
        DeferredItem<? extends Item> ingot = TOOLS.get(metalId + "_ingot");
        return ingot != null ? Ingredient.of(ingot.get()) : Ingredient.EMPTY;
    }

    public static String oreMetal(ItemStack stack) {
        if (stack.is(Items.RAW_COPPER) || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)) {
            return "copper";
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.equals("raw_tin") || path.equals("tin_ore")) return "tin";
        return null;
    }

    public static int colorOf(String metalId) {
        return MetalworkingData.color(metalId);
    }

    public record MeltValue(String metalId, int mb) {}

    public static MeltValue meltValue(ItemStack stack) {
        String ore = oreMetal(stack);
        if (ore != null) return new MeltValue(ore, MetalworkingData.mbPerUnit(ore));
        String m = metalOf(stack.getItem());
        if (!m.isEmpty()) return new MeltValue(m, MetalworkingData.mbPerUnit(m));
        return null;
    }

    public static boolean isSmeltable(ItemStack stack) {
        return meltValue(stack) != null;
    }

    public static MeltValue resolveCharge(List<ItemStack> charge) {
        Map<String, Integer> by = new LinkedHashMap<>();
        int total = 0;
        for (ItemStack s : charge) {
            MeltValue v = meltValue(s);
            if (v == null) continue;
            by.merge(v.metalId(), v.mb(), Integer::sum);
            total += v.mb();
        }
        if (total <= 0) return null;
        String metal = null;
        for (MetalworkingData.AlloyDef alloy : MetalworkingData.alloys()) {
            if (alloy.matches(by, total)) {
                metal = alloy.result();
                break;
            }
        }
        if (metal == null) {
            String best = "";
            int bestMb = -1;
            for (Map.Entry<String, Integer> e : by.entrySet()) {
                if (e.getValue() > bestMb) { bestMb = e.getValue(); best = e.getKey(); }
            }
            metal = best;
        }
        return new MeltValue(metal, total);
    }

    public static String metalOf(Item item) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        for (String m : METALS) {
            if (path.startsWith(m + "_") || path.equals(m)) return m;
        }
        return "";
    }

    public static int requiredMb(String shape) {
        return MetalworkingData.requiredMb(shape);
    }

    public static String haftCastingSuffix(String shape) {
        for (Haft h : Haft.values()) {
            if (h.tool.equals(shape)) return h.castingSuffix;
        }
        return null;
    }

    public static ItemStack castingFor(String shape, String metalId) {
        String suffix = haftCastingSuffix(shape);
        if (suffix != null) {
            DeferredItem<Item> casting = CASTINGS.get(metalId + "_" + suffix);
            return casting != null ? new ItemStack(casting.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("ingot")) {
            if (metalId.equals("copper")) return new ItemStack(Items.COPPER_INGOT);
            DeferredItem<? extends Item> ingot = TOOLS.get(metalId + "_ingot");
            return ingot != null ? new ItemStack(ingot.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("chisel")) {
            DeferredItem<? extends Item> chisel = TOOLS.get(metalId + "_chisel");
            return chisel != null ? new ItemStack(chisel.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("spear")) {
            DeferredItem<Item> head = CASTINGS.get(metalId + "_spear_point");
            return head != null ? new ItemStack(head.get()) : ItemStack.EMPTY;
        }
        if (shape.equals("arrow")) {
            DeferredItem<Item> head = CASTINGS.get(metalId + "_arrow_head");
            return head != null ? new ItemStack(head.get()) : ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    public static String templateShape(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof net.minecraft.world.item.TieredItem tiered) {
            Tier t = tiered.getTier();
            if (t == Tiers.WOOD || t == Tiers.STONE) {
                if (item instanceof net.minecraft.world.item.AxeItem) return "axe";
                if (item instanceof net.minecraft.world.item.PickaxeItem) return "pickaxe";
                if (item instanceof HoeItem) return "hoe";
                if (item instanceof net.minecraft.world.item.ShovelItem) return "shovel";
                if (item instanceof SwordItem) return "sword";
            }
            return null; // bone/metal tiers deliberately can't imprint a mold
        }
        if (item instanceof com.bannerbound.antiquity.item.HammerItem hammer) {
            return hammer.rank() == 0 ? "hammer" : null; // rank 0 = stone hammer only
        }
        if (item instanceof com.bannerbound.antiquity.item.SpearItem) return "spear";
        if (stack.is(net.minecraft.tags.ItemTags.ARROWS)) return "arrow";
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        if (path.equals("flint_knife") || path.equals("wooden_knife")) return "knife";
        if (path.equals("stone_chisel")) return "chisel";
        if (path.endsWith("_ingot")) return "ingot";
        return null;
    }

    public record CastingInfo(String metalId, String shape, Item resultTool, boolean needsStick) {}

    public static CastingInfo castingInfo(Item item) {
        for (Map.Entry<String, DeferredItem<Item>> e : CASTINGS.entrySet()) {
            if (e.getValue().get() != item) continue;
            String id = e.getKey();
            for (String metal : METALS) {
                if (!id.startsWith(metal + "_")) continue;
                String suffix = id.substring(metal.length() + 1);
                for (Haft h : Haft.values()) {
                    if (h.castingSuffix.equals(suffix)) {
                        DeferredItem<? extends Item> tool = TOOLS.get(metal + "_" + h.tool);
                        if (tool != null) {
                            return new CastingInfo(metal, h.tool, tool.get(), true);
                        }
                    }
                }
            }
        }
        return null;
    }

    public static String shapeOfFiredMold(Item item) {
        for (Map.Entry<String, DeferredItem<Item>> e : MOLDS.entrySet()) {
            String id = e.getKey();
            if (id.startsWith("fired_clay_mold_") && e.getValue().get() == item) {
                return id.substring("fired_clay_mold_".length());
            }
        }
        return null;
    }
}
