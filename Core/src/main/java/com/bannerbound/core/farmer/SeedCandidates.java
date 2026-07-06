package com.bannerbound.core.farmer;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;

/**
 * Server-side seed catalog for the farmer job. The candidate list is driven by the item tag
 * bannerbound:farmer_seeds -- modders add their own seed items to that tag and the picker picks them
 * up automatically; the default entries cover the four vanilla crops (wheat, beetroot, carrots,
 * potatoes). itemIds() returns those ids in registry order and is server-side only (the client gets
 * the list as a payload field, no tag lookup needed there); isValid() re-checks tag membership so
 * the pick-seed handler can reject junk values.
 *
 * <p>Per-seed lookups: the planted crop block is derived from BlockItem.getBlock() (covers any
 * ItemNameBlockItem wrapping a CropBlock, which every vanilla seed -- carrot and potato included --
 * already is). cropFor returns null for anything not backed by a CropBlock, which is silently
 * skipped at plant time so a misconfigured tag entry cannot crash the worker. The floating "yield"
 * marker icon comes from a small hardcoded map for vanilla crops (SEED_TO_OUTPUT); modded seeds fall
 * back to the seed item itself when unmapped.
 */
@ApiStatus.Internal
public final class SeedCandidates {
    public static final TagKey<Item> FARMER_SEEDS = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "farmer_seeds"));

    private static final Map<String, Item> SEED_TO_OUTPUT = Map.of(
        "minecraft:wheat_seeds", Items.WHEAT,
        "minecraft:beetroot_seeds", Items.BEETROOT,
        "minecraft:carrot", Items.CARROT,
        "minecraft:potato", Items.POTATO
    );

    private SeedCandidates() {
    }

    public static List<String> itemIds() {
        List<String> out = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(FARMER_SEEDS)) {
            holder.unwrapKey().ifPresent(key -> out.add(key.location().toString()));
        }
        return out;
    }

    public static Block cropFor(Item seedItem) {
        if (seedItem instanceof BlockItem bi && bi.getBlock() instanceof CropBlock cb) {
            return cb;
        }
        return null;
    }

    public static boolean isValid(String id) {
        if (id == null || id.isEmpty()) return false;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return false;
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) return false;
        return item.builtInRegistryHolder().is(FARMER_SEEDS);
    }

    public static Item outputFor(String seedItemId) {
        if (seedItemId == null || seedItemId.isEmpty()) return Items.AIR;
        Item explicit = SEED_TO_OUTPUT.get(seedItemId);
        if (explicit != null) return explicit;
        ResourceLocation rl = ResourceLocation.tryParse(seedItemId);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(rl);
    }
}
