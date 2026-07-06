package com.bannerbound.antiquity.client;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side registry of liquids the Mortar and Pestle can hold: each id resolves to an Entry of
 * block-atlas sprite plus ARGB tint. The block entity only stores a liquid id string; this is
 * where that id becomes pixels (drawn by {@code MortarAndPestleRenderer}). Every builtin entry
 * reuses vanilla's animated still-water sprite and differs only in tint -- the atlas ticker
 * animates any sprite whose texture carries an animation {@code .mcmeta}, so animation comes for
 * free. Builtin ids: "water" (vanilla water blue, ~80% alpha), "ink" (from grinding an ink sac,
 * near-opaque black), and one per DyeColor named exactly by {@code DyeColor.getName()} (e.g.
 * "pink", "light_gray") at ~80% alpha -- grind recipes reference liquids by these ids, so that
 * naming is a data contract. Mods add liquids via {@link #register} without touching the block
 * or renderer; {@link #get} returns null for empty/unknown ids (renderer draws no liquid).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MortarLiquids {
    public record Entry(ResourceLocation spriteId, int tint) {
        public TextureAtlasSprite sprite() {
            return Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(spriteId);
        }
    }

    private static final ResourceLocation WATER_SPRITE =
        ResourceLocation.withDefaultNamespace("block/water_still");

    private static final Map<String, Entry> REGISTRY = new HashMap<>();

    static {
        register("water", new Entry(WATER_SPRITE, 0xCC3F76E4));
        register("ink", new Entry(WATER_SPRITE, 0xF0050505));
        for (DyeColor color : DyeColor.values()) {
            register(color.getName(),
                new Entry(WATER_SPRITE, 0xCC000000 | (color.getTextureDiffuseColor() & 0xFFFFFF)));
        }
    }

    private MortarLiquids() {
    }

    public static void register(String id, Entry entry) {
        REGISTRY.put(id, entry);
    }

    @Nullable
    public static Entry get(String id) {
        return id == null || id.isEmpty() ? null : REGISTRY.get(id);
    }
}
