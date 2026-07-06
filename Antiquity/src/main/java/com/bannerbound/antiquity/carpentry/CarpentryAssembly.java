package com.bannerbound.antiquity.carpentry;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * One data-driven "assembly" carpentry recipe: a fixed result item built from a mix of generic
 * materials (planks, sticks), as opposed to the per-wood-family {@link CarpentryOutput}. Loaded from
 * {@code data/<namespace>/carpentry_assembly/*.json}, e.g.
 * <pre>{
 *   "name": "wooden_pickaxe",
 *   "result": "minecraft:wooden_pickaxe",
 *   "yield": 1,
 *   "ingredients": [ { "tag": "minecraft:planks", "count": 3 }, { "item": "minecraft:stick", "count": 2 } ]
 * }</pre>
 * This is what makes wooden tools (and sticks/ladder/bowl) craftable at the table: tools accept ANY
 * plank and always produce the same item, so they cannot ride the family/variant resolution. "name"
 * sorts the picker browse order; "yield" is how many of the result one crafted unit produces. Each
 * Ingredient is a tag OR a single item plus a count (default 1; an empty "tag" means the item form),
 * converts to the block entity's unified per-unit {@link Cost} via costs()/toCost(), and candidates()
 * enumerates the concrete items it can match (the tag's registry contents, or the single item) so the
 * NPC carpenter can pick a plank/stick its storage actually holds.
 */
@ApiStatus.Internal
public record CarpentryAssembly(String name, Item result, int yield, List<Ingredient> ingredients) {
    public static final Codec<CarpentryAssembly> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("name").forGetter(CarpentryAssembly::name),
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("result").forGetter(CarpentryAssembly::result),
        Codec.INT.optionalFieldOf("yield", 1).forGetter(CarpentryAssembly::yield),
        Ingredient.CODEC.listOf().fieldOf("ingredients").forGetter(CarpentryAssembly::ingredients)
    ).apply(i, CarpentryAssembly::new));

    public List<Cost> costs() {
        List<Cost> out = new ArrayList<>(ingredients.size());
        for (Ingredient in : ingredients) out.add(in.toCost());
        return out;
    }

    public record Ingredient(Cost.Kind kind, String ref, int count) {
        public static final Codec<Ingredient> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("tag", "").forGetter(x -> x.kind() == Cost.Kind.TAG ? x.ref() : ""),
            Codec.STRING.optionalFieldOf("item", "").forGetter(x -> x.kind() == Cost.Kind.ITEM ? x.ref() : ""),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Ingredient::count)
        ).apply(i, (tag, item, count) -> tag.isEmpty()
            ? new Ingredient(Cost.Kind.ITEM, item, count)
            : new Ingredient(Cost.Kind.TAG, tag, count)));

        public Cost toCost() {
            return new Cost(kind, ref, count);
        }

        public List<Item> candidates() {
            ResourceLocation id = ResourceLocation.tryParse(ref);
            if (id == null) return List.of();
            if (kind == Cost.Kind.ITEM) {
                return BuiltInRegistries.ITEM.containsKey(id)
                    ? List.of(BuiltInRegistries.ITEM.get(id)) : List.of();
            }
            List<Item> out = new ArrayList<>();
            for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, id))) {
                out.add(h.value());
            }
            return out;
        }
    }
}
