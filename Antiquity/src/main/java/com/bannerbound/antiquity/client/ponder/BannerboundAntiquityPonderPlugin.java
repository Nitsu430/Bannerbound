package com.bannerbound.antiquity.client.ponder;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.client.ponder.scenes.BloomeryScenes;
import com.bannerbound.antiquity.client.ponder.scenes.CarpentryScenes;
import com.bannerbound.antiquity.client.ponder.scenes.CraftingStoneScenes;
import com.bannerbound.antiquity.client.ponder.scenes.CrucibleScenes;
import com.bannerbound.antiquity.client.ponder.scenes.FletchingScenes;
import com.bannerbound.antiquity.client.ponder.scenes.KilnScenes;
import com.bannerbound.antiquity.client.ponder.scenes.MasonryScenes;
import com.bannerbound.antiquity.client.ponder.scenes.MortarScenes;
import com.bannerbound.antiquity.client.ponder.scenes.StoneAnvilScenes;
import com.bannerbound.antiquity.client.ponder.scenes.TanneryScenes;

import net.createmod.ponder.api.level.PonderLevel;
import net.createmod.ponder.api.registration.IndexExclusionHelper;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.api.registration.SharedTextRegistrationHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.bannerbound.antiquity.craft.Fletching;
import com.bannerbound.antiquity.craft.Tannery;

/**
 * Ponder plugin for Bannerbound: Antiquity - registers every workstation's construction/operation
 * story boards plus their index tags with Create's Ponder library, wired in
 * {@link PonderBootstrap} only when Create is actually installed. IMPORTANT: this class references
 * {@code net.createmod.ponder.*} and must never be class-loaded outside the
 * {@code ModList.isLoaded("create")} guard, or systems without Create hit a NoClassDefFoundError.
 * Scenes are keyed by item ResourceLocation (the inventory hover-W discovery point); scene names
 * are the structure-NBT paths under assets/&lt;modid&gt;/ponder/&lt;name&gt;.nbt. Stations with no
 * item form are keyed to a proxy item: the bloomery to Coal Block (its construction trigger) and
 * the kiln to Clayed Cobblestone (which it is formed from). BLOOMERY_TAG is also the "ponder"
 * value on the metal_working research node, so renaming its path breaks that link.
 */
@OnlyIn(Dist.CLIENT)
public final class BannerboundAntiquityPonderPlugin implements PonderPlugin {

    public static final ResourceLocation BLOOMERY_TAG = tag("bloomery");

    @Override
    public String getModId() {
        return BannerboundAntiquity.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PonderSceneRegistrationHelper<Item> items = helper.withKeyFunction(BuiltInRegistries.ITEM::getKey);

        items.forComponents(Items.COAL_BLOCK)
            .addStoryBoard("bloomery_construction", BloomeryScenes::construction, BLOOMERY_TAG)
            .addStoryBoard("bloomery_operation",    BloomeryScenes::operation,    BLOOMERY_TAG);

        items.forComponents(item("clayed_cobblestone"))
            .addStoryBoard("kiln_construction", KilnScenes::construction, tag("kiln"))
            .addStoryBoard("kiln_operation",    KilnScenes::operation,    tag("kiln"));

        items.forComponents(item("crucible"))
            .addStoryBoard("crucible_construction", CrucibleScenes::construction, tag("crucible"))
            .addStoryBoard("crucible_operation",    CrucibleScenes::operation,    tag("crucible"));

        items.forComponents(item("tanning_rack"), item("clay_tank"))
            .addStoryBoard("tannery_construction", TanneryScenes::construction, tag("tannery"))
            .addStoryBoard("tannery_operation",    TanneryScenes::operation,    tag("tannery"));

        items.forComponents(item("woodworking_table"))
            .addStoryBoard("woodworking_table_construction", CarpentryScenes::construction, tag("carpentry"))
            .addStoryBoard("woodworking_table_operation",    CarpentryScenes::operation,    tag("carpentry"));

        items.forComponents(item("masons_bench"))
            .addStoryBoard("masons_bench_construction", MasonryScenes::construction, tag("masonry"))
            .addStoryBoard("masons_bench_operation",    MasonryScenes::operation,    tag("masonry"));

        items.forComponents(item("mortar_and_pestle"))
            .addStoryBoard("mortar_construction", MortarScenes::construction, tag("mortar_and_pestle"))
            .addStoryBoard("mortar_operation",    MortarScenes::operation,    tag("mortar_and_pestle"));

        items.forComponents(item("crafting_stone"))
            .addStoryBoard("crafting_stone_construction", CraftingStoneScenes::construction, tag("crafting_stone"))
            .addStoryBoard("crafting_stone_operation",    CraftingStoneScenes::operation,    tag("crafting_stone"));

        items.forComponents(item("fletching_station"))
            .addStoryBoard("fletching_construction", FletchingScenes::construction, tag("fletching_station"))
            .addStoryBoard("fletching_operation",    FletchingScenes::operation,    tag("fletching_station"));

        items.forComponents(item("stone_anvil"))
            .addStoryBoard("stone_anvil_construction", StoneAnvilScenes::construction, tag("stone_anvil"))
            .addStoryBoard("stone_anvil_operation",    StoneAnvilScenes::operation,    tag("stone_anvil"));
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<Item> itemHelper = helper.withKeyFunction(BuiltInRegistries.ITEM::getKey);

        registerStationTag(helper, itemHelper, BLOOMERY_TAG, Items.COAL_BLOCK,
            "The Bloomery", "Smelting iron without electricity — the Antiquity-era forge.",
            Items.COAL_BLOCK);

        registerStationTag(helper, itemHelper, tag("kiln"), item("clayed_cobblestone"),
            "The Kiln", "Bake clay, ceramics and lime in a clayed-cobblestone cube.",
            item("clayed_cobblestone"));

        registerStationTag(helper, itemHelper, tag("crucible"), item("crucible"),
            "The Crucible", "Gather a metal charge, melt it in a Bloomery, pour it into moulds.",
            item("crucible"));

        registerStationTag(helper, itemHelper, tag("tannery"), item("tanning_rack"),
            "The Tannery", "Scrape, cure and dry raw hides into leather.",
            item("tanning_rack"), item("clay_tank"));

        registerStationTag(helper, itemHelper, tag("carpentry"), item("woodworking_table"),
            "The Woodworking Table", "Batch-cut logs into planks, stairs and frames.",
            item("woodworking_table"));

        registerStationTag(helper, itemHelper, tag("masonry"), item("masons_bench"),
            "The Mason's Bench", "Batch-dress stone into slabs, stairs, walls and bricks.",
            item("masons_bench"));

        registerStationTag(helper, itemHelper, tag("mortar_and_pestle"), item("mortar_and_pestle"),
            "The Mortar & Pestle", "Grind ingredients into dyes, inks and pastes.",
            item("mortar_and_pestle"));

        registerStationTag(helper, itemHelper, tag("crafting_stone"), item("crafting_stone"),
            "The Crafting Stone", "The off-station bench where flint and stone become the first tools.",
            item("crafting_stone"));

        registerStationTag(helper, itemHelper, tag("fletching_station"), item("fletching_station"),
            "The Fletching Station", "Refine arrows through a quality-rolled stretch minigame.",
            item("fletching_station"));

        registerStationTag(helper, itemHelper, tag("stone_anvil"), item("stone_anvil"),
            "The Stone Anvil", "Cast molten metal in moulds and cold-hammer parts into tools.",
            item("stone_anvil"));
    }

    private static void registerStationTag(PonderTagRegistrationHelper<ResourceLocation> helper,
                                           PonderTagRegistrationHelper<Item> itemHelper,
                                           ResourceLocation tag, Item icon,
                                           String title, String description, Item... members) {
        helper.registerTag(tag)
            .addToIndex()
            .item(icon, true, false)
            .title(title)
            .description(description)
            .register();
        var builder = itemHelper.addToTag(tag);
        for (Item member : members) {
            builder = builder.add(member);
        }
    }

    @Override
    public void registerSharedText(SharedTextRegistrationHelper helper) {
    }

    @Override
    public void onPonderLevelRestore(PonderLevel ponderLevel) {
    }

    @Override
    public void indexExclusions(IndexExclusionHelper helper) {
    }

    private static ResourceLocation tag(String path) {
        return ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, path);
    }

    private static Item item(String path) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, path));
    }
}
